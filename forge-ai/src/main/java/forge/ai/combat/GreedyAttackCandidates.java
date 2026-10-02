package forge.ai.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.Comparator;

/** Linear declaration heuristic; every requested declaration uses the caller's full evaluator. */
final class GreedyAttackCandidates {
    private GreedyAttackCandidates() { }

    record Selection<T>(Optional<T> best, Optional<T> baseline, boolean complete, int evaluations) { }

    static <T> Selection<T> select(final List<Integer> fixed, final List<Integer> optional,
            final Function<List<Integer>, T> evaluator, final Comparator<T> ordering,
            final IntUnaryOperator representative) {
        final Map<List<Integer>, T> cache = new HashMap<>();
        final Function<List<Integer>, T> evaluate = group -> cache.computeIfAbsent(group, evaluator);
        final List<Integer> combined = new ArrayList<>(fixed);
        combined.addAll(optional);
        final List<Integer> all = combined.stream().distinct().sorted().toList();
        final T baseline = evaluate.apply(fixed);
        // An unavailable future baseline must not hide a separately certified win this turn.
        if (baseline == null) { return new Selection<>(Optional.ofNullable(evaluate.apply(all)), Optional.empty(), false, cache.size()); }
        T best = baseline;
        final Map<Integer, T> singles = new HashMap<>();
        for (final int id : optional) {
            final T candidate = evaluate.apply(add(fixed, representative.applyAsInt(id)));
            if (candidate == null) {
                final T safeguard = evaluate.apply(all);
                if (safeguard != null && ordering.compare(safeguard, best) > 0) { best = safeguard; }
                return new Selection<>(Optional.of(best), Optional.of(baseline), false, cache.size());
            }
            singles.put(id, candidate);
            if (ordering.compare(candidate, best) > 0) { best = candidate; }
        }
        // Always try the overload/lethal safeguard, even when every singleton is a bad attack.
        final T allOut = evaluate.apply(all);
        if (allOut == null) { return new Selection<>(Optional.of(best), Optional.of(baseline), false, cache.size()); }
        if (ordering.compare(allOut, best) > 0) { best = allOut; }

        final List<Integer> ranked = optional.stream().sorted((left, right) -> {
            final int comparison = ordering.compare(singles.get(right), singles.get(left));
            return comparison != 0 ? comparison : Integer.compare(left, right);
        }).toList();
        List<Integer> chosen = fixed;
        T current = baseline;
        for (final int id : ranked) {
            final List<Integer> group = add(chosen, id);
            final T candidate = evaluate.apply(group);
            if (candidate == null) { return new Selection<>(Optional.of(best), Optional.of(baseline), false, cache.size()); }
            // Seed with the best singleton even if negative; later attackers can overload blockers.
            // Equal scores also extend the frontier, but never displace a tied no-attack baseline.
            if (chosen.equals(fixed) || ordering.compare(candidate, current) >= 0) {
                chosen = group;
                current = candidate;
            }
            if (ordering.compare(candidate, best) > 0) { best = candidate; }
        }
        // TODO: Local swaps/pair additions can recover useful non-prefix combinations this heuristic misses.
        return new Selection<>(Optional.of(best), Optional.of(baseline), true, cache.size());
    }

    private static List<Integer> add(final List<Integer> existing, final int id) {
        final List<Integer> group = new ArrayList<>(existing);
        group.add(id);
        return group.stream().distinct().sorted().toList();
    }
}
