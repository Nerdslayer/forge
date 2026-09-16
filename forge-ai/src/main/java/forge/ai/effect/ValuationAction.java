package forge.ai.effect;

import forge.game.card.Card;

/** A concrete proposed action whose value can be composed from shared card components. */
public interface ValuationAction {
    /** The card or permanent whose state is changed by this action. */
    Card subject();
}
