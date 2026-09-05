package forge.ai.effect;

import java.util.List;
import java.util.Set;

import forge.ai.PlayerResourceValueEvaluator;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/** Values mandatory, immediate additions of unrestricted mana. */
final class ManaOutcomeEvaluator implements OutcomeEvaluator {
    static final ManaOutcomeEvaluator INSTANCE = new ManaOutcomeEvaluator();

    // TODO(effect analysis): Support reflected, restricted, persistent, targeted, dynamic, optional,
    // and chained mana outcomes; color demand; phase/step timing; current pool contents; whether the
    // recipient can profitably spend the mana; and amounts involving Each or unresolved choices.
    private static final Set<String> SUPPORTED_PARAMS = Set.of(
            "DB", "Defined", "Produced", "Amount", "SpellDescription", "StackDescription");

    private ManaOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        return outcome != null
                && outcome.getApi() == ApiType.Mana
                && outcome.getSubAbility() == null
                && outcome.getManaPart() != null
                && !outcome.usesTargeting()
                && !outcome.hasParam("Optional")
                && SUPPORTED_PARAMS.containsAll(outcome.getMapParams().keySet())
                && hasSupportedRecipient(outcome)
                && outcome.hasParam("Produced")
                && !outcome.getParam("Produced").isBlank()
                && !outcome.getParamOrDefault("Amount", "1").isBlank();
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome,
            final OutcomeEvaluationContext context) {
        try {
            final int amount = outcome.amountOfManaGenerated(true);
            if (amount <= 0) {
                return 0;
            }

            int value = 0;
            final List<Player> recipients = AbilityUtils.getDefinedPlayers(outcome.getHostCard(),
                    outcome.getParamOrDefault("Defined", "You"), outcome);
            for (final Player recipient : recipients) {
                final int manaValue = PlayerResourceValueEvaluator.evaluateMana(amount);
                value = EffectMath.add(value, recipient.isOpponentOf(context.evaluatingAi())
                        ? manaValue : EffectMath.negate(manaValue));
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
}
