package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;

import forge.ai.ComputerUtilCard;
import forge.ai.ability.TokenAi;
import forge.game.ability.AbilityUtils;
import forge.game.ability.effects.CopyPermanentEffect;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Shared construction of analysis-only token prototypes. */
final class EffectTokenUtils {
    private EffectTokenUtils() {
    }

    static List<Player> resolvePlayers(final SpellAbility outcome,
            final String param, final String defaultValue) {
        return AbilityUtils.getDefinedPlayers(outcome.getHostCard(),
                outcome.getParamOrDefault(param, defaultValue), outcome);
    }

    static List<Card> createPrototypes(final SpellAbility outcome, final Player owner) {
        // TODO(effect analysis): Account for the immediate tactical value of TokenAttacking and
        // TokenBlocking. They are currently accepted but valued like otherwise identical tokens
        // outside combat; TokenTapped still affects the ordinary permanent evaluation.
        final List<Card> tokens = new ArrayList<>();
        for (final String script : outcome.getParam("TokenScript").split(",")) {
            if (script.isBlank()) {
                return List.of();
            }
            final SpellAbility tokenAbility = outcome.copy(outcome.getHostCard(), false);
            tokenAbility.setActivatingPlayer(outcome.getActivatingPlayer());
            tokenAbility.getMapParams().put("TokenScript", script.trim());
            final Card token = TokenAi.spawnToken(owner, tokenAbility);
            if (outcome.hasParam("TokenTapped")) {
                token.setTapped(true);
            }
            tokens.add(token);
        }
        return tokens;
    }

    static Card createCopyPrototype(final SpellAbility outcome,
            final Card original, final Player controller) {
        // TODO(effect analysis): Account for TokenAttacking and TokenBlocking as above once combat
        // context can be valued without turning this lightweight analysis into combat simulation.
        final Card token = CopyPermanentEffect.getProtoType(outcome, original, controller);
        token.setLastKnownZone(controller.getZone(ZoneType.Battlefield));
        if (outcome.hasParam("TokenTapped")) {
            token.setTapped(true);
        }
        ComputerUtilCard.applyStaticContPT(controller.getGame(), token, null);
        return token;
    }
}
