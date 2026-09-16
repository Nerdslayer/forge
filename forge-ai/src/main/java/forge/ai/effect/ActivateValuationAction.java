package forge.ai.effect;

import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

/** A concrete activation of an ability on a permanent controlled by the evaluating AI. */
public record ActivateValuationAction(Card source, SpellAbility ability)
        implements ValuationAction {
    public ActivateValuationAction {
        if (source == null || ability == null) {
            throw new IllegalArgumentException("An activation source and ability are required");
        }
    }

    @Override
    public Card subject() {
        return source;
    }
}
