package forge.ai.effect;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Composable, side-effect-free descriptions. State transitions must return isolated states. */
public sealed interface Outcome<S> {
    /** Explicitly unmodeled semantics, including a stable diagnostic reason. */
    record Unresolved<S>(String reason) implements Outcome<S> { }

    record Transition<S>(double value, S state, Object resolvedEffect) {
        public Transition(final double value, final S state) { this(value, state, null); }
    }

    record Atomic<S>(String description, Function<S, Transition<S>> evaluate) implements Outcome<S> {
        public Atomic(final Function<S, Transition<S>> evaluate) { this("", evaluate); }
    }

    record Sequence<S>(List<Outcome<S>> children) implements Outcome<S> {
        public Sequence { children = List.copyOf(children); }
    }

    /**
     * One simultaneous event. All preparers read the same pre-event state and return immutable
     * descriptions, not updated states. The committer combines them into one isolated transition.
     * Choices belong outside this node, so no event is applied until every choice is bound.
     * A null preparation or transition means unsupported. Combining overlapping changes is the
     * committer's responsibility: independently evaluated states/scores cannot safely be summed.
     */
    record Batch<S, D>(String description, List<Function<S, D>> preparations,
            BiFunction<S, List<D>, Transition<S>> commit) implements Outcome<S> {
        public Batch { preparations = List.copyOf(preparations); }
    }

    /** A resolution-time choice whose legal options depend on the current projected state. */
    record Deferred<S>(Function<S, Outcome<S>> build) implements Outcome<S> { }

    /** Options resolve in list order. Repeated modes are adjacent in that same order. */
    record Choice<S>(String id, List<Outcome<S>> options, int minimum, int maximum,
            boolean repeat, boolean maximize) implements Outcome<S> {
        public Choice {
            options = List.copyOf(options);
            if (minimum < 0 || maximum < minimum) {
                throw new IllegalArgumentException("Invalid selection count");
            }
        }
    }

    record Weighted<S>(Outcome<S> outcome, double weight) {
        public Weighted {
            if (!Double.isFinite(weight) || weight <= 0) {
                throw new IllegalArgumentException("Weights must be finite and positive");
            }
        }
    }

    record Random<S>(String id, List<Weighted<S>> options) implements Outcome<S> {
        public Random {
            options = List.copyOf(options);
            if (options.isEmpty()) { throw new IllegalArgumentException("Empty random outcome"); }
        }
    }

    /** Binding is separate from effects: a child may read the same binding any number of times. */
    record Target<S, T>(String id, Function<S, List<T>> candidates,
            BiFunction<S, T, S> bind, Outcome<S> child, boolean maximize) implements Outcome<S> { }
}
