package forge.ai.effect;

import java.util.List;

import forge.game.card.Card;
import forge.game.trigger.Trigger;

/** Shared routing for supported consequence extractors. */
final class EffectConsequenceExtractorRegistry {
    private static final List<EffectConsequenceExtractor> EXTRACTORS = List.of(
            TriggeredConsequenceExtractor.INSTANCE);

    private EffectConsequenceExtractorRegistry() {
    }

    static EffectConsequence extract(final Card source, final Trigger trigger) {
        for (final EffectConsequenceExtractor extractor : EXTRACTORS) {
            final EffectConsequence consequence = extractor.extract(source, trigger);
            if (consequence != null) {
                return consequence;
            }
        }
        return null;
    }
}
