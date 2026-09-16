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
            CounterRemovalProductionExtractor.INSTANCE,
            CounterMoveProductionExtractor.INSTANCE,
            LifeGainProductionExtractor.INSTANCE,
            LifeLossProductionExtractor.INSTANCE,
            CardDrawProductionExtractor.INSTANCE,
            CardDiscardProductionExtractor.INSTANCE,
            DamageProductionExtractor.INSTANCE,
            ControlChangeProductionExtractor.INSTANCE,
            AttackProductionExtractor.INSTANCE,
            DestroyProductionExtractor.INSTANCE,
            ZoneChangeProductionExtractor.INSTANCE,
            SacrificeProductionExtractor.INSTANCE);

    private EffectProductionExtractorRegistry() {
    }

    static List<EffectProduction> extract(final Player evaluatingAi, final Card source) {
        final List<EffectProduction> productions = new ArrayList<>();
        for (final EffectProductionExtractor extractor : EXTRACTORS) {
            productions.addAll(extractor.extract(evaluatingAi, source));
        }
        return withDerivedProductions(productions, AbilityIdentity.synthetic(source, "card"));
    }

    static List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final Trigger trigger) {
        final List<EffectProduction> productions = new ArrayList<>();
        for (final EffectProductionExtractor extractor : EXTRACTORS) {
            productions.addAll(extractor.extract(evaluatingAi, source, trigger));
        }
        return withDerivedProductions(productions, AbilityIdentity.forTrigger(source, trigger));
    }

    static List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final SpellAbility ability) {
        final List<EffectProduction> productions = new ArrayList<>();
        for (final EffectProductionExtractor extractor : EXTRACTORS) {
            productions.addAll(extractor.extract(evaluatingAi, source, ability));
        }
        return withDerivedProductions(productions, AbilityIdentity.forSpellAbility(source, ability));
    }

    private static List<EffectProduction> withDerivedProductions(
            final List<EffectProduction> productions, final AbilityIdentity ability) {
        final List<EffectProduction> derived = new ArrayList<>();
        for (int i = 0; i < productions.size(); i++) {
            final EffectProduction raw = productions.get(i);
            // The registry knows the extraction origin. Do not recover it from event Cause:
            // copied trigger outcomes and derived events frequently carry no reliable cause.
            final EffectProduction production = new EffectProduction(raw.source(), raw.type(),
                    raw.events(), raw.expectedBatches(), ability);
            productions.set(i, production);
            derived.addAll(DamageLifeLossProductionDeriver.derive(production));
            final List<EffectProduction> combatDamage =
                    CombatDamageProductionDeriver.derive(production);
            derived.addAll(combatDamage);
            for (final EffectProduction damageProduction : combatDamage) {
                derived.addAll(DamageLifeLossProductionDeriver.derive(damageProduction));
            }
        }
        productions.addAll(derived);
        return productions;
    }
}
