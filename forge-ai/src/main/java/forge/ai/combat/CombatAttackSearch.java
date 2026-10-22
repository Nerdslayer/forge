package forge.ai.combat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Frozen ordinary 1v1 declarations, shared opposing blocks, and one nonrecursive reply. */
public final class CombatAttackSearch {
    private CombatAttackSearch() { }

    public record Candidate(List<Integer> attackers, CombatBlockSearch.Candidate combat,
            Optional<CombatSafetyEvaluator.FollowUp> reply, int replyAdjustment, boolean certifiedWin,
            Optional<CombatTwoTurnPressureEvaluator.Forecast> twoTurnPressure) {
        public Candidate { attackers = List.copyOf(attackers); }
        public java.util.Map<Integer, CombatAttackDestination> destinations() {
            final java.util.Map<Integer, CombatAttackDestination> result = new java.util.LinkedHashMap<>();
            combat.assignment().attackersToDefenders().forEach((id, target) -> result.put(id, CombatAttackDestination.fromKey(target)));
            return java.util.Map.copyOf(result);
        }
        public int total() {
            return CombatOutcomePredictor.add(CombatOutcomePredictor.add(combat.score().total(), replyAdjustment),
                    twoTurnPressure.map(CombatTwoTurnPressureEvaluator.Forecast::value).orElse(0));
        }
    }

    /** noAttack is the no-additional-attacks baseline when fixed attackers are supplied. */
    public record Result(Optional<Candidate> best, Optional<Candidate> noAttack, boolean searchExhaustive,
            boolean outcomeDomainComplete, int nodes, List<String> reasons,
            boolean candidateSearchComplete, int declarationsEvaluated) {
        public Result { reasons = List.copyOf(reasons); }
    }

    public static Result search(final PublicCombatSnapshot alternatives, final PreparedCombatValuation values,
            final PublicCombatReadiness readiness, final CombatSearchBudget budget) {
        return search(alternatives, values, readiness, budget, Set.of());
    }

    /** Fixed attackers are present in every alternative, including the no-additional-attacks baseline. */
    public static Result search(final PublicCombatSnapshot alternatives, final PreparedCombatValuation values,
            final PublicCombatReadiness readiness, final CombatSearchBudget budget, final Set<Integer> fixedAttackers) {
        if (alternatives.observingPlayerId() != alternatives.attackingPlayerId()) {
            throw new IllegalArgumentException("The observing AI must be the attacking player");
        }
        final Set<String> reasons = new LinkedHashSet<>(alternatives.unsupportedReasons());
        reasons.addAll(alternatives.unavailableReasons());
        if (!reasons.isEmpty()) { return rejected(reasons); }
        if (!alternatives.attackersToDefenders().keySet().containsAll(fixedAttackers)) {
            return rejected(Set.of("Fixed attackers are absent from public legal alternatives"));
        }
        final List<Integer> fixed = fixedAttackers.stream().sorted().toList();
        final List<Integer> optional = alternatives.attackersToDefenders().keySet().stream()
                .filter(id -> !fixedAttackers.contains(id)).sorted().toList();
        final Evaluation evaluation = new Evaluation(alternatives, values, readiness, budget, reasons);
        final var representatives = CombatAttackEquivalence.representatives(alternatives, values, readiness, optional);
        final var selection = GreedyAttackCandidates.select(fixed, optional, evaluation::evaluate,
                CombatAttackSearch::compare, CombatAttackSearch::compareSelection, id -> representatives.get(id));
        final boolean complete = selection.complete() && evaluation.supported;
        final boolean exhaustive = complete && optional.size() <= 2;
        reasons.add("Greedy singleton/addition search with an independent all-out candidate; not a global optimum certificate");
        if (!complete) { reasons.add("Greedy attack candidates or opposing responses were not completely evaluated"); }
        Optional<Candidate> best = selection.best();
        if (complete && best.isPresent() && budget.remaining() > 0
                && best.orElseThrow().combat().projection().terminal() == CombatProjection.Terminal.NONE) {
            final var allowance = budget.childAllowance(Math.min(4000, budget.remaining() / 4));
            final var refinement = evaluation.withBudget(allowance);
            best = Optional.of(CombatAttackRefiner.refine(best.orElseThrow(),
                    alternatives.attackersToDefenders().entrySet().stream().filter(e -> fixedAttackers.contains(e.getKey()))
                            .collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue)),
                    assignments -> refinement.evaluate(CombatAttackCandidates.assign(alternatives, assignments)),
                    CombatAttackSearch::compare, allowance, reasons));
            reasons.add("Attack refinement completed-assignment cache hits: " + refinement.cacheHits);
        }
        return CombatAttackDecisionPolicy.select(new Result(best, selection.baseline(), exhaustive, evaluation.supported,
                budget.used() - evaluation.startingNodes, List.copyOf(reasons), complete, selection.evaluations()));
    }

    private static Result rejected(final Set<String> reasons) {
        return new Result(Optional.empty(), Optional.empty(), false, false, 0, List.copyOf(reasons), false, 0);
    }

    static final class Evaluation {
        private final PublicCombatSnapshot alternatives;
        private final PreparedCombatValuation values;
        private final PublicCombatReadiness readiness;
        private final CombatSearchBudget budget;
        private final Set<String> reasons;
        private final int startingNodes;
        private final CombatAttackForecasts forecasts;
        private Candidate baseline;
        private boolean supported = true;
        private int cacheHits;
        private final java.util.Map<java.util.Map<Integer, Integer>, Candidate> completed = new java.util.HashMap<>();

        Evaluation(final PublicCombatSnapshot alternatives0, final PreparedCombatValuation values0,
                final PublicCombatReadiness readiness0, final CombatSearchBudget budget0, final Set<String> reasons0) {
            alternatives = alternatives0;
            values = values0;
            readiness = readiness0;
            budget = budget0;
            reasons = reasons0;
            startingNodes = budget.used();
            // Follow-ups depend on the projected state and frozen public board, not on which
            // declaration produced it. Reuse equal projections across singleton/joint candidates.
            forecasts = new CombatAttackForecasts(alternatives, readiness, values, budget);
        }

        private Evaluation(final Evaluation source, final CombatSearchBudget allowance) {
            alternatives = source.alternatives;
            values = source.values;
            readiness = source.readiness;
            budget = allowance;
            reasons = new LinkedHashSet<>();
            startingNodes = budget.used();
            forecasts = source.forecasts.withBudget(allowance);
            baseline = source.baseline;
            completed.putAll(source.completed);
        }

        Evaluation withBudget(final CombatSearchBudget allowance) { return new Evaluation(this, allowance); }
        int cacheHits() { return cacheHits; }

        private Candidate evaluate(final List<Integer> group) {
            return evaluate(CombatAttackCandidates.select(alternatives, group));
        }

        Candidate evaluate(final PublicCombatSnapshot current) {
            if (!budget.tryConsume()) { supported = false; reasons.add("Shared attack search budget exhausted"); return null; }
            final var cached = completed.get(current.attackersToDefenders());
            if (cached != null) { cacheHits++; return cached; }
            boolean candidateSupported = true;
            final List<Integer> group = current.attackersToDefenders().keySet().stream().sorted().toList();
            final CombatBlockSearch.Result blocks = CombatBlockSearch.searchWithReplySafety(current, values, budget,
                    forecasts::reply, forecasts::pressure);
            reasons.addAll(blocks.reasons());
            candidateSupported &= blocks.outcomeDomainComplete() && blocks.searchExhaustive();
            if (blocks.best().isEmpty()) { supported = false; return null; }
            final var combat = blocks.best().orElseThrow();
            Optional<CombatSafetyEvaluator.FollowUp> reply = Optional.empty();
            int adjustment = 0;
            if (combat.projection().terminal() == CombatProjection.Terminal.NONE) {
                final var forecast = combat.followUp().orElseThrow();
                reply = Optional.of(forecast);
                reasons.addAll(forecast.reasons());
                candidateSupported &= forecast.usable();
                if (baseline != null && (baseline.reply().isEmpty() || !baseline.reply().orElseThrow().usable())) {
                    candidateSupported = false;
                    reasons.add("Missing comparable no-attack continuation; not zero reply danger");
                }
                if (baseline != null && forecast.usable() && baseline.reply().isPresent()) {
                    final long delta = (long) forecast.nonterminalUtility() - baseline.reply().orElseThrow().nonterminalUtility();
                    // Immediate casualties are already excluded from the reply ledger. Discount
                    // ordinary future changes; compare() still gives lethal reply risk priority.
                    if (CombatAttackDecisionPolicy.bothRepliesLethal(baseline.reply().orElseThrow(), forecast)) {
                        reasons.add("Ordinary reply adjustment suppressed between usable predicted lethal continuations");
                    } else {
                        adjustment = CombatSafetyEvaluator.discountedFollowUpValue(delta);
                    }
                }
            } else if (!blocks.searchExhaustive()) {
                candidateSupported = false;
                reasons.add("A terminal attack result requires exhaustive opposing block responses");
            }
            final boolean certifiedWin = alternatives.ignoredEffects().isEmpty() && blocks.outcomeDomainComplete() && blocks.searchExhaustive()
                    && combat.projection().terminal() == CombatProjection.Terminal.WIN;
            final Candidate candidate = new Candidate(group, combat, reply, adjustment, certifiedWin,
                    forecasts.cachedPressure(combat.projection()));
            if (baseline == null) { baseline = candidate; }
            supported &= candidateSupported;
            if (candidateSupported || certifiedWin) { completed.put(current.attackersToDefenders(), candidate); }
            // Incomplete opposing searches never contribute an optimistic singleton ranking.
            return candidateSupported || certifiedWin ? candidate : null;
        }

        /** Independent current-combat safeguard; future uncertainty cannot poison terminal scoring. */
        Candidate immediateWin(final PublicCombatSnapshot current, final CombatSearchBudget allowance) {
            final var blocks = CombatBlockSearch.search(current, values, allowance);
            if (!blocks.outcomeDomainComplete() || !blocks.searchExhaustive() || blocks.best().isEmpty()) { return null; }
            final var combat = blocks.best().orElseThrow();
            if (combat.projection().terminal() != CombatProjection.Terminal.WIN) { return null; }
            return new Candidate(current.attackersToDefenders().keySet().stream().sorted().toList(), combat,
                    Optional.empty(), 0, current.ignoredEffects().isEmpty(), Optional.empty());
        }
    }

    static int compare(final Candidate left, final Candidate right) {
        if (left.certifiedWin() != right.certifiedWin()) { return left.certifiedWin() ? 1 : -1; }
        if (left.combat().projection().terminal() != right.combat().projection().terminal()) {
            return CombatTransitionValueEvaluator.compare(left.combat().score(), right.combat().score());
        }
        final boolean leftRisk = left.reply().map(CombatSafetyEvaluator.FollowUp::lethalOpportunity).orElse(false);
        final boolean rightRisk = right.reply().map(CombatSafetyEvaluator.FollowUp::lethalOpportunity).orElse(false);
        if (leftRisk != rightRisk) { return leftRisk ? -1 : 1; }
        final int value = Integer.compare(left.total(), right.total());
        return value;
    }

    static int compareSelection(final Candidate left, final Candidate right) {
        return CombatAttackRefiner.preferSmaller(left, right, compare(left, right));
    }
}
