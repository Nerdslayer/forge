package forge.ai.effect;

import java.util.List;
import java.util.Set;

import forge.ai.PlayerResourceValueEvaluator;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityCantDraw;
import forge.game.zone.ZoneType;

/** Values mandatory draws of unknown cards using the shared player-resource scale. */
final class CardDrawOutcomeEvaluator implements OutcomeEvaluator {
    static final CardDrawOutcomeEvaluator INSTANCE = new CardDrawOutcomeEvaluator();

    // Targeted recipients and mixed sequences are composed by the shared planner.
    // TODO(effect analysis): Support richer dynamic recipients, optional and up-to draw amounts,
    // replacement effects, hand-size limits, card
    // quality, timing, and drawing from an insufficient library (including losing the game).
    private static final Set<String> SUPPORTED_PARAMS = Set.of(
            "DB", "Defined", "NumCards", "Reveal", "RememberDrawn",
            "ValidTgts", "ValidTgtsDesc", "TgtPrompt", "TargetMin", "TargetMax",
            "SpellDescription", "StackDescription");

    private CardDrawOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        return outcome != null
                && outcome.getApi() == ApiType.Draw
                && outcome.getSubAbility() == null
                && !outcome.hasParam("OptionalDecider")
                && !outcome.hasParam("Upto")
                && SUPPORTED_PARAMS.containsAll(outcome.getMapParams().keySet())
                && hasSupportedRecipient(outcome)
                && !outcome.getParamOrDefault("NumCards", "1").isBlank();
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome,
            final OutcomeEvaluationContext context) {
        try {
            final int requested = AbilityUtils.calculateAmount(outcome.getHostCard(),
                    outcome.getParamOrDefault("NumCards", "1"), outcome);
            if (requested <= 0) {
                return 0;
            }

            int value = 0;
            final List<Player> recipients = PlayerRecipientResolver.resolve(outcome, context);
            for (final Player recipient : recipients) {
                final int library = context.state() == null ? recipient.getCardsIn(ZoneType.Library).size()
                        : context.state().library(recipient);
                final int hand = context.state() == null ? recipient.getCardsIn(ZoneType.Hand).size()
                        : context.state().hand(recipient);
                final int amount = Math.min(requested, Math.min(
                        StaticAbilityCantDraw.canDrawAmount(recipient, requested),
                        library));
                final int drawValue = PlayerResourceValueEvaluator.evaluateCardDraw(
                        hand, amount);
                if (context.state() != null) {
                    context.state().hands.put(recipient, EffectMath.add(hand, amount));
                    context.state().libraries.put(recipient, library - amount);
                }
                value = EffectMath.add(value,
                        orientForRecipient(context, recipient, drawValue));
            }
            return value;
        } catch (final RuntimeException ignored) {
            return context.unsupported();
        }
    }

    private static boolean hasSupportedRecipient(final SpellAbility outcome) {
        final String defined = outcome.getParamOrDefault("Defined", "You");
        return outcome.usesTargeting() || "You".equals(defined) || "Opponent".equals(defined)
                || SpellAbilityOutcomePlanner.sharedPlayer(outcome);
    }

    private static int orientForRecipient(final OutcomeEvaluationContext context,
            final Player recipient, final int value) {
        return recipient.isOpponentOf(context.evaluatingAi())
                ? value : EffectMath.negate(value);
    }
}
