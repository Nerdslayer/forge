package forge.ai.effect;

import java.util.Map;

/** Scalar substitution shared by incoming and outgoing damage event adapters. */
record IntrinsicDamageAmountBinding(int amount) {
    private static final String EXPRESSION = "TriggerCount$DamageAmount";

    static boolean quantity(final String expression) {
        return new IntrinsicEventScalarBinding(EXPRESSION, 0).matches(expression);
    }

    static boolean needed(final AbilityOutcomeDescription node) {
        return new IntrinsicEventScalarBinding(EXPRESSION, 0).needed(node);
    }

    Map<String, String> bindVariables(final Map<String, String> variables) {
        return new IntrinsicEventScalarBinding(EXPRESSION, amount).bindVariables(variables);
    }

    AbilityOutcomeDescription bindOutcome(final AbilityOutcomeDescription node) {
        return new IntrinsicEventScalarBinding(EXPRESSION, amount).bindOutcome(node);
    }
}
