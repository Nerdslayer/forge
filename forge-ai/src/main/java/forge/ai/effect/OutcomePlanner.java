package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import forge.ai.effect.OutcomePlan.DecisionKind;

/** Bounded exhaustive planning; continuation evaluation keeps choices and later effects coupled. */
public final class OutcomePlanner<S> {
    private final int budget;
    private int remaining;
    private int depth;

    public OutcomePlanner() { this(4096); }

    public OutcomePlanner(final int budget) {
        if (budget <= 0) { throw new IllegalArgumentException("Positive budget required"); }
        this.budget = budget;
    }

    public OutcomePlan<S> evaluate(final Outcome<S> outcome, final S state) {
        remaining = budget;
        depth = 0;
        try {
            return solve(outcome, state, s -> OutcomePlan.complete(0, s));
        } catch (final SearchLimit exceeded) {
            // Do not present a biased prefix of the alternatives as the best/expected result.
            return OutcomePlan.unsupported(state, "Outcome search budget exceeded");
        }
    }

    private OutcomePlan<S> solve(final Outcome<S> outcome, final S state,
            final Function<S, OutcomePlan<S>> next) {
        if (++depth > 128) { throw new SearchLimit(); }
        try {
            return solveNode(outcome, state, next);
        } finally {
            depth--;
        }
    }

    private OutcomePlan<S> solveNode(final Outcome<S> outcome, final S state,
            final Function<S, OutcomePlan<S>> next) {
        if (--remaining < 0) { throw new SearchLimit(); }
        if (outcome instanceof Outcome.Atomic<S> atom) {
            final Outcome.Transition<S> change = atom.evaluate().apply(state);
            if (change == null || !Double.isFinite(change.value())) {
                return OutcomePlan.unsupported(state, "Unsupported transition");
            }
            final OutcomePlan<S> tail = next.apply(change.state());
            final OutcomePlan<S> plan = new OutcomePlan<>(change.value() + tail.value(), tail.state(),
                    tail.decisions(), tail.branches(), tail.supported(), tail.reason());
            if (atom.description().isEmpty() && change.resolvedEffect() == null) { return plan; }
            return decision(plan, DecisionKind.EFFECT, atom.description(), change.resolvedEffect() == null
                    ? List.of() : List.of(change.resolvedEffect()));
        }
        if (outcome instanceof Outcome.Sequence<S> sequence) {
            return sequence(sequence.children(), 0, state, next);
        }
        if (outcome instanceof Outcome.Deferred<S> deferred) {
            return solve(deferred.build().apply(state), state, next);
        }
        if (outcome instanceof Outcome.Target<S, ?> target) {
            return target(target, state, next);
        }
        if (outcome instanceof Outcome.Choice<S> choice) {
            final List<List<Integer>> selections = new ArrayList<>();
            combinations(choice, 0, new ArrayList<>(), selections);
            OutcomePlan<S> best = null;
            for (final List<Integer> selected : selections) {
                final List<Outcome<S>> children = new ArrayList<>();
                for (final int index : selected) { children.add(choice.options().get(index)); }
                final OutcomePlan<S> candidate = sequence(children, 0, state, next);
                if (!candidate.supported() && !candidate.unavailable()) { return candidate; }
                if (candidate.supported() && better(candidate, best, choice.maximize())) {
                    best = decision(candidate, DecisionKind.CHOICE, choice.id(), selected);
                }
            }
            return best == null ? OutcomePlan.unsupported(state, "No legal choice") : best;
        }
        final Outcome.Random<S> random = (Outcome.Random<S>) outcome;
        final List<OutcomePlan<S>> branches = new ArrayList<>();
        double total = 0;
        double weight = 0;
        final double scale = random.options().stream().mapToDouble(Outcome.Weighted::weight).max().orElseThrow();
        for (final Outcome.Weighted<S> option : random.options()) {
            final OutcomePlan<S> branch = solve(option.outcome(), state, next);
            if (!branch.supported()) { return branch; }
            branches.add(branch);
            total += (option.weight() / scale) * branch.value();
            weight += option.weight() / scale;
        }
        // There is no single resulting state. Each branch includes its own continuation.
        return new OutcomePlan<>(total / weight, state,
                List.of(new OutcomePlan.Decision(DecisionKind.RANDOM, random.id(),
                        random.options().stream().map(Outcome.Weighted::weight).toList())),
                branches, true, "");
    }

    private OutcomePlan<S> sequence(final List<Outcome<S>> children, final int index,
            final S state, final Function<S, OutcomePlan<S>> next) {
        if (index == children.size()) { return next.apply(state); }
        return solve(children.get(index), state,
                s -> sequence(children, index + 1, s, next));
    }

    private <T> OutcomePlan<S> target(final Outcome.Target<S, T> target, final S state,
            final Function<S, OutcomePlan<S>> next) {
        OutcomePlan<S> best = null;
        for (final T candidate : target.candidates().apply(state)) {
            final OutcomePlan<S> plan = solve(target.child(), target.bind().apply(state, candidate), next);
            if (!plan.supported() && !plan.unavailable()) { return plan; }
            if (plan.supported() && better(plan, best, target.maximize())) {
                best = decision(plan, DecisionKind.TARGET, target.id(), List.of(candidate));
            }
        }
        return best == null ? OutcomePlan.unsupported(state, "No legal target") : best;
    }

    private void combinations(final Outcome.Choice<S> choice, final int start,
            final List<Integer> selected, final List<List<Integer>> result) {
        if (--remaining < 0) { throw new SearchLimit(); }
        if (selected.size() >= choice.minimum()) { result.add(List.copyOf(selected)); }
        if (selected.size() == choice.maximum()) { return; }
        for (int i = start; i < choice.options().size(); i++) {
            selected.add(i);
            combinations(choice, choice.repeat() ? i : i + 1, selected, result);
            selected.remove(selected.size() - 1);
        }
    }

    private static <S> boolean better(final OutcomePlan<S> candidate,
            final OutcomePlan<S> best, final boolean maximize) {
        return best == null || (maximize ? candidate.value() > best.value() : candidate.value() < best.value());
    }

    private static <S> OutcomePlan<S> decision(final OutcomePlan<S> plan,
            final DecisionKind kind, final String id, final List<?> selections) {
        final List<OutcomePlan.Decision> decisions = new ArrayList<>();
        decisions.add(new OutcomePlan.Decision(kind, id, selections));
        decisions.addAll(plan.decisions());
        return new OutcomePlan<>(plan.value(), plan.state(), decisions,
                plan.branches(), plan.supported(), plan.reason());
    }

    private static final class SearchLimit extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
