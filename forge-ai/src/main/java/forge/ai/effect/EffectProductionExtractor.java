package forge.ai.effect;

import java.util.List;

import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

/** Extracts normalized production families from current card state and parsed Forge abilities. */
interface EffectProductionExtractor {
    default List<EffectProduction> extract(final Card source) {
        return List.of();
    }

    List<EffectProduction> extract(Card source, Trigger trigger);

    List<EffectProduction> extract(Card source, SpellAbility ability);
}
