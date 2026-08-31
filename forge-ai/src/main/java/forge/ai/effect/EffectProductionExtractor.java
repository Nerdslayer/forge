package forge.ai.effect;

import java.util.List;

import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

/** Extracts normalized production families from parsed Forge abilities. */
interface EffectProductionExtractor {
    List<EffectProduction> extract(Card source, Trigger trigger);

    List<EffectProduction> extract(Card source, SpellAbility ability);
}
