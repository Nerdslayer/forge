package forge.ai.effect;

import java.util.EnumMap;
import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Conservative checks for whether a known card will reach an intended zone. */
final class ZoneChangePrediction {
    // TODO(effect analysis): Predict updated, prevented, redirected, commander, and optional
    // replacement results instead of failing closed whenever an applicable Moved replacement exists.
    private ZoneChangePrediction() {
    }

    static boolean willMove(final Card card, final ZoneType origin,
            final ZoneType destination, final SpellAbility cause) {
        if (card == null || card.isPhasedOut() || !card.isInZone(origin)) {
            return false;
        }
        if (destination == ZoneType.Exile && !card.canExiledBy(cause, true)) {
            return false;
        }

        final Map<AbilityKey, Object> replacementParameters =
                new EnumMap<>(AbilityKey.class);
        replacementParameters.putAll(AbilityKey.mapFromAffected(card));
        replacementParameters.put(AbilityKey.CardLKI, card);
        replacementParameters.put(AbilityKey.Cause, cause);
        replacementParameters.put(AbilityKey.Origin, origin);
        replacementParameters.put(AbilityKey.Destination, destination);
        try {
            return card.getGame().getReplacementHandler().getReplacementList(
                    ReplacementType.Moved, replacementParameters, null).isEmpty();
        } catch (final RuntimeException ignored) {
            return false;
        }
    }
}
