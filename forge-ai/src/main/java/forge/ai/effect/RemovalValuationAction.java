package forge.ai.effect;

import forge.game.card.Card;

/** A single-permanent removal action for the shared action valuation entry point. */
public record RemovalValuationAction(Card target, RemovalActionKind actionKind)
        implements ValuationAction {
    public RemovalValuationAction {
        if (target == null || actionKind == null) {
            throw new IllegalArgumentException("A removal target and action kind are required");
        }
    }

    @Override
    public Card subject() {
        return target;
    }
}
