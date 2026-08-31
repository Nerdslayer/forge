package forge.ai.effect;

import forge.game.card.Card;
import forge.game.trigger.Trigger;

/** Extracts one normalized triggered-consequence family. */
interface EffectConsequenceExtractor {
    EffectConsequence extract(Card source, Trigger trigger);
}
