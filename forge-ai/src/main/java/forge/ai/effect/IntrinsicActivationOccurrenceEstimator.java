package forge.ai.effect;

import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;

/**
 * Estimates repeated uses of a simple activated ability in a game-free reference situation.
 *
 * <p>This deliberately models generic mana, a source tap, fixed life payments and literal
 * per-turn activation limits. It uses
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

    public static IntrinsicActivationOccurrenceEstimate estimate(final int manaCost,
            final boolean hasTapCost, final int lifeCost, final PermanentProfile source,
            final IntrinsicReferenceModel model, final IntrinsicEvaluationSettings settings,
            final EntryTiming entryTiming) {
        return estimate(manaCost, hasTapCost, lifeCost, source, model, settings, entryTiming, false);
    }

    /** Estimates repeated uses with fixed life payments and an explicit sorcery-speed window. */
    public static IntrinsicActivationOccurrenceEstimate estimate(final int manaCost,
            final boolean hasTapCost, final int lifeCost, final PermanentProfile source,
            final IntrinsicReferenceModel model, final IntrinsicEvaluationSettings settings,
            final EntryTiming entryTiming, final boolean sorcerySpeed) {
        return estimate(manaCost, hasTapCost, lifeCost, source, model, settings, entryTiming,
                sorcerySpeed, Integer.MAX_VALUE);
    }

    /** An explicit per-turn limit applies before averaging affordability, never to the average. */
    public static IntrinsicActivationOccurrenceEstimate estimate(final int manaCost,
            final boolean hasTapCost, final int lifeCost, final PermanentProfile source,
            final IntrinsicReferenceModel model, final IntrinsicEvaluationSettings settings,
            final EntryTiming entryTiming, final boolean sorcerySpeed, final int activationLimit) {
        if (manaCost < 0 || lifeCost < 0 || source == null || model == null || settings == null
                || entryTiming == null || activationLimit < 0) {
            return IntrinsicActivationOccurrenceEstimate.unsupported(
                    "Invalid intrinsic activation inputs");
        }

        // TODO: Account for life paid by earlier activations and future life-loss effects instead
        // of treating every reference opportunity as if it starts with the original life total.
        final double usesPerTurn = model.availableMana().entries().stream()
                .mapToDouble(mana -> mana.weight() * model.referenceIntegers(IntrinsicDrawOutcomeBackend.CONTROLLER_LIFE,
                        model.lifeTotals()).entries().stream()
                        .mapToDouble(life -> life.weight() * (life.value() >= lifeCost
                                ? AbilityOccurrenceEstimator.activationUsesForTurn(mana.value(), manaCost,
                                        hasTapCost, false, true,
                                        Math.min(activationLimit, settings.maximumActivationUsesPerTurn())) : 0))
                        .sum())
                .sum();
        // Tap abilities are valued only on the source controller's turns. Creature/token sources
        // need haste to pay a tap cost on entry. This affects availability, not survival hazards.
        final boolean creature = source.kind() == PermanentKind.CREATURE || source.kind() == PermanentKind.TOKEN;
        final boolean haste = source.keywords().stream().anyMatch("Haste"::equalsIgnoreCase);
        final double currentUses = sorcerySpeed && !entryTiming.firstTurnIsControllerTurn()
                || hasTapCost && (!entryTiming.firstTurnIsControllerTurn()
                        || creature && !haste)
                ? 0 : usesPerTurn;
        double expected = currentUses;
        int futureTurns = 0;
        final PermanentSurvivalEstimator survival = new PermanentSurvivalEstimator(model);
        // Count the source controller's next six turns, never alternating controller/opponent
        // turns. Flash entry starts on the opponent's turn, so its first controller turn is
        // still a future opportunity. TODO: Model opponent-turn uses (including flash/haste taps)
        // separately without counting the same mana, untap window or outcome twice.
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

        final String reason = lifeCost == 0
                        ? "reference mana and permanent survival"
                        : "reference mana, life-payment availability and permanent survival";
        // TODO: Dynamic limits, once-per-game/shared limits and extra activations from other
        // cards need explicit state. This bound is per ability, per controller-turn opportunity.
        return new IntrinsicActivationOccurrenceEstimate(expected, currentUses, usesPerTurn,
                futureTurns, true, reason + (activationLimit == Integer.MAX_VALUE ? ""
                        : "; per-turn activation limit " + activationLimit));
    }
}
