package forge.ai.effect;

import java.util.List;
import java.util.Objects;

/**
 * A selected plan, or an explicitly incomplete evaluation. Random branches retain their plans.
 * {@code supported} preserves the legacy meaning that an evaluated subtotal exists; callers that
 * require a complete expected value must inspect {@link #completeness()}.
 */
public record OutcomePlan<S>(double value, S state, List<Decision> decisions,
        List<OutcomePlan<S>> branches, boolean supported, String reason,
        Completeness completeness, double unresolvedProbability, List<String> unresolvedAlternatives) {
    public OutcomePlan {
        decisions = List.copyOf(decisions);
        branches = List.copyOf(branches);
        reason = Objects.requireNonNull(reason);
        completeness = Objects.requireNonNull(completeness);
        unresolvedAlternatives = List.copyOf(unresolvedAlternatives);
        if (!Double.isFinite(value)) { throw new IllegalArgumentException("Finite outcome value required"); }
        if (!Double.isFinite(unresolvedProbability)
                || unresolvedProbability < 0 || unresolvedProbability > 1) {
            throw new IllegalArgumentException("Unresolved probability must be between zero and one");
        }
        if (completeness != Completeness.PARTIAL && unresolvedProbability != 0) {
            throw new IllegalArgumentException("Only partial plans may have unresolved probability");
        }
        if (completeness == Completeness.COMPLETE && !unresolvedAlternatives.isEmpty()) {
            throw new IllegalArgumentException("Complete plans cannot have unresolved alternatives");
        }
    }

    /** Compatibility constructor for callers that only need the original supported/reason fields. */
    public OutcomePlan(final double value, final S state, final List<Decision> decisions,
            final List<OutcomePlan<S>> branches, final boolean supported, final String reason) {
        this(value, state, decisions, branches, supported, reason,
                inferCompleteness(supported, reason), 0, List.of());
    }

    public enum Completeness { COMPLETE, PARTIAL, UNAVAILABLE, UNSUPPORTED }

    public enum DecisionKind { TARGET, CHOICE, EFFECT, RANDOM }

    public record Decision(DecisionKind kind, String id, List<?> selections) {
        public Decision { selections = List.copyOf(selections); }
    }

    static <S> OutcomePlan<S> complete(final double value, final S state) {
        return new OutcomePlan<>(value, state, List.of(), List.of(), true, "",
                Completeness.COMPLETE, 0, List.of());
    }

    static <S> OutcomePlan<S> unsupported(final S state, final String reason) {
        final Completeness completeness = isUnavailableReason(reason)
                ? Completeness.UNAVAILABLE : Completeness.UNSUPPORTED;
        return new OutcomePlan<>(0, state, List.of(), List.of(), false, reason,
                completeness, 0, List.of());
    }

    static <S> OutcomePlan<S> partial(final double value, final S state,
            final List<Decision> decisions, final List<OutcomePlan<S>> branches,
            final String reason, final double unresolvedProbability,
            final List<String> unresolvedAlternatives) {
        return partial(value, state, decisions, branches, reason, unresolvedProbability,
                unresolvedAlternatives, false);
    }

    static <S> OutcomePlan<S> partial(final double value, final S state,
            final List<Decision> decisions, final List<OutcomePlan<S>> branches,
            final String reason, final double unresolvedProbability,
            final List<String> unresolvedAlternatives, final boolean supported) {
        return new OutcomePlan<>(value, state, decisions, branches, supported, reason,
                Completeness.PARTIAL, unresolvedProbability, unresolvedAlternatives);
    }

    boolean unavailable() {
        return completeness == Completeness.UNAVAILABLE;
    }

    boolean complete() {
        return completeness == Completeness.COMPLETE;
    }

    boolean partial() {
        return completeness == Completeness.PARTIAL;
    }

    private static Completeness inferCompleteness(final boolean supported, final String reason) {
        if (supported) { return Completeness.COMPLETE; }
        return isUnavailableReason(reason) ? Completeness.UNAVAILABLE : Completeness.UNSUPPORTED;
    }

    private static boolean isUnavailableReason(final String reason) {
        return "No legal target".equals(reason) || "No legal choice".equals(reason);
    }
}
