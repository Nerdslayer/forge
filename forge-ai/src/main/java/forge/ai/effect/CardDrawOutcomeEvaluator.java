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

    // TODO(effect analysis): Support targeted and dynamic recipients, optional and up-to draws,
    // compound draw/discard or draw/life outcomes, replacement effects, hand-size limits, card
    // quality, timing, and drawing from an insufficient library (including losing the game).
    private static final Set<String> SUPPORTED_PARAMS = Set.of(
            "DB", "Defined", "NumCards", "Reveal", "RememberDrawn",
            "SpellDescription", "StackDescription");

    private CardDrawOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        return outcome != null
                && outcome.getApi() == ApiType.Draw
                && outcome.getSubAbility() == null
                && !outcome.usesTargeting()
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
            final List<Player> recipients = AbilityUtils.getDefinedPlayers(outcome.getHostCard(),
                    outcome.getParamOrDefault("Defined", "You"), outcome);
            for (final Player recipient : recipients) {
                final int amount = Math.min(requested, Math.min(
                        StaticAbilityCantDraw.canDrawAmount(recipient, requested),
                        recipient.getCardsIn(ZoneType.Library).size()));
                final int drawValue = PlayerResourceValueEvaluator.evaluateCardDraw(
                        recipient.getCardsIn(ZoneType.Hand).size(), amount);
                value = EffectMath.add(value,
                        orientForRecipient(context, recipient, drawValue));
            }
            return value;
        } catch (final RuntimeException ignored) {
            return 0;
        }
    }

    private static boolean hasSupportedRecipient(final SpellAbility outcome) {
        final String defined = outcome.getParamOrDefault("Defined", "You");
        return "You".equals(defined) || "Opponent".equals(defined);
    }

    private static int orientForRecipient(final OutcomeEvaluationContext context,
            final Player recipient, final int value) {
        return recipient.isOpponentOf(context.evaluatingAi())
                ? value : EffectMath.negate(value);
    }
}
