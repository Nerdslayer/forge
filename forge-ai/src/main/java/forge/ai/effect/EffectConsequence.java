package forge.ai.effect;

import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

record EffectConsequence(Card source, EffectType observedType, Trigger trigger, SpellAbility outcome,
        OutcomeEvaluator outcomeEvaluator, AbilityIdentity ability) {
    EffectConsequence(final Card source, final EffectType observedType, final Trigger trigger,
            final SpellAbility outcome, final OutcomeEvaluator outcomeEvaluator) {
        this(source, observedType, trigger, outcome, outcomeEvaluator,
                AbilityIdentity.forTrigger(source, trigger));
    }
}
