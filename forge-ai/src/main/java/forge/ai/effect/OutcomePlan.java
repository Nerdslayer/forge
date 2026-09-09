package forge.ai.effect;

import java.util.List;

/** A selected plan, or an explicitly incomplete evaluation. Random branches retain their plans. */
public record OutcomePlan<S>(double value, S state, List<Decision> decisions,
        List<OutcomePlan<S>> branches, boolean supported, String reason) {
    public OutcomePlan {
        decisions = List.copyOf(decisions);
        branches = List.copyOf(branches);
    }

    public enum DecisionKind { TARGET, CHOICE, EFFECT, RANDOM }

    public record Decision(DecisionKind kind, String id, List<?> selections) {
        public Decision { selections = List.copyOf(selections); }
    }

    static <S> OutcomePlan<S> complete(final double value, final S state) {
        return new OutcomePlan<>(value, state, List.of(), List.of(), true, "");
    }

    static <S> OutcomePlan<S> unsupported(final S state, final String reason) {
        return new OutcomePlan<>(0, state, List.of(), List.of(), false, reason);
    }

    boolean unavailable() {
        return !supported && ("No legal target".equals(reason) || "No legal choice".equals(reason));
    }
}
