package forge.ai.effect;

import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** Creates analysis-only card objects when engine collections require distinct identities. */
final class EffectAnalysisCardFactory {
    // TODO(effect analysis): Extend local preparation placeholders to distinct copied and token
    // prototypes. The legacy copy/unknown-card methods still consume Game.nextCardId(); combat
    // preparation must not use those methods until it has a local identity namespace.

    private EffectAnalysisCardFactory() {
    }

    static Card copyWithDistinctIdentity(final Card prototype) {
        return new CardCopyService(prototype).copyCard(true);
    }

    /** Creates a characteristic-free card for events whose hidden card identity is unknown. */
    static Card createUnknownCard(final Player owner, final ZoneType zone) {
        return createUnknownCard(owner, zone, owner.getGame().nextCardId());
    }

    /** Local placeholder, never inserted into a game collection or used as a target identity. */
    static Card createUnknownCardForPreparation(final Player owner, final ZoneType zone) {
        return createUnknownCard(owner, zone, Integer.MIN_VALUE + owner.getId());
    }

    private static Card createUnknownCard(final Player owner, final ZoneType zone, final int id) {
        final Card card = new Card(id, owner.getGame());
        card.setOwner(owner);
        card.setLastKnownZone(owner.getZone(zone));
        return card;
    }
}
