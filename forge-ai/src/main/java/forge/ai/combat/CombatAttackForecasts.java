package forge.ai.combat;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Decision-local public forecasts shared by attack selection and actual damage allocation. */
final class CombatAttackForecasts {
    private final PublicCombatSnapshot snapshot;
    private final PublicCombatReadiness readiness;
    private final PreparedCombatValuation values;
    private final CombatSearchBudget budget;
    private final Map<CombatProjection, CombatSafetyEvaluator.Forecast> replies = new HashMap<>();
    private final Map<CombatProjection, CombatTwoTurnPressureEvaluator.Forecast> pressure = new HashMap<>();

    CombatAttackForecasts(final PublicCombatSnapshot snapshot0, final PublicCombatReadiness readiness0,
            final PreparedCombatValuation values0, final CombatSearchBudget budget0) {
        snapshot = snapshot0;
        readiness = readiness0;
        values = values0;
        budget = budget0;
    }

    CombatSafetyEvaluator.FollowUp reply(final CombatProjection state) {
        return detailedReply(state).evaluation();
    }

    CombatTwoTurnPressureEvaluator.Forecast pressure(final CombatProjection state) {
        return pressure.computeIfAbsent(state, outcome -> CombatTwoTurnPressureEvaluator.evaluate(
                snapshot, outcome, readiness, detailedReply(outcome), budget));
    }

    Optional<CombatTwoTurnPressureEvaluator.Forecast> cachedPressure(final CombatProjection state) {
        return Optional.ofNullable(pressure.get(state));
    }

    private CombatSafetyEvaluator.Forecast detailedReply(final CombatProjection state) {
        return replies.computeIfAbsent(state, outcome -> CombatSafetyEvaluator.forecastNextAttack(
                snapshot, outcome, readiness, values, budget));
    }
}
