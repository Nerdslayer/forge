package forge.ai.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Optional local refinement: never invalidates a completed required search. */
final class CombatAttackRefiner {
    private CombatAttackRefiner() { }

    static CombatAttackSearch.Candidate refine(final CombatAttackSearch.Candidate initial,
            final Map<Integer, Integer> fixed, final Function<Map<Integer, Integer>, CombatAttackSearch.Candidate> evaluator,
            final Comparator<CombatAttackSearch.Candidate> strategic, final CombatSearchBudget budget, final Set<String> reasons) {
        // TODO: Refine terminal fast-path wins with an immediate-only evaluator, not a new future baseline.
        if (initial.combat().projection().terminal() != CombatProjection.Terminal.NONE) { return initial; }
        CombatAttackSearch.Candidate best = initial;
        final int started = budget.used();
        final List<String> removed = new ArrayList<>();
        int trials = 0;
        int rejected = 0;
        boolean complete = true;
        for (int pass = 0; pass < 2; pass++) {
            boolean changed = false;
            for (final int id : best.attackers()) {
                if (fixed.containsKey(id)) { continue; }
                if (budget.remaining() == 0) { complete = false; break; }
                final Map<Integer, Integer> smaller = new LinkedHashMap<>(best.combat().assignment().attackersToDefenders());
                smaller.remove(id);
                final var trial = evaluator.apply(Map.copyOf(smaller));
                trials++;
                if (trial == null) { rejected++; complete = false; continue; }
                if (strategic.compare(trial, best) >= 0) {
                    removed.add(id + ":" + best.total() + "->" + trial.total());
                    best = trial;
                    changed = true;
                }
            }
            if (!changed || budget.remaining() == 0) { break; }
        }
        // TODO: Bounded two-pass pruning is not a global minimum declaration or an ability-opportunity model.
        reasons.add("Attack refinement: before=" + initial.attackers() + ", after=" + best.attackers()
                + ", beforeDestinations=" + initial.destinations() + ", afterDestinations=" + best.destinations()
                + ", removed(score)=" + removed + ", trials=" + trials + ", rejectedTrials=" + rejected
                + ", nodes=" + (budget.used() - started) + ", complete=" + (complete && budget.remaining() > 0));
        return best;
    }

    static int preferSmaller(final CombatAttackSearch.Candidate left, final CombatAttackSearch.Candidate right, final int strategic) {
        if (strategic != 0) { return strategic; }
        final int count = Integer.compare(right.attackers().size(), left.attackers().size());
        if (count != 0) { return count; }
        for (int index = 0; index < left.attackers().size(); index++) {
            final int id = Integer.compare(right.attackers().get(index), left.attackers().get(index));
            if (id != 0) { return id; }
        }
        return 0;
    }
}
