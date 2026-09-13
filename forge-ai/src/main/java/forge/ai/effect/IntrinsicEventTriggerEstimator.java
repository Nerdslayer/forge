package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;

/** Estimates bounded event-trigger opportunities using public reference assumptions. */
public final class IntrinsicEventTriggerEstimator {
    private IntrinsicEventTriggerEstimator() {
    }

    public static IntrinsicEventTriggerEstimate estimate(final IntrinsicEventTrigger trigger,
            final PermanentProfile source, final IntrinsicReferenceModel model,
            final IntrinsicEvaluationSettings settings, final EntryTiming entryTiming) {
        if (trigger == null || source == null || model == null || settings == null
                || entryTiming == null) {
            throw new IllegalArgumentException("Event trigger inputs are required");
        }
        final WeightedDistribution<Double> distribution = model.eventRates(trigger.eventType());
        if (distribution == null) {
            return IntrinsicEventTriggerEstimate.unsupported(
                    "No reference event rate for " + trigger.eventType());
        }
        final double eventRate = expectedRate(distribution, trigger.atMostOncePerTurn())
                * trigger.occurrenceMultiplier();
        final PermanentSurvivalEstimate survival = new PermanentSurvivalEstimator(model)
                .estimate(source, entryTiming);
        final List<IntrinsicEventTriggerEstimate.Opportunity> opportunities = new ArrayList<>();
        final int maximum = settings.maximumExpectedOccurrencesPerAbility();
        double expected = 0;
        for (final SurvivalCheckpoint checkpoint : SurvivalCheckpoint.values()) {
            if (!checkpoint.isTurnEnd() || !matchesTurn(trigger.turnScope(), checkpoint, entryTiming)
                    || expected >= maximum) {
                continue;
            }
            final double survivalProbability = survival.probability(checkpoint);
            final double horizonDiscount = AbilityOccurrenceEstimator.turnDiscount(
                    checkpoint.turnNumber());
            final double contribution = Math.min(maximum - expected,
                    eventRate * survivalProbability * horizonDiscount);
            expected += contribution;
            opportunities.add(new IntrinsicEventTriggerEstimate.Opportunity(checkpoint,
                    eventRate, survivalProbability, horizonDiscount, contribution));
        }
        return new IntrinsicEventTriggerEstimate(expected, true, "reference event rate",
                opportunities);
    }

    private static double expectedRate(final WeightedDistribution<Double> distribution,
            final boolean atMostOncePerTurn) {
        return distribution.entries().stream().mapToDouble(entry -> {
            final double rate = atMostOncePerTurn ? Math.min(1, entry.value()) : entry.value();
            return rate * entry.weight();
        }).sum();
    }

    private static boolean matchesTurn(final IntrinsicEventTrigger.TurnScope scope,
            final SurvivalCheckpoint checkpoint, final EntryTiming entryTiming) {
        final boolean controllerTurn = isControllerTurn(checkpoint, entryTiming);
        return switch (scope) {
        case ANY_TURN -> true;
        case CONTROLLER_TURN -> controllerTurn;
        case OPPONENT_TURN -> !controllerTurn;
        };
    }

    private static boolean isControllerTurn(final SurvivalCheckpoint checkpoint,
            final EntryTiming entryTiming) {
        final boolean oddTurn = checkpoint.turnNumber() % 2 == 1;
        return entryTiming.firstTurnIsControllerTurn() == oddTurn;
    }
}
