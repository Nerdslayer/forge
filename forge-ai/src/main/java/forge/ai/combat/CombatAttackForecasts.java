package forge.ai.combat;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Decision-local public forecasts shared by attack selection and actual damage allocation. */
final class CombatAttackForecasts {
    private final PublicCombatReadiness readiness;
    private final PreparedCombatValuation values;
    private final CombatSearchBudget budget;
    private final Map<ForecastKey, CombatSafetyEvaluator.Forecast> replies = new HashMap<>();
    private final Map<ForecastKey, CombatTwoTurnPressureEvaluator.Forecast> pressure = new HashMap<>();

    // One forecast instance belongs to one prepared battlefield. Only the declaration varies.
    private record ForecastKey(Map<Integer, Integer> declaration, CombatProjection outcome) { }

    CombatAttackForecasts(final PublicCombatReadiness readiness0,
            final PreparedCombatValuation values0, final CombatSearchBudget budget0) {
        readiness = readiness0;
        values = values0;
        budget = budget0;
    }

    CombatAttackForecasts withBudget(final CombatSearchBudget allowance) {
        final var result = new CombatAttackForecasts(readiness, values, allowance);
        replies.forEach((key, value) -> { if (value.evaluation().usable()) { result.replies.put(key, value); } });
        pressure.forEach((key, value) -> { if (value.usable()) { result.pressure.put(key, value); } });
        return result;
    }

    CombatSafetyEvaluator.FollowUp reply(final PublicCombatSnapshot selected, final CombatProjection state) {
        return detailedReply(selected, state).evaluation();
    }

    CombatTwoTurnPressureEvaluator.Forecast pressure(final PublicCombatSnapshot selected, final CombatProjection state) {
        return pressure.computeIfAbsent(new ForecastKey(selected.attackersToDefenders(), state), key -> CombatTwoTurnPressureEvaluator.evaluate(
                selected, key.outcome(), readiness, detailedReply(selected, state), budget));
    }

    Optional<CombatTwoTurnPressureEvaluator.Forecast> cachedPressure(final PublicCombatSnapshot selected, final CombatProjection state) {
        return Optional.ofNullable(pressure.get(new ForecastKey(selected.attackersToDefenders(), state)));
    }

    private CombatSafetyEvaluator.Forecast detailedReply(final PublicCombatSnapshot selected, final CombatProjection state) {
        return replies.computeIfAbsent(new ForecastKey(selected.attackersToDefenders(), state), key -> CombatSafetyEvaluator.forecastNextAttack(
                selected, key.outcome(), readiness, values, budget));
    }
}
