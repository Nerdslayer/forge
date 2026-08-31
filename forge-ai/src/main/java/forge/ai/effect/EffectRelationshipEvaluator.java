package forge.ai.effect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import forge.game.card.Card;
import forge.game.player.Player;

/**
 * Combines supported relationship values contributed by triggered and static effects.
 */
public final class EffectRelationshipEvaluator {
    private EffectRelationshipEvaluator() {
    }

    /**
     * Returns signed relationship adjustments for removal candidates from {@code evaluatingAi}'s
     * perspective. Positive values make a source a better removal target; negative values make it
     * a worse target.
     */
    public static Map<Card, Integer> evaluateRemovalRelationships(final Player evaluatingAi,
            final Iterable<Card> candidates) {
        if (evaluatingAi == null || candidates == null) {
            return Collections.emptyMap();
        }
        final List<Card> candidateList = new ArrayList<>();
        candidates.forEach(candidateList::add);

        final Map<Card, Integer> values = new HashMap<>();
        merge(values, TriggeredEffectAnalyzer.evaluateRelationships(evaluatingAi, candidateList));
        merge(values, StaticAbilityAnalyzer.evaluateRelationships(evaluatingAi, candidateList));
        return values;
    }

    private static void merge(final Map<Card, Integer> destination, final Map<Card, Integer> source) {
        for (final Map.Entry<Card, Integer> entry : source.entrySet()) {
            final int combined = EffectMath.add(
                    destination.getOrDefault(entry.getKey(), 0), entry.getValue());
            if (combined == 0) {
                destination.remove(entry.getKey());
            } else {
                destination.put(entry.getKey(), combined);
            }
        }
    }
}
