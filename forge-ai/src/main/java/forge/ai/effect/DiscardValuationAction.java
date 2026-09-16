package forge.ai.effect;

import forge.game.card.Card;
import forge.game.player.Player;

/** A proposed discard of one known card from a player's hand. */
public record DiscardValuationAction(Card card, Player discarder) implements ValuationAction {
    public DiscardValuationAction {
        if (card == null || discarder == null) {
            throw new IllegalArgumentException("A discarded card and discarding player are required");
        }
    }

    @Override
    public Card subject() {
        return card;
    }
}
