package forge.ai.effect;

import forge.game.card.Card;
import forge.game.player.Player;

record EffectProduction(Card source, EffectType type, Card eventSubject, Player eventPlayer,
        int expectedOccurrences) {
}
