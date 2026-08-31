package forge.ai.effect;

import java.util.List;

import forge.game.card.Card;

/** Events a source is expected to produce during the analysis horizon. */
record EffectProduction(Card source, EffectType type, List<EffectEvent> events, int expectedBatches) {
    EffectProduction {
        events = List.copyOf(events);
        if (source == null || type == null || events.isEmpty() || expectedBatches <= 0
                || events.stream().anyMatch(event -> event.type() != type)) {
            throw new IllegalArgumentException("Invalid effect production");
        }
    }
}
