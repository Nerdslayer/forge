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
        return estimate(trigger, source, model, settings, entryTiming, AbilityOccurrenceMode.REPEATABLE);
    }

    public static IntrinsicEventTriggerEstimate estimate(final IntrinsicEventTrigger trigger,
            final PermanentProfile source, final IntrinsicReferenceModel model,
            final IntrinsicEvaluationSettings settings, final EntryTiming entryTiming,
            final AbilityOccurrenceMode occurrenceMode) {
        if (trigger == null || source == null || model == null || settings == null
                || entryTiming == null || occurrenceMode == null) {
            throw new IllegalArgumentException("Event trigger inputs are required");
        }
        final WeightedDistribution<Double> distribution = model.eventRates(trigger.eventType());
        if (distribution == null) {
            return IntrinsicEventTriggerEstimate.unsupported(
                    "No reference event rate for " + trigger.eventType());
        }
        final double eventRate = expectedRate(distribution, trigger.atMostOncePerTurn(),
                trigger.minimumEventAmount())
                * trigger.occurrenceMultiplier();
        final PermanentSurvivalEstimate survival = new PermanentSurvivalEstimator(model)
                .estimate(source, entryTiming);
        final List<IntrinsicEventTriggerEstimate.Opportunity> opportunities = new ArrayList<>();
        final int maximum = settings.maximumExpectedOccurrencesPerAbility();
        double expected = 0;
        double noEarlierEvent = 1;
        // Convert each reference rate before averaging; E[P(event)] != P(E[event count]).
        final double oneEventProbability = occurrenceMode != AbilityOccurrenceMode.SINGLE_OCCURRENCE ? 0
                : distribution.entries().stream().mapToDouble(entry ->
                trigger.minimumEventAmount() != null && entry.value() < trigger.minimumEventAmount() ? 0
                        : entry.weight() * (trigger.atMostOncePerTurn()
                                ? Math.min(1, Math.min(1, entry.value()) * trigger.occurrenceMultiplier())
                                : AbilityOccurrenceEstimator.atLeastOneProbability(entry.value() * trigger.occurrenceMultiplier()))).sum();
        for (final SurvivalCheckpoint checkpoint : SurvivalCheckpoint.values()) {
            if (!checkpoint.isTurnEnd() || !matchesTurn(trigger.turnScope(), checkpoint, entryTiming)
                    || expected >= maximum) {
                continue;
            }
            final double survivalProbability = survival.probability(checkpoint);
            final int opportunityTurn = opportunityTurnNumber(trigger.turnScope(), checkpoint,
                    entryTiming);
            if (opportunityTurn < 1) {
                continue;
            }
            final double horizonDiscount = AbilityOccurrenceEstimator.turnDiscount(
                    opportunityTurn);
            final double contribution = occurrenceMode == AbilityOccurrenceMode.SINGLE_OCCURRENCE
                    ? noEarlierEvent * oneEventProbability * survivalProbability * horizonDiscount
                    : Math.min(maximum - expected, eventRate * survivalProbability * horizonDiscount);
            if (occurrenceMode == AbilityOccurrenceMode.SINGLE_OCCURRENCE) { noEarlierEvent *= 1 - oneEventProbability; }
            expected += contribution;
            opportunities.add(new IntrinsicEventTriggerEstimate.Opportunity(checkpoint,
                    eventRate, survivalProbability, horizonDiscount, contribution));
        }
        // TODO: Event rates correlated across turns and recursion/reanimation reset policies.
        return new IntrinsicEventTriggerEstimate(expected, true,
                occurrenceMode == AbilityOccurrenceMode.SINGLE_OCCURRENCE ? "single-event likelihood" : "reference event rate",
                opportunities);
    }

    private static int opportunityTurnNumber(final IntrinsicEventTrigger.TurnScope scope,
            final SurvivalCheckpoint checkpoint, final EntryTiming entryTiming) {
        return switch (scope) {
        case CONTROLLER_TURN -> checkpoint.controllerTurnNumber(entryTiming);
        case OPPONENT_TURN -> checkpoint.opponentTurnNumber(entryTiming);
        // Treat an event on either player's turn as belonging to the active player's turn.
        case ANY_TURN -> Math.max(checkpoint.controllerTurnNumber(entryTiming),
                checkpoint.opponentTurnNumber(entryTiming));
        };
    }

    private static double expectedRate(final WeightedDistribution<Double> distribution,
            final boolean atMostOncePerTurn, final Integer minimumEventAmount) {
        return distribution.entries().stream().mapToDouble(entry -> {
            if (minimumEventAmount != null && entry.value() < minimumEventAmount) {
                return 0;
            }
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
