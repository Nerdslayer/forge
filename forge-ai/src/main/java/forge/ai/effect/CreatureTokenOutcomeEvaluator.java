package forge.ai.effect;

import java.util.List;
import java.util.Set;

import forge.ai.ComputerUtilCard;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/** Values immediate token-permanent creation using the resulting token prototypes. */
final class CreatureTokenOutcomeEvaluator implements OutcomeEvaluator {
    static final CreatureTokenOutcomeEvaluator INSTANCE = new CreatureTokenOutcomeEvaluator();

    // TODO(effect analysis): Account for activated abilities on noncreature tokens (for example,
    // Food), temporary, attached, conditional, replacement-modified, and more dynamic-owner token
    // outcomes. Projected tokens do not simulate ETB/death triggers or recompute board-wide static
    // effects; attacking/blocking tokens currently receive ordinary permanent value without a
    // combat adjustment.

    private static final Set<String> SUPPORTED_PARAMS = Set.of(
            "DB", "TokenScript", "TokenOwner", "TokenAmount", "TokenPower",
            "TokenToughness", "TokenTypes", "TokenColors", "TokenTapped",
            "TokenAttacking", "TokenBlocking",
            "LockTokenScript", "RememberTokens", "RememberOriginalTokens", "ImprintTokens",
            "SpellDescription", "StackDescription");

    private CreatureTokenOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        return outcome != null
                && outcome.getApi() == ApiType.Token
                && outcome.getSubAbility() == null
                && !outcome.usesTargeting()
                && outcome.hasParam("TokenScript")
                && !outcome.getParam("TokenScript").isBlank()
                && SUPPORTED_PARAMS.containsAll(outcome.getMapParams().keySet())
                && hasSupportedOwner(outcome);
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome, final OutcomeEvaluationContext context) {
        try {
            final int amount = AbilityUtils.calculateAmount(outcome.getHostCard(),
                    outcome.getParamOrDefault("TokenAmount", "1"), outcome);
            if (amount <= 0) {
                return 0;
            }

            final List<Player> owners = EffectTokenUtils.resolvePlayers(
                    outcome, "TokenOwner", "You");
            if (owners.isEmpty()) {
                return 0;
            }

            int value = 0;
            for (final Player owner : owners) {
                final List<Card> tokens = EffectTokenUtils.createPrototypes(outcome, owner);
                if (tokens.isEmpty()) {
                    return context.unsupported();
                }
                for (final Card token : tokens) {
                    if (context.state() != null) {
                        for (int i = 0; i < amount; i++) { context.state().addToken(token); }
                    }
                    final int tokenValue = ComputerUtilCard.evaluatePermanent(
                            context.evaluatingAi(), token);
                    value = EffectMath.add(value, owner.isOpponentOf(context.evaluatingAi())
                            ? tokenValue : EffectMath.negate(tokenValue));
                }
            }
            return EffectMath.multiply(value, amount);
        } catch (final RuntimeException ignored) {
            return context.unsupported();
        }
    }

    private static boolean hasSupportedOwner(final SpellAbility outcome) {
        final String owner = outcome.getParamOrDefault("TokenOwner", "You");
        return "You".equals(owner) || "Opponent".equals(owner);
    }
}
