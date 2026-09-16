package forge.ai.effect;

import java.util.List;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Information available when valuing a card as a resource in hand.
 *
 * <p>The knowledge mode is deliberately explicit. An AI may use a complete hand when choosing
 * one of its own cards, but it must not inspect an opponent's hidden hand merely because a known
 * card is being evaluated. The total hand size is still public information in the latter case.</p>
 */
public record HandValuationContext(Player evaluatingAi, Player handOwner, int totalHandSize,
        List<Card> knownCards, HandKnowledge knowledge) {
    public HandValuationContext {
        if (handOwner == null) {
            throw new IllegalArgumentException("A hand owner is required");
        }
        if (totalHandSize < 0) {
            throw new IllegalArgumentException("Hand size cannot be negative");
        }
        if (knowledge == null) {
            throw new IllegalArgumentException("Hand knowledge is required");
        }
        knownCards = knownCards == null ? List.of() : List.copyOf(knownCards);
        if (knownCards.size() > totalHandSize) {
            throw new IllegalArgumentException("Known cards cannot exceed the total hand size");
        }
        if (knowledge == HandKnowledge.FULL_HAND && knownCards.size() != totalHandSize) {
            throw new IllegalArgumentException("A full-hand context must contain every hand card");
        }
    }

    /** Creates a context for evaluating one of the AI's own cards with the full hand visible. */
    public static HandValuationContext fullHand(final Player evaluatingAi, final Player handOwner) {
        final List<Card> hand = List.copyOf(handOwner.getCardsIn(ZoneType.Hand));
        return new HandValuationContext(evaluatingAi, handOwner, hand.size(), hand,
                HandKnowledge.FULL_HAND);
    }

    /**
     * Creates the appropriate known-card context for a live hand decision. Complete-information
     * decisions may inspect the whole hand; otherwise only the candidate card and public hand size
     * are exposed.
     */
    public static HandValuationContext forKnownCard(final Player evaluatingAi,
            final Player handOwner, final Card knownCard, final boolean completeInformation) {
        if (completeInformation) {
            return fullHand(evaluatingAi, handOwner);
        }
        return knownCardOnly(evaluatingAi, handOwner, knownCard,
                handOwner.getCardsIn(ZoneType.Hand).size());
    }

    /**
     * Creates a context for a public card that would be added to a hand whose remaining cards are
     * hidden. This is the context used when estimating the cost of bouncing an opponent's card.
     */
    public static HandValuationContext knownCardOnly(final Player evaluatingAi,
            final Player handOwner, final Card knownCard, final int totalHandSize) {
        return new HandValuationContext(evaluatingAi, handOwner, totalHandSize,
                knownCard == null ? List.of() : List.of(knownCard), HandKnowledge.KNOWN_CARD_ONLY);
    }

    /** Number of cards in the hand that are not individually known to the evaluator. */
    public int unknownCardCount() {
        return totalHandSize - knownCards.size();
    }

    public boolean knows(final Card card) {
        return card != null && knownCards.contains(card);
    }
}
