package forge.ai.effect;

/** Decision that determines which parts of a card's value are relevant. */
public enum ValuationDecision {
    GENERAL_CARD,
    HAND_SELECTION,
    CAST,
    ACTIVATE,
    ATTACK,
    BLOCK,
    REMOVAL_TARGET
}
