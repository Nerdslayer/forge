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
        public int total() {
            return CombatOutcomePredictor.add(CombatOutcomePredictor.add(combat.score().total(), replyAdjustment),
                    twoTurnPressure.map(CombatTwoTurnPressureEvaluator.Forecast::value).orElse(0));
        }
    }

    /** noAttack is the no-additional-attacks baseline when fixed attackers are supplied. */
    public record Result(Optional<Candidate> best, Optional<Candidate> noAttack, boolean searchExhaustive,
            boolean outcomeDomainComplete, int nodes, List<String> reasons) {
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
        final int startingNodes = budget.used();
        final Set<String> reasons = new LinkedHashSet<>(alternatives.unsupportedReasons());
        reasons.addAll(alternatives.unavailableReasons());
        if (!reasons.isEmpty()) {
            return new Result(Optional.empty(), Optional.empty(), false, false, 0, List.copyOf(reasons));
        }
        if (!alternatives.attackersToDefenders().keySet().containsAll(fixedAttackers)) {
            return new Result(Optional.empty(), Optional.empty(), false, false, 0,
                    List.of("Fixed attackers are absent from public legal alternatives"));
        }
        final List<Integer> fixed = fixedAttackers.stream().sorted().toList();
        final var declarations = CombatAttackCandidates.generate(alternatives.attackersToDefenders().keySet().stream()
                .filter(id -> !fixedAttackers.contains(id)).toList());
        Candidate best = null;
        Candidate baseline = null;
        boolean exhaustive = declarations.exhaustive();
        boolean supported = true;
        // Establish fixed-only reply safety before assigning incremental utility to additions.
        final java.util.ArrayList<List<Integer>> groups = new java.util.ArrayList<>();
        groups.add(fixed);
        declarations.groups().stream().filter(group -> !group.isEmpty()).forEach(group -> {
            final java.util.ArrayList<Integer> combined = new java.util.ArrayList<>(fixed);
            combined.addAll(group);
            groups.add(combined.stream().sorted().toList());
        });
        for (final List<Integer> group : groups) {
            if (!budget.tryConsume()) { exhaustive = false; reasons.add("Shared attack search budget exhausted"); break; }
            final PublicCombatSnapshot current = CombatAttackCandidates.select(alternatives, group);
            final var forecasts = new CombatAttackForecasts(current, readiness, values, budget);
            final CombatBlockSearch.Result blocks = CombatBlockSearch.searchWithReplySafety(current, values, budget,
                    forecasts::reply, forecasts::pressure);
            reasons.addAll(blocks.reasons());
            exhaustive &= blocks.searchExhaustive();
            supported &= blocks.outcomeDomainComplete();
            if (blocks.best().isEmpty()) { supported = false; break; }
            final var combat = blocks.best().orElseThrow();
            Optional<CombatSafetyEvaluator.FollowUp> reply = Optional.empty();
            int adjustment = 0;
            if (combat.projection().terminal() == CombatProjection.Terminal.NONE) {
                final var forecast = combat.followUp().orElseThrow();
                reply = Optional.of(forecast);
                reasons.addAll(forecast.reasons());
                supported &= forecast.supported() && forecast.searchExhaustive();
                exhaustive &= forecast.searchExhaustive();
                if (baseline != null && forecast.supported() && forecast.searchExhaustive() && baseline.reply().isPresent()) {
                    final long delta = (long) forecast.nonterminalUtility() - baseline.reply().orElseThrow().nonterminalUtility();
                    // Immediate casualties are already excluded from the reply ledger. Charge
                    // the incremental public reply in full, not the blocking-pressure heuristic.
                    adjustment = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, delta));
                }
            } else if (!blocks.searchExhaustive()) {
                supported = false;
                reasons.add("A terminal attack result requires exhaustive opposing block responses");
            }
            final boolean certifiedWin = blocks.outcomeDomainComplete() && blocks.searchExhaustive()
                    && combat.projection().terminal() == CombatProjection.Terminal.WIN;
            final Candidate candidate = new Candidate(group, combat, reply, adjustment, certifiedWin,
                    forecasts.cachedPressure(combat.projection()));
            if (baseline == null) { baseline = candidate; }
            if (best == null || compare(candidate, best) > 0) { best = candidate; }
        }
        if (!exhaustive) { reasons.add("Bounded attack/reply search, not a certified complete combat choice"); }
        // TODO: Incomplete mandatory declarations/planeswalkers, concrete events,
        // static survivor changes, alternative reply continuations and declaration/execution rollout.
        return new Result(Optional.ofNullable(best), Optional.ofNullable(baseline), exhaustive, supported,
                budget.used() - startingNodes, List.copyOf(reasons));
    }

    private static int compare(final Candidate left, final Candidate right) {
        if (left.certifiedWin() != right.certifiedWin()) { return left.certifiedWin() ? 1 : -1; }
        if (left.combat().projection().terminal() != right.combat().projection().terminal()) {
            return CombatTransitionValueEvaluator.compare(left.combat().score(), right.combat().score());
        }
        final boolean leftRisk = left.reply().map(CombatSafetyEvaluator.FollowUp::lethalOpportunity).orElse(false);
        final boolean rightRisk = right.reply().map(CombatSafetyEvaluator.FollowUp::lethalOpportunity).orElse(false);
        if (leftRisk != rightRisk) { return leftRisk ? -1 : 1; }
        final int value = Integer.compare(left.total(), right.total());
        // Equal safe declarations may attack more bodies; do not force a poor trade for aggression.
        return value != 0 ? value : Integer.compare(left.attackers().size(), right.attackers().size());
    }
}
