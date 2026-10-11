package forge.ai.combat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

/** Public survivor-board follow-up with explicit bounded continuations, never an implicit recursive policy. */
public final class CombatSafetyEvaluator {
    private CombatSafetyEvaluator() { }

    public record FollowUp(boolean supported, boolean searchExhaustive, int nonterminalUtility,
            boolean lethalOpportunity, List<Integer> attackers, List<String> reasons, boolean candidateSearchComplete,
            boolean defensiveLethalRisk, Optional<DefensiveDetails> defensiveDetails,
            Optional<CombatSurvivalHorizonEvaluator.Opportunity> survivalOpportunity) {
        public FollowUp { attackers = List.copyOf(attackers); reasons = List.copyOf(reasons); }
        public FollowUp(final boolean supported, final boolean searchExhaustive, final int nonterminalUtility,
                final boolean lethalOpportunity, final List<Integer> attackers, final List<String> reasons,
                final boolean candidateSearchComplete, final boolean defensiveLethalRisk,
                final Optional<DefensiveDetails> defensiveDetails) {
            this(supported, searchExhaustive, nonterminalUtility, lethalOpportunity, attackers, reasons,
                    candidateSearchComplete, defensiveLethalRisk, defensiveDetails, Optional.empty());
        }
        public FollowUp(final boolean supported, final boolean searchExhaustive, final int nonterminalUtility,
                final boolean lethalOpportunity, final List<Integer> attackers, final List<String> reasons,
                final boolean candidateSearchComplete) {
            this(supported, searchExhaustive, nonterminalUtility, lethalOpportunity, attackers, reasons,
                    candidateSearchComplete, false, Optional.empty());
        }
        public FollowUp(final boolean supported, final boolean searchExhaustive, final int nonterminalUtility,
                final boolean lethalOpportunity, final List<Integer> attackers, final List<String> reasons) {
            this(supported, searchExhaustive, nonterminalUtility, lethalOpportunity, attackers, reasons, searchExhaustive);
        }
        /** All chosen heuristic candidates and their opposing blocks were evaluated, not all subsets. */
        public boolean usable() {
            return supported && candidateSearchComplete
                    && survivalOpportunity.map(value -> value.horizon().complete()).orElse(true);
        }
    }

    public record DefensiveDetails(int offensiveUtility, int defensiveUtility, List<Integer> opponentAttackers,
            Map<Integer, List<Integer>> futureBlocks) {
        public DefensiveDetails {
            opponentAttackers = List.copyOf(opponentAttackers);
            futureBlocks = new CombatAssignment(Map.of(), futureBlocks).blockersByAttacker();
        }
    }

    /** Optional scalar continuation for the explicit two-turn heuristic, never a recursive policy. */
    public record Continuation(PublicCombatSnapshot snapshot, CombatProjection projection, PreparedCombatValuation values) { }

    public record Forecast(FollowUp evaluation, Optional<Continuation> continuation) { }

    /** Ordinary speculative combat value retains 70% weight; lethal protection is ranked separately. */
    public static int discountedFollowUpValue(final long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(value * 0.7)));
    }

    /** A heuristic opportunity is not an actual win; current terminal ranking remains separate. */
    public static int incrementalPressure(final FollowUp baseline, final FollowUp candidate) {
        if (!baseline.usable() || !candidate.usable()) {
            throw new IllegalArgumentException("Complete comparable follow-up estimates required");
        }
        final long delta = (long) candidate.nonterminalUtility() - baseline.nonterminalUtility()
                + survivalDelta(baseline, candidate);
        final int ordinary = discountedFollowUpValue(delta);
        final int lethal = candidate.lethalOpportunity() == baseline.lethalOpportunity() ? 0
                : candidate.lethalOpportunity() ? 500 : -500;
        final int protection = candidate.defensiveLethalRisk() == baseline.defensiveLethalRisk() ? 0
                : candidate.defensiveLethalRisk() ? -500 : 500;
        return CombatOutcomePredictor.add(CombatOutcomePredictor.add(ordinary, lethal), protection);
    }

    static int survivalDelta(final FollowUp baseline, final FollowUp candidate) {
        return baseline.survivalOpportunity().isPresent() && candidate.survivalOpportunity().isPresent()
                ? candidate.survivalOpportunity().orElseThrow().delta(baseline.survivalOpportunity().orElseThrow()) : 0;
    }

    public static FollowUp nextAttack(final PublicCombatSnapshot before, final CombatProjection current,
            final PublicCombatReadiness readiness, final PreparedCombatValuation values, final CombatSearchBudget budget) {
        return forecastNextAttack(before, current, readiness, values, budget).evaluation();
    }

    /** Same bounded forecast, retaining the selected public continuation without redoing search. */
    public static Forecast forecastNextAttack(final PublicCombatSnapshot before, final CombatProjection current,
            final PublicCombatReadiness readiness, final PreparedCombatValuation values, final CombatSearchBudget budget) {
        return forecastNextAttack(before, current, readiness, values, budget, null, false);
    }

    /** One explicit continuation at the AI attack stage, never installed in the final reply. */
    static Forecast forecastNextAttack(final PublicCombatSnapshot before, final CombatProjection current,
            final PublicCombatReadiness readiness, final PreparedCombatValuation values, final CombatSearchBudget budget,
            final java.util.function.Function<Continuation, FollowUp> continuation) {
        return forecastNextAttack(before, current, readiness, values, budget, continuation, true);
    }

    static Forecast forecastNextAttack(final PublicCombatSnapshot before, final CombatProjection current,
            final PublicCombatReadiness readiness, final PreparedCombatValuation values, final CombatSearchBudget budget,
            final java.util.function.Function<Continuation, FollowUp> continuation, final boolean includeChumpValue) {
        return forecastNextAttack(before, current, readiness, values, budget, continuation, includeChumpValue, null);
    }

    /** Optional bounded leaf opportunity; no tail utility is added to ordinary combat value. */
    static Forecast forecastNextAttack(final PublicCombatSnapshot before, final CombatProjection current,
            final PublicCombatReadiness readiness, final PreparedCombatValuation values, final CombatSearchBudget budget,
            final java.util.function.Function<Continuation, FollowUp> continuation, final boolean includeChumpValue,
            final java.util.function.Function<Continuation, CombatSurvivalHorizonEvaluator.Opportunity> horizon) {
        if (!current.supported() || !current.available() || current.terminal() != CombatProjection.Terminal.NONE) {
            return new Forecast(rejected("No continuing supported combat state"), Optional.empty());
        }
        if (!readiness.unsupportedReasons().isEmpty()) {
            return new Forecast(new FollowUp(false, false, 0, false, List.of(), readiness.unsupportedReasons()), Optional.empty());
        }
        if (readiness.nextActivePlayerId() != before.defendingPlayerId()) {
            return new Forecast(rejected("Immediate reply timing differs from ordinary alternating turns"), Optional.empty());
        }
        final var transition = CombatTurnProjectionBuilder.advance(before, current, readiness, values);
        if (transition.turn().isEmpty()) {
            return new Forecast(new FollowUp(false, false, 0, false, List.of(), transition.reasons()), Optional.empty());
        }
        final var turn = transition.turn().orElseThrow();
        final var alternatives = turn.alternatives();
        final var survivingValues = turn.values();
        final boolean observingAttacker = before.observingPlayerId() == alternatives.attackingPlayerId();
        final List<Integer> eligible = alternatives.attackersToDefenders().keySet().stream().sorted().toList();
        final Set<String> reasons = new LinkedHashSet<>(transition.reasons());
        final Map<Continuation, CombatSurvivalHorizonEvaluator.Opportunity> opportunities = new java.util.HashMap<>();
        final var hold = CombatAttackCandidates.select(alternatives, List.of());
        final var reference = horizon == null ? null : horizon.apply(new Continuation(hold,
                CombatOutcomePredictor.predict(hold, new CombatAssignment(Map.of(), Map.of())), survivingValues));
        if (reference != null && !reference.horizon().complete()) {
            return new Forecast(rejected("Survival horizon reference incomplete: " + reference.horizon().reasons()), Optional.empty());
        }
        final CombatSurvivalHorizonEvaluator.Opportunity[] attackReference = new CombatSurvivalHorizonEvaluator.Opportunity[1];
        final var selection = GreedyAttackCandidates.select(List.of(), eligible, group -> {
            if (!budget.tryConsume()) { reasons.add("Follow-up shared budget exhausted"); return null; }
            final PublicCombatSnapshot next = CombatAttackCandidates.select(alternatives, group);
            final CombatBlockSearch.Result response = horizon != null ? CombatBlockSearch.searchWithLeafAdjustment(next, survivingValues, budget,
                    projection -> {
                        final var opportunity = opportunities.computeIfAbsent(new Continuation(next, projection, survivingValues), horizon);
                        return new CombatBlockSearch.LeafAdjustment(opportunity.horizon().complete() ? opportunity.delta(reference) : 0,
                                opportunity.horizon().complete(), opportunity.horizon().reasons());
                    }) : continuation == null ? CombatBlockSearch.search(next, survivingValues, budget)
                    : CombatBlockSearch.searchWithUndiscountedReply(next, survivingValues, budget,
                            projection -> continuation.apply(new Continuation(next, projection, survivingValues)), attackReference[0]);
            reasons.addAll(response.reasons());
            // TODO: The block expansion bound ignores used-blocker pruning and can choose a
            // beam for a manageable domain. Audit a tighter bound before relaxing this gate.
            if (!response.outcomeDomainComplete() || !response.searchExhaustive() || response.best().isEmpty()) {
                reasons.add("Incomplete opposing block responses in greedy future attack search");
                return null;
            }
            final var candidate = response.best().orElseThrow();
            if (group.isEmpty()) {
                attackReference[0] = candidate.followUp().flatMap(FollowUp::survivalOpportunity).orElse(null);
            }
            return new FutureCandidate(next, candidate);
        }, (left, right) -> {
            int comparison = CombatTransitionValueEvaluator.compare(left.combat().score(), right.combat().score());
            if (includeChumpValue && left.combat().score().terminal() == right.combat().score().terminal()) {
                final boolean leftRisk = continuation != null && left.combat().followUp().map(FollowUp::lethalOpportunity).orElse(false);
                final boolean rightRisk = continuation != null && right.combat().followUp().map(FollowUp::lethalOpportunity).orElse(false);
                comparison = Boolean.compare(rightRisk, leftRisk);
                if (comparison == 0) {
                    comparison = Integer.compare(futureRanking(left.combat(), attackReference[0]),
                            futureRanking(right.combat(), attackReference[0]));
                }
            }
            return observingAttacker ? comparison : -comparison;
        }, id -> id);
        // TODO: Reuse permutation-equivalent future singleton scores once future-role keys are audited.
        // Greedy search may miss non-prefix combinations; no-lethal is an estimate, not a safety certificate.
        reasons.add("Greedy future singleton/addition search with independent all-out candidate; not a global optimum certificate");
        reasons.add("Greedy future declarations evaluated: " + selection.evaluations());
        if (!selection.complete()) { reasons.add("Greedy future attack candidates were not completely evaluated"); }
        final FutureCandidate selected = selection.best().orElse(null);
        if (selected == null) {
            return new Forecast(new FollowUp(false, false, 0, false, List.of(), List.copyOf(reasons), false), Optional.empty());
        }
        final var candidate = selected.combat();
        final int offensiveUtility = CombatOutcomePredictor.add(CombatOutcomePredictor.add(
                CombatOutcomePredictor.add(candidate.score().permanentLoss().total(), candidate.score().lifeUtility()),
                candidate.score().outcomeUtility()), includeChumpValue ? candidate.chumpLifeAdjustment() : 0);
        final int defensiveUtility = continuation == null ? 0 : candidate.followUp().map(FollowUp::nonterminalUtility).orElse(0);
        final int utility = CombatOutcomePredictor.add(offensiveUtility, defensiveUtility);
        final boolean lethal = candidate.projection().terminal()
                == (observingAttacker ? CombatProjection.Terminal.WIN : CombatProjection.Terminal.LOSS);
        // TODO: Scheduled state-changing opportunities, static layers beyond fixed additive P/T,
        // untap/turn-skipping rules and alternative equally ranked public reply continuations.
        final boolean defensiveRisk = continuation != null && candidate.followUp().map(FollowUp::lethalOpportunity).orElse(false);
        final Optional<DefensiveDetails> details = continuation == null
                ? (observingAttacker || !includeChumpValue ? Optional.empty() : Optional.of(new DefensiveDetails(0, offensiveUtility,
                        candidate.assignment().attackersToDefenders().keySet().stream().sorted().toList(),
                        candidate.assignment().blockersByAttacker())))
                : Optional.of(new DefensiveDetails(offensiveUtility, defensiveUtility,
                        candidate.followUp().map(FollowUp::attackers).orElse(List.of()),
                        candidate.followUp().flatMap(FollowUp::defensiveDetails).map(DefensiveDetails::futureBlocks).orElse(Map.of())));
        final Optional<CombatSurvivalHorizonEvaluator.Opportunity> opportunity = horizon == null
                ? candidate.followUp().flatMap(FollowUp::survivalOpportunity)
                : Optional.ofNullable(opportunities.get(new Continuation(selected.snapshot(), candidate.projection(), survivingValues)));
        return new Forecast(new FollowUp(true, selection.complete() && eligible.size() <= 2, utility, lethal,
                candidate.assignment().attackersToDefenders().keySet().stream().sorted().toList(), List.copyOf(reasons), selection.complete(),
                defensiveRisk, details, opportunity),
                Optional.of(new Continuation(selected.snapshot(), candidate.projection(), survivingValues)));
    }

    private record FutureCandidate(PublicCombatSnapshot snapshot, CombatBlockSearch.Candidate combat) { }

    private static int futureRanking(final CombatBlockSearch.Candidate candidate,
            final CombatSurvivalHorizonEvaluator.Opportunity reference) {
        if (reference == null) { return candidate.adjustedTotal(); }
        final var followUp = candidate.followUp().orElse(null);
        final int opportunity = followUp == null ? 0 : followUp.survivalOpportunity().map(value -> value.delta(reference)).orElse(0);
        return CombatOutcomePredictor.add(CombatOutcomePredictor.add(candidate.score().total(), candidate.chumpLifeAdjustment()),
                CombatOutcomePredictor.add(followUp == null ? 0 : followUp.nonterminalUtility(), opportunity));
    }

    private static FollowUp rejected(final String reason) {
        return new FollowUp(false, false, 0, false, List.of(), List.of(reason));
    }
}
