package forge.ai.effect;

import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

/** Supplies environment-specific inputs to the shared ability-occurrence estimator. */
interface AbilityOccurrenceContext {
    AbilityOccurrenceRequest triggerRequest(Card source, Trigger trigger);

    ActivationOccurrenceRequest activationRequest(Card source, SpellAbility ability);
}
