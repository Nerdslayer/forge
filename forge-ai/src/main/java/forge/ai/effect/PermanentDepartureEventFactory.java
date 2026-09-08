package forge.ai.effect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Builds normalized sacrifice and battlefield-departure events from known permanents. */
final class PermanentDepartureEventFactory {
    private PermanentDepartureEventFactory() {
    }

    static List<EffectProduction> createSacrificeProductions(final Card source,
            final SpellAbility cause, final Map<Player, List<Card>> sacrificedByPlayer,
            final int expectedBatches) {
        final List<EffectProduction> productions = new ArrayList<>();
        for (final Map.Entry<Player, List<Card>> entry : sacrificedByPlayer.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            final List<EffectEvent> sacrificeEvents = new ArrayList<>();
            final List<EffectEvent> zoneEvents = new ArrayList<>();
            for (final Card sacrificed : entry.getValue()) {
                final Map<AbilityKey, Object> sacrificeParameters =
                        new EnumMap<>(AbilityKey.class);
                sacrificeParameters.put(AbilityKey.Card, sacrificed);
                sacrificeParameters.put(AbilityKey.Player, entry.getKey());
                sacrificeParameters.put(AbilityKey.Cause, cause);
                sacrificeEvents.add(new EffectEvent(EffectType.SACRIFICED, entry.getKey(),
                        List.of(new EffectEvent.Subject(sacrificed, 1)), sacrificeParameters));

                final Map<AbilityKey, Object> zoneParameters = new EnumMap<>(AbilityKey.class);
                zoneParameters.put(AbilityKey.Card, sacrificed);
                zoneParameters.put(AbilityKey.CardLKI, sacrificed);
                zoneParameters.put(AbilityKey.Cause, cause);
                zoneParameters.put(AbilityKey.Origin, ZoneType.Battlefield.name());
                zoneParameters.put(AbilityKey.Destination, ZoneType.Graveyard.name());
                zoneEvents.add(new EffectEvent(EffectType.ZONE_CHANGED, entry.getKey(),
                        List.of(new EffectEvent.Subject(sacrificed, 1)), zoneParameters));
            }
            productions.add(new EffectProduction(source, EffectType.SACRIFICED,
                    sacrificeEvents, expectedBatches));
            productions.add(new EffectProduction(source, EffectType.ZONE_CHANGED,
                    zoneEvents, expectedBatches));
        }
        return productions;
    }

    static List<EffectProduction> createBattlefieldToGraveyardProduction(
            final Card source, final SpellAbility cause, final Collection<Card> departed,
            final int expectedBatches) {
        return createZoneChangeProduction(source, cause, departed,
                ZoneType.Battlefield, ZoneType.Graveyard, expectedBatches);
    }

    static List<EffectProduction> createZoneChangeProduction(
            final Card source, final SpellAbility cause, final Collection<Card> movedCards,
            final ZoneType origin, final ZoneType destination, final int expectedBatches) {
        if (movedCards.isEmpty()) {
            return List.of();
        }

        final List<EffectEvent> zoneEvents = new ArrayList<>();
        for (final Card card : movedCards) {
            final Map<AbilityKey, Object> zoneParameters = new EnumMap<>(AbilityKey.class);
            zoneParameters.put(AbilityKey.Card, card);
            zoneParameters.put(AbilityKey.CardLKI, card);
            zoneParameters.put(AbilityKey.Cause, cause);
            zoneParameters.put(AbilityKey.Origin, origin.name());
            zoneParameters.put(AbilityKey.Destination, destination.name());
            zoneEvents.add(new EffectEvent(EffectType.ZONE_CHANGED, card.getController(),
                    List.of(new EffectEvent.Subject(card, 1)), zoneParameters));
        }
        return List.of(new EffectProduction(source, EffectType.ZONE_CHANGED,
                zoneEvents, expectedBatches));
    }
}
