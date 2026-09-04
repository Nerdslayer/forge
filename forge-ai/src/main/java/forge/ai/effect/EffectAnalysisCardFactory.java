package forge.ai.effect;

import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

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

    /** Creates a characteristic-free card for events whose hidden card identity is unknown. */
    static Card createUnknownCard(final Player owner, final ZoneType zone) {
        final Card card = new Card(owner.getGame().nextCardId(), owner.getGame());
        card.setOwner(owner);
        card.setLastKnownZone(owner.getZone(zone));
        return card;
    }
}
