package forge.ai.effect;

import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

record EffectConsequence(Card source, EffectType observedType, Trigger trigger, SpellAbility outcome,
        int counterAmount) {
}
