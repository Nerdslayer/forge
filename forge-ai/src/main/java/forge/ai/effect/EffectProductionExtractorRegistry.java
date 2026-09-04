package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

/** Shared routing for supported production extractors. */
final class EffectProductionExtractorRegistry {
    private static final List<EffectProductionExtractor> EXTRACTORS = List.of(
            TokenProductionExtractor.INSTANCE,
            CopiedTokenProductionExtractor.INSTANCE,
            CounterProductionExtractor.INSTANCE,
            LifeGainProductionExtractor.INSTANCE,
            CardDrawProductionExtractor.INSTANCE,
            DamageProductionExtractor.INSTANCE,
            AttackProductionExtractor.INSTANCE);

    private EffectProductionExtractorRegistry() {
    }

    static List<EffectProduction> extract(final Player evaluatingAi, final Card source) {
        final List<EffectProduction> productions = new ArrayList<>();
        for (final EffectProductionExtractor extractor : EXTRACTORS) {
            productions.addAll(extractor.extract(evaluatingAi, source));
        }
        return productions;
    }

    static List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final Trigger trigger) {
        final List<EffectProduction> productions = new ArrayList<>();
        for (final EffectProductionExtractor extractor : EXTRACTORS) {
            productions.addAll(extractor.extract(evaluatingAi, source, trigger));
        }
        return productions;
    }

    static List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final SpellAbility ability) {
        final List<EffectProduction> productions = new ArrayList<>();
        for (final EffectProductionExtractor extractor : EXTRACTORS) {
            productions.addAll(extractor.extract(evaluatingAi, source, ability));
        }
        return productions;
    }
}
