package forge.ai.effect;

import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;

/**
 * Estimates repeated uses of a simple activated ability in a game-free reference situation.
 *
 * <p>This deliberately models only generic mana and a source tap. It uses the reference mana
 * distribution for the current and future turns and the shared survival checkpoints for future
 * uses. It is not a simulation and does not infer target availability, optional choices, or
 * non-mana costs.</p>
 */
public final class IntrinsicActivationOccurrenceEstimator {
    private IntrinsicActivationOccurrenceEstimator() {
    }

    public static IntrinsicActivationOccurrenceEstimate estimate(final int manaCost,
            final boolean hasTapCost, final PermanentProfile source,
            final IntrinsicReferenceModel model, final IntrinsicEvaluationSettings settings,
            final EntryTiming entryTiming) {
        if (manaCost < 0 || source == null || model == null || settings == null
                || entryTiming == null) {
            return IntrinsicActivationOccurrenceEstimate.unsupported(
                    "Invalid intrinsic activation inputs");
        }

        final double usesPerTurn = model.availableMana().entries().stream()
                .mapToDouble(entry -> entry.weight()
                        * usesForTurn(entry.value(), manaCost, hasTapCost, settings))
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
                futureTurns, true, "reference mana and permanent survival");
    }

    private static int usesForTurn(final int availableMana, final int manaCost,
            final boolean hasTapCost, final IntrinsicEvaluationSettings settings) {
        if (hasTapCost) {
            return availableMana >= manaCost ? 1 : 0;
        }
        if (manaCost <= 0) {
            // Avoid assigning infinite intrinsic value to a free, repeatable ability until a
            // repeatability model exists. One use per opportunity is intentionally conservative.
            return 1;
        }
        return Math.min(settings.maximumActivationUsesPerTurn(), availableMana / manaCost);
    }
}
