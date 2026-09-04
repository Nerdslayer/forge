package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.ai.ComputerUtilCard;
import forge.ai.ability.TokenAi;
import forge.game.ability.AbilityKey;
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

    static EffectEvent createBattlefieldEntryEvent(final Card token, final int amount,
            final SpellAbility cause) {
        final Map<AbilityKey, Object> triggerParameters = new EnumMap<>(AbilityKey.class);
        triggerParameters.put(AbilityKey.Card, token);
        triggerParameters.put(AbilityKey.CardLKI, token);
        triggerParameters.put(AbilityKey.Cause, cause);
        triggerParameters.put(AbilityKey.Origin, null);
        triggerParameters.put(AbilityKey.Destination, ZoneType.Battlefield.name());
        return new EffectEvent(EffectType.ZONE_CHANGED, token.getController(),
                List.of(new EffectEvent.Subject(token, amount)), triggerParameters);
    }

    static List<EffectProduction> createProductions(final Card source,
            final Player controller, final List<ProducedToken> tokens,
            final int expectedBatches, final SpellAbility cause) {
        final List<EffectEvent> tokenEvents = new ArrayList<>();
        final List<EffectEvent> zoneEvents = new ArrayList<>();
        for (final ProducedToken produced : tokens) {
            final Map<AbilityKey, Object> triggerParameters = new EnumMap<>(AbilityKey.class);
            triggerParameters.put(AbilityKey.Player, controller);
            triggerParameters.put(AbilityKey.Card, produced.prototype());
            tokenEvents.add(new EffectEvent(EffectType.TOKEN_CREATED, controller,
                    List.of(new EffectEvent.Subject(
                            produced.prototype(), produced.amount())), triggerParameters));
            zoneEvents.add(createBattlefieldEntryEvent(
                    produced.prototype(), produced.amount(), cause));
        }
        if (tokenEvents.isEmpty()) {
            return List.of();
        }
        return List.of(
                new EffectProduction(source, EffectType.TOKEN_CREATED,
                        tokenEvents, expectedBatches),
                new EffectProduction(source, EffectType.ZONE_CHANGED,
                        zoneEvents, expectedBatches));
    }

    record ProducedToken(Card prototype, int amount) {
        ProducedToken {
            if (prototype == null || amount <= 0) {
                throw new IllegalArgumentException("A produced token needs a positive amount");
            }
        }
    }
}
