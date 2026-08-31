package forge.ai.effect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;

/** Normalized public event data that can be tested against an existing Forge trigger. */
record EffectEvent(EffectType type, Player player, List<Subject> subjects,
        Map<AbilityKey, Object> triggerParameters) {
    EffectEvent {
        if (type == null || player == null) {
            throw new IllegalArgumentException("An effect event needs a type and player");
        }
        subjects = List.copyOf(subjects);
        final Map<AbilityKey, Object> copiedParameters = new EnumMap<>(AbilityKey.class);
        copiedParameters.putAll(triggerParameters);
        triggerParameters = Collections.unmodifiableMap(copiedParameters);
    }

    List<Card> expandedCardSubjects() {
        final List<Card> cards = new ArrayList<>();
        for (final Subject subject : subjects) {
            if (subject.value() instanceof Card card) {
                for (int i = 0; i < subject.occurrences(); i++) {
                    cards.add(card);
                }
            }
        }
        return cards;
    }

    /** One normalized event subject and the number of equivalent occurrences it represents. */
    record Subject(Object value, int occurrences) {
        Subject {
            if (value == null || occurrences <= 0) {
                throw new IllegalArgumentException("An effect-event subject needs a positive occurrence count");
            }
        }
    }
}
