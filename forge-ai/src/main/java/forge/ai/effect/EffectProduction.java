package forge.ai.effect;

import forge.game.card.Card;
import forge.game.player.Player;

import java.util.List;

record EffectProduction(Card source, EffectType type, List<ProducedEvent> events, Player eventPlayer,
        int expectedBatches) {
    record ProducedEvent(Card subject, int occurrences) {
    }
}
