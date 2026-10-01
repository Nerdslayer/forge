package forge.ai.combat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Joint allocation search across attackers and both strike steps, with one frozen utility perspective. */
public final class CombatDamageOptimizer {
    private CombatDamageOptimizer() { }

    public record Candidate(CombatAssignment assignment, CombatDamagePlan damagePlan, CombatProjection projection,
            CombatTransitionValueEvaluator.Score score) { }

    public record Result(Optional<Candidate> best, boolean exhaustive, boolean outcomeSupported,
            int nodes, List<String> reasons) {
        public Result { reasons = List.copyOf(reasons); }
    }

    public static Result optimize(final PublicCombatSnapshot snapshot, final CombatAssignment assignment,
            final PreparedCombatValuation values, final CombatSearchBudget budget) {
        return optimize(snapshot, assignment, values, budget, false, projection -> 0, (left, right) -> 0);
    }

    /** Chooses pre-damage legacy orders as well as allocations; use only while orders are still selectable. */
    public static Result optimizeBlockGroups(final PublicCombatSnapshot snapshot, final CombatAssignment assignment,
            final PreparedCombatValuation values, final CombatSearchBudget budget) {
        return optimize(snapshot, assignment, values, budget, true, projection -> 0, (left, right) -> 0);
    }

    public static Result optimizeBlockGroups(final PublicCombatSnapshot snapshot, final CombatAssignment assignment,
            final PreparedCombatValuation values, final CombatSearchBudget budget,
            final java.util.function.ToIntFunction<CombatProjection> adjustment) {
        return optimize(snapshot, assignment, values, budget, true, adjustment, (left, right) -> 0);
    }

    public static Result optimizeBlockGroups(final PublicCombatSnapshot snapshot, final CombatAssignment assignment,
            final PreparedCombatValuation values, final CombatSearchBudget budget,
            final java.util.function.ToIntFunction<CombatProjection> adjustment,
            final java.util.function.BiFunction<CombatProjection, CombatProjection, Integer> tacticalPriority) {
        return optimize(snapshot, assignment, values, budget, true, adjustment, tacticalPriority);
    }

    private record Work(CombatAssignment assignment, CombatDamagePlan plan, int orderIndex) { }

    private static Result optimize(final PublicCombatSnapshot snapshot, final CombatAssignment assignment,
            final PreparedCombatValuation values, final CombatSearchBudget budget, final boolean chooseOrders,
            final java.util.function.ToIntFunction<CombatProjection> adjustment,
            final java.util.function.BiFunction<CombatProjection, CombatProjection, Integer> tacticalPriority) {
        final int before = budget.used();
        final List<Integer> orderable = chooseOrders && snapshot.legacyDamageOrder()
                ? assignment.blockersByAttacker().keySet().stream()
                        .filter(id -> assignment.blockersByAttacker().get(id).size() > 1).sorted().toList() : List.of();
        final ArrayDeque<Work> pending = new ArrayDeque<>();
        pending.push(new Work(assignment, new CombatDamagePlan(Map.of(), Map.of()), 0));
        final Set<String> reasons = new LinkedHashSet<>();
        Candidate best = null;
        int bestAdjustment = 0;
        boolean exhaustive = true;
        boolean supported = true;
        final boolean maximizing = snapshot.observingPlayerId() == snapshot.attackingPlayerId();
        while (!pending.isEmpty()) {
            if (!budget.tryConsume()) { exhaustive = false; break; }
            final Work work = pending.pop();
            final CombatDamagePlan plan = work.plan();
            if (work.orderIndex() < orderable.size()) {
                final int id = orderable.get(work.orderIndex());
                final List<Integer> group = work.assignment().blockersByAttacker().get(id);
                if (group.size() > 3) {
                    exhaustive = false;
                    reasons.add("Legacy order alternatives for groups larger than three are not fully searched");
                    pending.push(new Work(work.assignment(), plan, work.orderIndex() + 1));
                } else {
                    final List<List<Integer>> orders = new ArrayList<>();
                    final boolean complete = orders(group, new ArrayList<>(), orders, budget);
                    exhaustive &= complete;
                    for (int index = orders.size() - 1; index >= 0; index--) {
                        final Map<Integer, List<Integer>> blocks = new HashMap<>(work.assignment().blockersByAttacker());
                        blocks.put(id, orders.get(index));
                        pending.push(new Work(new CombatAssignment(assignment.attackersToDefenders(), blocks), plan, work.orderIndex() + 1));
                    }
                }
                continue;
            }
            final CombatOutcomePredictor.AllocationRequest[] unresolved = new CombatOutcomePredictor.AllocationRequest[1];
            final CombatProjection projection = CombatOutcomePredictor.predictResolving(snapshot, work.assignment(), request -> {
                final CombatDamageAllocation.Allocation allocation = plan.step(request.firstStrike()).get(request.attackerId());
                if (allocation == null) { unresolved[0] = request; }
                return allocation;
            });
            final CombatOutcomePredictor.AllocationRequest request = unresolved[0];
            if (request != null) {
                final CombatDamageAllocation.Result alternatives = CombatDamageAllocation.enumerate(request.damage(),
                        request.targets(), request.trample(), request.legacyOrder(), budget);
                exhaustive &= alternatives.exhaustive();
                // Reverse insertion preserves deterministic enumeration order for equal utility.
                for (int index = alternatives.allocations().size() - 1; index >= 0; index--) {
                    final Map<Integer, CombatDamageAllocation.Allocation> step = new HashMap<>(plan.step(request.firstStrike()));
                    step.put(request.attackerId(), alternatives.allocations().get(index));
                    pending.push(new Work(work.assignment(), request.firstStrike() ? new CombatDamagePlan(step, plan.regular())
                            : new CombatDamagePlan(plan.firstStrike(), step), work.orderIndex()));
                }
                continue;
            }
            if (!projection.supported() || !projection.available()) {
                supported &= projection.supported();
                reasons.addAll(projection.reasons());
                continue;
            }
            final Candidate candidate = new Candidate(work.assignment(), plan, projection,
                    CombatTransitionValueEvaluator.evaluate(snapshot, values, projection));
            final int candidateAdjustment = adjustment.applyAsInt(projection);
            int comparison = best == null ? 0 : CombatTransitionValueEvaluator.compare(candidate.score(), best.score());
            if (best != null && candidate.score().terminal() == best.score().terminal()) {
                comparison = tacticalPriority.apply(candidate.projection(), best.projection());
                if (comparison == 0) {
                    comparison = Integer.compare(CombatOutcomePredictor.add(candidate.score().total(), candidateAdjustment),
                            CombatOutcomePredictor.add(best.score().total(), bestAdjustment));
                }
            }
            if (best == null || (maximizing ? comparison > 0 : comparison < 0)) {
                best = candidate;
                bestAdjustment = candidateAdjustment;
            }
        }
        if (!exhaustive) { reasons.add("Shared allocation/projection search budget exhausted"); }
        // TODO: Alternative assignment/banding mechanics and richer state/event projections.
        return new Result(Optional.ofNullable(best), exhaustive, supported, budget.used() - before, List.copyOf(reasons));
    }

    private static boolean orders(final List<Integer> group, final List<Integer> prefix,
            final List<List<Integer>> result, final CombatSearchBudget budget) {
        if (!budget.tryConsume()) { return false; }
        if (prefix.size() == group.size()) { result.add(List.copyOf(prefix)); return true; }
        for (final int id : group.stream().sorted().toList()) {
            if (prefix.contains(id)) { continue; }
            prefix.add(id);
            final boolean complete = orders(group, prefix, result, budget);
            prefix.remove(prefix.size() - 1);
            if (!complete) { return false; }
        }
        return true;
    }
}
