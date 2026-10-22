package forge.ai.combat;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

/** Blocking-only two-combat utility forecast with an optional bounded hold-and-defend clock. */
final class CombatDefensiveForecastEvaluator {
    static final int MAX_ADDITIONAL_NODES = 12_000;
    record PlanningResult(CombatBlockSearch.Result search, List<String> reasons) {
        PlanningResult { reasons = List.copyOf(reasons); }
    }

    /** Consistent whole-decision degradation; no incomplete defensive candidate gets zero credit. */
    static PlanningResult search(final PublicCombatSnapshot snapshot, final PreparedCombatValuation values,
            final PublicCombatReadiness readiness, final Map<Integer, List<Integer>> fixed,
            final CombatSearchBudget budget, final int maximumAdditionalNodes) {
        return search(snapshot, values, readiness, fixed, budget, maximumAdditionalNodes, 0);
    }

    static PlanningResult search(final PublicCombatSnapshot snapshot, final PreparedCombatValuation values,
            final PublicCombatReadiness readiness, final Map<Integer, List<Integer>> fixed,
            final CombatSearchBudget budget, final int maximumAdditionalNodes, final int turnValue) {
        return search(snapshot, values, readiness, fixed, budget, maximumAdditionalNodes, turnValue, List.of());
    }

    static PlanningResult search(final PublicCombatSnapshot snapshot, final PreparedCombatValuation values,
            final PublicCombatReadiness readiness, final Map<Integer, List<Integer>> fixed,
            final CombatSearchBudget budget, final int maximumAdditionalNodes, final int turnValue,
            final List<CombatAssignment> seeds) {
        // Leave at least half of planning capacity for recovery, separate from live validation.
        // Horizon-enabled work uses half this optional node allotment again so recovery can
        // try the existing defensive forecast before degrading to offensive pressure.
        final int optionalNodes = Math.min(maximumAdditionalNodes, budget.remaining() / 2);
        final CombatSearchBudget optional = budget.childAllowance(turnValue > 0 ? optionalNodes / 2 : optionalNodes);
        final var evaluator = new CombatDefensiveForecastEvaluator(snapshot, values, readiness, optional, turnValue);
        final var forecast = CombatBlockSearch.searchWithSeeds(snapshot, values, optional, readiness, fixed, seeds, evaluator::evaluate);
        if (forecast.outcomeDomainComplete() && forecast.best().isPresent() && optional.remaining() > 0) {
            return new PlanningResult(forecast, List.of("Next-opponent-attack defensive forecast applied; survivalHorizon="
                    + (turnValue > 0) + "; nodes=" + forecast.nodes()));
        }
        final String reason = "Optional defensive forecast discarded consistently; nodes=" + forecast.nodes()
                + ", allowanceRemaining=" + optional.remaining() + ", reasons=" + forecast.reasons();
        if (turnValue > 0) {
            final var recovered = search(snapshot, values, readiness, fixed, budget, maximumAdditionalNodes, 0, seeds);
            final var reasons = new java.util.ArrayList<String>();
            reasons.add("Survival horizon discarded for whole decision; " + reason);
            reasons.addAll(recovered.reasons());
            return new PlanningResult(recovered.search(), reasons);
        }
        return new PlanningResult(CombatBlockSearch.searchWithSeeds(snapshot, values, budget, readiness, fixed, seeds, null), List.of(reason));
    }

    private final PublicCombatSnapshot before;
    private final PreparedCombatValuation values;
    private final PublicCombatReadiness readiness;
    private final CombatSearchBudget budget;
    private final CombatSurvivalHorizonEvaluator horizon;
    private final int turnValue;
    private final Map<CombatProjection, CombatSafetyEvaluator.FollowUp> forecasts = new HashMap<>();
    private final Map<CombatSafetyEvaluator.Continuation, CombatSafetyEvaluator.FollowUp> replies = new HashMap<>();

    CombatDefensiveForecastEvaluator(final PublicCombatSnapshot before0, final PreparedCombatValuation values0,
            final PublicCombatReadiness readiness0, final CombatSearchBudget budget0) {
        this(before0, values0, readiness0, budget0, 0);
    }

    CombatDefensiveForecastEvaluator(final PublicCombatSnapshot before0, final PreparedCombatValuation values0,
            final PublicCombatReadiness readiness0, final CombatSearchBudget budget0, final int turnValue0) {
        before = before0;
        values = values0;
        readiness = readiness0;
        budget = budget0;
        turnValue = turnValue0;
        horizon = turnValue > 0 ? new CombatSurvivalHorizonEvaluator(readiness, budget) : null;
    }

    CombatSafetyEvaluator.FollowUp evaluate(final CombatProjection current) {
        return forecasts.computeIfAbsent(current, projection -> CombatSafetyEvaluator.forecastNextAttack(
                before, projection, readiness, values, budget, this::reply).evaluation());
    }

    private CombatSafetyEvaluator.FollowUp reply(final CombatSafetyEvaluator.Continuation state) {
        return replies.computeIfAbsent(state, continuation -> {
            if (!budget.tryConsume()) {
                return new CombatSafetyEvaluator.FollowUp(false, false, 0, false, List.of(),
                        List.of("Optional defensive forecast shared allowance exhausted"));
            }
            // Readiness was captured for both controllers using public information. Surviving
            // state, untaps and block availability are reconstructed for this explicit second turn.
            // TODO: Extra/skipped turns, intervening spells/activations and cleanup layers beyond
            // the existing public transition domain. A heuristic no-lethal result is not a proof.
            return CombatSafetyEvaluator.forecastNextAttack(continuation.snapshot(), continuation.projection(),
                    readiness.forActivePlayer(before.attackingPlayerId()), continuation.values(), budget, null, true,
                    horizon == null ? null : state0 -> new CombatSurvivalHorizonEvaluator.Opportunity(horizon.evaluate(state0), turnValue)).evaluation();
        });
    }
}
