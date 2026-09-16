package forge.ai.effect;

import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

/** A spell-casting action whose host card is currently available in hand. */
public record CastValuationAction(SpellAbility spellAbility) implements ValuationAction {
    public CastValuationAction {
        if (spellAbility == null || spellAbility.getHostCard() == null) {
            throw new IllegalArgumentException("A spell ability with a host card is required");
        }
    }

    @Override
    public Card subject() {
        return spellAbility.getHostCard();
    }
}
