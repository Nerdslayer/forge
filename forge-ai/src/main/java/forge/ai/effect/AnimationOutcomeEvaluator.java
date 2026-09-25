package forge.ai.effect;

import forge.ai.ability.AnimateAi;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

/** Values persistent animation and characteristic-changing outcomes. */
final class AnimationOutcomeEvaluator implements OutcomeEvaluator {
    static final AnimationOutcomeEvaluator INSTANCE = new AnimationOutcomeEvaluator();
    private static final double TEMPORARY_ANIMATION_VALUE_WEIGHT = 0.35;

    // TODO(effect analysis): Calibrate temporary-animation weighting against timing and combat;
    // add multiple targets, non-battlefield recipients, optional/control-flow forms, subability
    // chains, and animation parameters whose dynamic choices cannot yet be reproduced reliably.

    private AnimationOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        if (outcome == null || !isSupportedApi(outcome.getApi())
                || outcome.getSubAbility() != null
                || EffectAbilityUtils.hasUnsupportedControlFlow(outcome)
                || !AffectedCardResolver.affectsOnlyBattlefield(outcome, "Zone")) {
            return false;
        }
        if (outcome.getApi() == ApiType.AnimateAll) {
            return !outcome.usesTargeting() && !outcome.hasParam("Defined");
        }
        return !outcome.usesTargeting()
                || AffectedCardResolver.supportsSingleBattlefieldTarget(outcome);
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome,
            final OutcomeEvaluationContext context) {
        try {
            final AffectedCardResolver.Resolution resolution;
            if (outcome.getApi() == ApiType.AnimateAll) {
                resolution = AffectedCardResolver.group(outcome, context, card -> true);
            } else if (outcome.usesTargeting()) {
                resolution = AffectedCardResolver.targeted(outcome, context, card -> true);
            } else {
                resolution = AffectedCardResolver.defined(outcome, context, card -> true);
            }
            final int value = CardStateDeltaEvaluator.evaluate(outcome, context, resolution,
                    affected -> evaluateCardDelta(outcome, affected, context));
            return isTemporary(outcome)
                    ? (int) Math.round(value * TEMPORARY_ANIMATION_VALUE_WEIGHT) : value;
        } catch (final RuntimeException ignored) {
            // Dynamic or malformed script forms contribute no outcome value.
            return context.unsupported();
        }
    }

    private static int evaluateCardDelta(final SpellAbility outcome, final Card affected,
            final OutcomeEvaluationContext context) {
        final Card changed = AnimateAi.becomeAnimatedForEvaluation(affected, outcome);
        return CardStateDeltaEvaluator.evaluateChange(context, affected, changed);
    }

    private static boolean isSupportedApi(final ApiType api) {
        return api == ApiType.Animate || api == ApiType.AnimateAll;
    }

    private static boolean isTemporary(final SpellAbility outcome) {
        final String duration = outcome.getParam("Duration");
        return !"Permanent".equalsIgnoreCase(duration)
                && !"Perpetual".equalsIgnoreCase(duration);
    }
}
