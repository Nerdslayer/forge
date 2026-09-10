package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import forge.ai.effect.OutcomePlan.DecisionKind;
import forge.ai.effect.OutcomePlan.Completeness;

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
                    tail.decisions(), tail.branches(), tail.supported(), tail.reason(), tail.completeness(),
                    tail.unresolvedProbability(), tail.unresolvedAlternatives());
            if (atom.description().isEmpty() && change.resolvedEffect() == null) { return plan; }
            return decision(plan, DecisionKind.EFFECT, atom.description(), change.resolvedEffect() == null
                    ? List.of() : List.of(change.resolvedEffect()));
        }
        if (outcome instanceof Outcome.Sequence<S> sequence) {
            return sequence(sequence.children(), 0, state, next);
        }
        if (outcome instanceof Outcome.Batch<S, ?> batch) {
            return batch(batch, state, next);
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
            final List<String> unresolved = new ArrayList<>();
            for (final List<Integer> selected : selections) {
                final List<Outcome<S>> children = new ArrayList<>();
                for (final int index : selected) { children.add(choice.options().get(index)); }
                final OutcomePlan<S> candidate = sequence(children, 0, state, next);
                if (!candidate.unavailable() && betterCandidate(candidate, best, choice.maximize())) {
                    best = decision(candidate, DecisionKind.CHOICE, choice.id(), selected);
                }
                if (!candidate.complete() && !candidate.unavailable()) {
                    unresolved.add(choice.id() + " " + selected + ": " + reason(candidate));
                }
            }
            if (best == null) {
                return unresolved.isEmpty() ? OutcomePlan.unsupported(state, "No legal choice")
                        : OutcomePlan.partial(0, state, List.of(), List.of(),
                                "Choice has no fully evaluated alternative", 0, unresolved);
            }
            if (unresolved.isEmpty()) { return best; }
            return partial(best, "Choice has unresolved alternatives", unresolved);
        }
        final Outcome.Random<S> random = (Outcome.Random<S>) outcome;
        final List<OutcomePlan<S>> branches = new ArrayList<>();
        double total = 0;
        double unresolvedWeight = 0;
        boolean supported = false;
        final List<String> unresolved = new ArrayList<>();
        final double totalWeight = random.options().stream().mapToDouble(Outcome.Weighted::weight).sum();
        for (final Outcome.Weighted<S> option : random.options()) {
            final OutcomePlan<S> branch = solve(option.outcome(), state, next);
            branches.add(branch);
            supported |= branch.supported();
            final double probability = option.weight() / totalWeight;
            total += probability * branch.value();
            if (!branch.complete() && !branch.unavailable()) {
                unresolvedWeight += probability;
                unresolved.add(random.id() + " branch: " + reason(branch));
            }
        }
        // There is no single resulting state. Each branch includes its own continuation.
        final List<OutcomePlan.Decision> decisions = List.of(new OutcomePlan.Decision(DecisionKind.RANDOM,
                random.id(), random.options().stream().map(Outcome.Weighted::weight).toList()));
        if (unresolved.isEmpty()) {
            return new OutcomePlan<>(total, state, decisions, branches, true, "",
                    Completeness.COMPLETE, 0, List.of());
        }
        return new OutcomePlan<>(total, state, decisions, branches, supported,
                "Random outcome has unresolved branches", Completeness.PARTIAL,
                unresolvedWeight, unresolved);
    }

    private <D> OutcomePlan<S> batch(final Outcome.Batch<S, D> batch, final S state,
            final Function<S, OutcomePlan<S>> next) {
        return solve(new Outcome.Atomic<S>(batch.description(), snapshot -> {
            final List<D> prepared = new ArrayList<>();
            for (final Function<S, D> prepare : batch.preparations()) {
                if (--remaining < 0) { throw new SearchLimit(); }
                final D change = prepare.apply(snapshot);
                if (change == null) { return null; }
                prepared.add(change);
            }
            return batch.commit().apply(snapshot, List.copyOf(prepared));
        }), state, next);
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
        final List<String> unresolved = new ArrayList<>();
        for (final T candidate : target.candidates().apply(state)) {
            final OutcomePlan<S> plan = solve(target.child(), target.bind().apply(state, candidate), next);
            if (!plan.unavailable() && betterCandidate(plan, best, target.maximize())) {
                best = decision(plan, DecisionKind.TARGET, target.id(), List.of(candidate));
            }
            if (!plan.complete() && !plan.unavailable()) {
                unresolved.add(target.id() + " " + candidate + ": " + reason(plan));
            }
        }
        if (best == null) {
            return unresolved.isEmpty() ? OutcomePlan.unsupported(state, "No legal target")
                    : OutcomePlan.partial(0, state, List.of(), List.of(),
                            "Target has no fully evaluated candidate", 0, unresolved);
        }
        if (unresolved.isEmpty()) { return best; }
        return partial(best, "Target has unresolved candidates", unresolved);
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

    private static <S> boolean betterCandidate(final OutcomePlan<S> candidate,
            final OutcomePlan<S> best, final boolean maximize) {
        return best == null || candidate.complete() && !best.complete()
                || candidate.complete() == best.complete() && better(candidate, best, maximize);
    }

    private static <S> String reason(final OutcomePlan<S> plan) {
        return plan.reason().isBlank() ? plan.completeness().name() : plan.reason();
    }

    private static <S> OutcomePlan<S> decision(final OutcomePlan<S> plan,
            final DecisionKind kind, final String id, final List<?> selections) {
        final List<OutcomePlan.Decision> decisions = new ArrayList<>();
        decisions.add(new OutcomePlan.Decision(kind, id, selections));
        decisions.addAll(plan.decisions());
        return new OutcomePlan<>(plan.value(), plan.state(), decisions,
                plan.branches(), plan.supported(), plan.reason(), plan.completeness(),
                plan.unresolvedProbability(), plan.unresolvedAlternatives());
    }

    private static <S> OutcomePlan<S> partial(final OutcomePlan<S> plan, final String reason,
            final List<String> unresolved) {
        return new OutcomePlan<>(plan.value(), plan.state(), plan.decisions(), plan.branches(), plan.supported(),
                reason, Completeness.PARTIAL, plan.unresolvedProbability(), unresolved);
    }

    private static final class SearchLimit extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
