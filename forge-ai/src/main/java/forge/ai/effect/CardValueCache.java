package forge.ai.effect;

import java.util.IdentityHashMap;
import java.util.Map;

import forge.game.card.Card;

/**
 * Caches card values for one decision and one immutable valuation context.
 *
 * <p>The cache is intentionally short-lived. A card's live state, its hand, or the game state
 * used by a situational evaluator may change after a decision, so callers must create a new cache
 * for the next decision or after mutating the hypothetical state.</p>
 */
public final class CardValueCache {
    private final ValuationContext context;
    private final Map<Card, CardValueBreakdown> values = new IdentityHashMap<>();

    public CardValueCache(final ValuationContext context) {
        if (context == null) {
            throw new IllegalArgumentException("A valuation context is required");
        }
        this.context = context;
    }

    /** Returns the cached value, evaluating the card at most once for this decision. */
    public CardValueBreakdown evaluate(final Card card) {
        if (card == null) {
            return CardValueBreakdown.unavailable("A card is required for cached valuation.");
        }
        return values.computeIfAbsent(card,
                candidate -> UnifiedCardValueEvaluator.evaluateCard(candidate, context));
    }

    /** Number of distinct card identities evaluated by this cache. */
    public int size() {
        return values.size();
    }
}
