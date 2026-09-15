package forge.ai.effect;

/** Describes how much of a player's hand is available to a valuation. */
public enum HandKnowledge {
    /** The evaluator may inspect every card in the hand. */
    FULL_HAND,
    /** Only the candidate card is known; the rest of the hand remains hidden. */
    KNOWN_CARD_ONLY
}
