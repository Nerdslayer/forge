package forge.ai.effect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * Combines supported relationship values contributed by triggered and static effects.
 */
public final class EffectRelationshipEvaluator {
    // TODO(effect analysis): Add explicit consumers for casting, activation, protection, combat,
    // and other decisions. Relationship values are currently integrated only into removal choice.
    private EffectRelationshipEvaluator() {
    }

    /**
     * Returns signed relationship adjustments for removal candidates from {@code evaluatingAi}'s
     * perspective. Positive values make a source a better removal target; negative values make it
     * a worse target.
     */
    public static Map<Card, Integer> evaluateRemovalRelationships(final Player evaluatingAi,
            final Iterable<Card> candidates) {
        return evaluateRemovalRelationships(evaluatingAi, candidates, null,
                EffectAnalysisTrace.disabled());
    }

    /** Evaluates removal relationships including the target event from the proposed action. */
    public static Map<Card, Integer> evaluateRemovalRelationships(final Player evaluatingAi,
            final Iterable<Card> candidates, final SpellAbility removalAbility) {
        return evaluateRemovalRelationships(evaluatingAi, candidates, removalAbility,
                EffectAnalysisTrace.disabled());
    }

    /** Evaluates relationships while optionally collecting a grouped diagnostic trace. */
    public static Map<Card, Integer> evaluateRemovalRelationships(final Player evaluatingAi,
            final Iterable<Card> candidates, final EffectAnalysisTrace trace) {
        return evaluateRemovalRelationships(evaluatingAi, candidates, null, trace);
    }

    /** Evaluates removal relationships while optionally modeling target selection. */
    public static Map<Card, Integer> evaluateRemovalRelationships(final Player evaluatingAi,
            final Iterable<Card> candidates, final SpellAbility removalAbility,
            final EffectAnalysisTrace trace) {
        final Map<Card, List<AbilityValueContribution>> contributions =
                evaluateRemovalContributions(evaluatingAi, candidates, removalAbility, trace);
        final Map<Card, Integer> values = new HashMap<>();
        for (final Map.Entry<Card, List<AbilityValueContribution>> entry : contributions.entrySet()) {
            int value = 0;
            for (final AbilityValueContribution contribution : entry.getValue()) {
                if (contribution.counted()) {
                    value = EffectMath.add(value, contribution.value());
                }
            }
            if (value != 0) {
                values.put(entry.getKey(), value);
            }
        }
        return values;
    }

    /** Returns the explainable relationship entries before they are summed per candidate. */
    static Map<Card, List<AbilityValueContribution>> evaluateRemovalContributions(
            final Player evaluatingAi, final Iterable<Card> candidates,
            final EffectAnalysisTrace trace) {
        return evaluateRemovalContributions(evaluatingAi, candidates, null, trace);
    }

    static Map<Card, List<AbilityValueContribution>> evaluateRemovalContributions(
            final Player evaluatingAi, final Iterable<Card> candidates,
            final SpellAbility removalAbility, final EffectAnalysisTrace trace) {
        if (evaluatingAi == null || candidates == null) {
            return Collections.emptyMap();
        }
        final List<Card> candidateList = new ArrayList<>();
        candidates.forEach(candidateList::add);

        final Map<Card, List<AbilityValueContribution>> values = new HashMap<>();
        mergeContributions(values, TriggeredEffectAnalyzer.evaluateContributions(
                evaluatingAi, candidateList, removalAbility, trace));
        mergeContributions(values, StaticAbilityAnalyzer.evaluateContributions(
                evaluatingAi, candidateList, trace));
        return values;
    }

    private static void mergeContributions(
            final Map<Card, List<AbilityValueContribution>> destination,
            final Map<Card, List<AbilityValueContribution>> source) {
        for (final Map.Entry<Card, List<AbilityValueContribution>> entry : source.entrySet()) {
            destination.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                    .addAll(entry.getValue());
        }
    }
}
