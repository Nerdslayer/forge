package forge.ai.effect;

import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;

/**
 * Estimates repeated uses of a simple activated ability in a game-free reference situation.
 *
 * <p>This deliberately models only generic mana, a source tap, and fixed life payments. It uses
 * the reference resource distributions for the current and future turns and the shared survival
 * hazard for future uses. It is not a simulation and does not infer target availability,
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
        // Tap abilities are valued only on the source controller's turns. A creature also cannot
        // pay a tap cost during the turn it entered because of summoning sickness.
        final double currentUses = hasTapCost
                && (!entryTiming.firstTurnIsControllerTurn()
                        || source.kind() == PermanentKind.CREATURE)
                ? 0 : usesPerTurn;
        double expected = currentUses;
        int futureTurns = 0;
        final PermanentSurvivalEstimator survival = new PermanentSurvivalEstimator(model);
        // Count the source controller's next six turns, never alternating controller/opponent
        // turns. Flash entry starts on the opponent's turn, so its first controller turn is
        // still a future opportunity. TODO: Model optional opponent-turn uses of non-tap abilities
        // separately without counting the same mana or outcome twice.
        for (int opportunity = 1;
                opportunity <= settings.futureActivationControllerTurns(); opportunity++) {
            final int controllerTurn = entryTiming.firstTurnIsControllerTurn()
                    ? opportunity + 1 : opportunity;
            final int relativeTurn = entryTiming.firstTurnIsControllerTurn()
                    ? 2 * controllerTurn - 1 : 2 * controllerTurn;
            futureTurns++;
            final double futureContribution = usesPerTurn
                    * survival.probabilityAtTurnStart(source, entryTiming, relativeTurn)
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
