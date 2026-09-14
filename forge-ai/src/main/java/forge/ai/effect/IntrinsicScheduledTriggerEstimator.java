package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;

import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;

/** Combines scheduled timing opportunities with the shared checkpoint survival estimate. */
public final class IntrinsicScheduledTriggerEstimator {
    private IntrinsicScheduledTriggerEstimator() {
    }

    /**
     * Estimates scheduled opportunities during the first bounded controller turns. The schedule
     * is matched against the relative player turn sequence implied by the entry timing, so “your
     * upkeep” is not treated as every upkeep. Survival is applied at the exact checkpoint of each
     * opportunity, while horizon discounts count the relevant player's turns rather than every
     * alternating turn.
     */
    public static IntrinsicScheduledTriggerEstimate estimate(
            final IntrinsicScheduledTrigger trigger, final PermanentProfile source,
            final IntrinsicReferenceModel model, final IntrinsicEvaluationSettings settings,
            final EntryTiming entryTiming) {
        if (trigger == null || model == null || settings == null || entryTiming == null) {
            throw new IllegalArgumentException("Scheduled trigger inputs are required");
        }
        final PermanentSurvivalEstimate survival = new PermanentSurvivalEstimator(model)
                .estimate(source, entryTiming);
        final List<IntrinsicScheduledTriggerEstimate.Opportunity> opportunities = new ArrayList<>();
        final int maximum = settings.recurringTriggerResolutions();
        for (final SurvivalCheckpoint checkpoint : SurvivalCheckpoint.values()) {
            final int opportunityTurn = opportunityTurnNumber(trigger.playerScope(), checkpoint,
                    entryTiming);
            if (opportunities.size() >= maximum || opportunityTurn < 1
                    || opportunityTurn > maximum || !matches(trigger, checkpoint, entryTiming)) {
                continue;
            }
            final double survivalProbability = survival.probability(checkpoint);
            final double horizonDiscount = AbilityOccurrenceEstimator.turnDiscount(
                    opportunityTurn);
            final double contribution = survivalProbability * horizonDiscount;
            opportunities.add(new IntrinsicScheduledTriggerEstimate.Opportunity(
                    checkpoint, survivalProbability, horizonDiscount, contribution));
        }
        final double expected = opportunities.stream()
                .mapToDouble(IntrinsicScheduledTriggerEstimate.Opportunity::expectedContribution)
                .sum();
        return new IntrinsicScheduledTriggerEstimate(expected, opportunities.size(), opportunities,
                survival);
    }

    private static int opportunityTurnNumber(final IntrinsicScheduledTrigger.PlayerScope scope,
            final SurvivalCheckpoint checkpoint, final EntryTiming entryTiming) {
        return switch (scope) {
        case CONTROLLER -> checkpoint.controllerTurnNumber(entryTiming);
        case OPPONENT -> checkpoint.opponentTurnNumber(entryTiming);
        // Use the active player's turn number when either player can produce the trigger.
        case EACH_PLAYER -> Math.max(checkpoint.controllerTurnNumber(entryTiming),
                checkpoint.opponentTurnNumber(entryTiming));
        };
    }

    private static boolean matches(final IntrinsicScheduledTrigger trigger,
            final SurvivalCheckpoint checkpoint, final EntryTiming entryTiming) {
        final boolean controllerTurn = isControllerTurn(checkpoint, entryTiming);
        final boolean playerMatches = switch (trigger.playerScope()) {
        case CONTROLLER -> controllerTurn;
        case OPPONENT -> !controllerTurn;
        case EACH_PLAYER -> true;
        };
        final boolean timingMatches = switch (trigger.schedule()) {
        case UPKEEP, TURN_BEGIN -> checkpoint.isTurnStart();
        case END_STEP -> checkpoint.isTurnEnd();
        };
        return playerMatches && timingMatches;
    }

    private static boolean isControllerTurn(final SurvivalCheckpoint checkpoint,
            final EntryTiming entryTiming) {
        final boolean oddTurn = checkpoint.turnNumber() % 2 == 1;
        return entryTiming.firstTurnIsControllerTurn() == oddTurn;
    }
}
