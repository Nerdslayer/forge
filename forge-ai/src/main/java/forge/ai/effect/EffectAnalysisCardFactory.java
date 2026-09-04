package forge.ai.effect;

import forge.game.card.Card;
import forge.game.card.CardCopyService;

/** Creates analysis-only card objects when engine collections require distinct identities. */
final class EffectAnalysisCardFactory {
    // TODO(effect analysis): Provide isolated synthetic identities that do not consume IDs from the
    // live game. Existing token prototype construction already uses Game.nextCardId(), so this
    // helper centralizes that behavior until Forge supports non-game analysis identities.

    private EffectAnalysisCardFactory() {
    }

    static Card copyWithDistinctIdentity(final Card prototype) {
        return new CardCopyService(prototype).copyCard(true);
    }
}
