package forge.ai.effect;

import forge.ai.ability.AnimateAi;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

/** Values persistent animation and characteristic-changing outcomes. */
final class AnimationOutcomeEvaluator implements OutcomeEvaluator {
    static final AnimationOutcomeEvaluator INSTANCE = new AnimationOutcomeEvaluator();

    // TODO(effect analysis): Add duration-aware weighting for temporary animations, multiple
    // targets, non-battlefield recipients, optional/control-flow forms, subability chains, and
    // animation parameters whose dynamic choices cannot yet be reproduced reliably for analysis.

    private AnimationOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        if (outcome == null || !isSupportedApi(outcome.getApi())
                || outcome.getSubAbility() != null
                || (!"Permanent".equals(outcome.getParam("Duration"))
                        && !"Perpetual".equals(outcome.getParam("Duration")))
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
            return CardStateDeltaEvaluator.evaluate(outcome, context, resolution,
                    affected -> evaluateCardDelta(outcome, affected, context));
        } catch (final RuntimeException ignored) {
            // Dynamic or malformed script forms contribute no outcome value.
            return 0;
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
}
