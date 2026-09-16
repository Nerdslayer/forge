package forge.ai.effect;

import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;

/**
 * Estimates repeated uses of a simple activated ability in a game-free reference situation.
 *
 * <p>This deliberately models only generic mana, a source tap, and fixed life payments. It uses
 * the reference resource distributions for the current and future turns and the shared survival
 * checkpoints for future uses. It is not a simulation and does not infer target availability,
 * optional choices, or cumulative resource depletion.</p>
 */
public final class IntrinsicActivationOccurrenceEstimator {
    private IntrinsicActivationOccurrenceEstimator() {
    }

    public static IntrinsicActivationOccurrenceEstimate estimate(final int manaCost,
            final boolean hasTapCost, final PermanentProfile source,
            final IntrinsicReferenceModel model, final IntrinsicEvaluationSettings settings,
            final EntryTiming entryTiming) {
        return estimate(manaCost, hasTapCost, 0, source, model, settings, entryTiming);
    }

    /** Estimates repeated uses with a fixed life payment in addition to mana and tap. */
    public static IntrinsicActivationOccurrenceEstimate estimate(final int manaCost,
            final boolean hasTapCost, final int lifeCost, final PermanentProfile source,
            final IntrinsicReferenceModel model, final IntrinsicEvaluationSettings settings,
            final EntryTiming entryTiming) {
        if (manaCost < 0 || lifeCost < 0 || source == null || model == null || settings == null
                || entryTiming == null) {
            return IntrinsicActivationOccurrenceEstimate.unsupported(
                    "Invalid intrinsic activation inputs");
        }

        // TODO: Account for life paid by earlier activations and future life-loss effects instead
        // of treating every reference opportunity as if it starts with the original life total.
        final double usesPerTurn = model.availableMana().entries().stream()
                .mapToDouble(mana -> mana.weight() * model.lifeTotals().entries().stream()
                        .mapToDouble(life -> life.weight() * (life.value() >= lifeCost
                                ? AbilityOccurrenceEstimator.activationUsesForTurn(mana.value(), manaCost,
                                        hasTapCost, false, true,
                                        settings.maximumActivationUsesPerTurn()) : 0))
                        .sum())
                .sum();
        final double currentUses = usesPerTurn;
        double expected = currentUses;
        int futureTurns = 0;
        final PermanentSurvivalEstimate survival = new PermanentSurvivalEstimator(model)
                .estimate(source, entryTiming);
        final int maximumTurns = settings.recurringTriggerResolutions();
        for (final SurvivalCheckpoint checkpoint : SurvivalCheckpoint.values()) {
            if (!checkpoint.isTurnStart()) {
                continue;
            }
            final int controllerTurn = checkpoint.controllerTurnNumber(entryTiming);
            final boolean currentOpportunity = entryTiming == EntryTiming.NORMAL_SPEED
                    && controllerTurn == 1;
            if (currentOpportunity || controllerTurn < 1 || controllerTurn > maximumTurns) {
                continue;
            }
            futureTurns++;
            final double futureContribution = usesPerTurn
                    * survival.probability(checkpoint)
                    * AbilityOccurrenceEstimator.turnDiscount(controllerTurn);
            expected = Math.min(settings.maximumExpectedOccurrencesPerAbility(),
                    expected + futureContribution);
        }

        return new IntrinsicActivationOccurrenceEstimate(expected, currentUses, usesPerTurn,
                futureTurns, true, lifeCost == 0
                        ? "reference mana and permanent survival"
                        : "reference mana, life-payment availability and permanent survival");
    }
}
