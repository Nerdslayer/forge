package forge.ai.effect;

import java.util.List;

/** Shared decision boundaries; declining never applies the perform branch's state changes. */
public final class OutcomeChoices {
    private OutcomeChoices() { }

    public static <S> Outcome<S> optional(final String id, final Outcome<S> perform,
            final boolean maximize) {
        final Outcome<S> decline = new Outcome.Atomic<>("Decline",
                state -> new Outcome.Transition<>(0, state));
        // Decline is first so exact ties preserve the original state.
        return new Outcome.Choice<>(id, List.of(decline, perform), 1, 1, false, maximize);
    }
}
