package forge.ai.effect;

import forge.ai.ComputerUtil;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

/** Values supported outcomes that permanently remove a battlefield permanent. */
final class PermanentRemovalOutcomeEvaluator implements OutcomeEvaluator {
    static final PermanentRemovalOutcomeEvaluator INSTANCE =
            new PermanentRemovalOutcomeEvaluator();

    // TODO(effect analysis): Account for destruction and zone-change replacement effects,
    // regeneration decisions, death/leave triggers, commanders, cards valuable in other zones,
    // downstream static-effect changes, multiple targets, group removal, temporary exile,
    // independently scripted delayed returns, optional/control-flow forms, and subability chains.

    private PermanentRemovalOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        if (outcome == null || outcome.getSubAbility() != null
                || EffectAbilityUtils.hasUnsupportedControlFlow(outcome)
                || (!outcome.usesTargeting() && !outcome.hasParam("Defined"))) {
            return false;
        }
        if (outcome.usesTargeting()
                && !AffectedCardResolver.supportsSingleBattlefieldTarget(outcome)) {
            return false;
        }
        if (outcome.getApi() == ApiType.Destroy) {
            return !outcome.hasParam("Radiance");
        }
        return outcome.getApi() == ApiType.ChangeZone
                && "Battlefield".equals(outcome.getParam("Origin"))
                && "Exile".equals(outcome.getParam("Destination"))
                && !outcome.hasParam("Duration")
                && !outcome.hasParam("ChangeNum")
                && !outcome.hasParam("ChangeType")
                && !outcome.hasParam("Chooser")
                && !outcome.hasParam("DefinedPlayer");
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome,
            final OutcomeEvaluationContext context) {
        try {
            final AffectedCardResolver.Resolution resolution = outcome.usesTargeting()
                    ? AffectedCardResolver.targeted(outcome, context,
                            card -> canRemove(outcome, card))
                    : AffectedCardResolver.defined(outcome, context,
                            card -> canRemove(outcome, card));
            return CardStateDeltaEvaluator.evaluate(outcome, context, resolution,
                    affected -> CardStateDeltaEvaluator.evaluateDeparture(context, affected));
        } catch (final RuntimeException ignored) {
            // Dynamic or malformed script forms contribute no outcome value.
            return 0;
        }
    }

    private static boolean canRemove(final SpellAbility outcome, final Card card) {
        if (!card.isInPlay() || card.isPhasedOut()) {
            return false;
        }
        if (outcome.getApi() != ApiType.Destroy) {
            return true;
        }
        if (!card.canBeDestroyed() || card.getShieldCount() > 0) {
            return false;
        }
        return outcome.hasParam("NoRegen")
                || !ComputerUtil.canRegenerate(card.getController(), card);
    }
}
