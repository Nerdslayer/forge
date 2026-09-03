package forge.ai.effect;

import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.spellability.SpellAbility;

/** Values supported permanent control-change outcomes. */
final class ControlChangeOutcomeEvaluator implements OutcomeEvaluator {
    static final ControlChangeOutcomeEvaluator INSTANCE = new ControlChangeOutcomeEvaluator();

    // TODO(effect analysis): Support temporary control changes with duration/attack-use weighting,
    // exchanges, player control, multiple and group recipients, choices and targeting players,
    // untapping and added keywords, controller-sensitive continuous effects on the changed card,
    // downstream static-effect changes to other permanents, optional/control-flow forms, and
    // subability chains.

    private ControlChangeOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        if (outcome == null || outcome.getApi() != ApiType.GainControl
                || outcome.getSubAbility() != null || outcome.hasParam("LoseControl")
                || outcome.hasParam("AllValid") || outcome.hasParam("Choices")
                || outcome.hasParam("Chooser") || outcome.hasParam("TargetingPlayer")
                || outcome.hasParam("Optional") || outcome.hasParam("Untap")
                || outcome.hasParam("AddKWs")
                || EffectAbilityUtils.hasUnsupportedControlFlow(outcome)
                || (!outcome.usesTargeting() && !outcome.hasParam("Defined"))) {
            return false;
        }
        return !outcome.usesTargeting()
                || AffectedCardResolver.supportsSingleBattlefieldTarget(outcome);
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome,
            final OutcomeEvaluationContext context) {
        try {
            final Player newController = resolveNewController(outcome);
            if (newController == null) {
                return 0;
            }
            final AffectedCardResolver.Resolution resolution = outcome.usesTargeting()
                    ? AffectedCardResolver.targeted(outcome, context,
                            card -> canChangeControl(card, newController))
                    : AffectedCardResolver.defined(outcome, context,
                            card -> canChangeControl(card, newController));
            return CardStateDeltaEvaluator.evaluate(outcome, context, resolution,
                    affected -> CardStateDeltaEvaluator.evaluateControlChange(
                            context, affected, newController));
        } catch (final RuntimeException ignored) {
            // Dynamic, illegal, or malformed control changes contribute no outcome value.
            return 0;
        }
    }

    private static Player resolveNewController(final SpellAbility outcome) {
        if (!outcome.hasParam("NewController")) {
            return outcome.getActivatingPlayer();
        }
        final PlayerCollection players = AbilityUtils.getDefinedPlayers(outcome.getHostCard(),
                outcome.getParam("NewController"), outcome);
        return players.size() == 1 ? players.get(0) : null;
    }

    private static boolean canChangeControl(final Card card, final Player newController) {
        return card.isInPlay() && !card.isPhasedOut()
                && card.getController() != newController
                && card.canBeControlledBy(newController);
    }
}
