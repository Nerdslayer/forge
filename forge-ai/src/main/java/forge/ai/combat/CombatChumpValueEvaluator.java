package forge.ai.combat;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

import forge.ai.PlayerResourceValueEvaluator;

/** Decision-local policy adjustment, not a change to the shared life-value metric. */
final class CombatChumpValueEvaluator {
    record Evaluation(int adjustment, boolean complete) { }

    private final PublicCombatSnapshot snapshot;
    private final CombatSearchBudget budget;
    private record BaselineKey(CombatAssignment assignment, CombatDamagePlan plan) { }
    private final Map<BaselineKey, CombatProjection> baselines = new HashMap<>();

    CombatChumpValueEvaluator(final PublicCombatSnapshot snapshot0, final CombatSearchBudget budget0) {
        snapshot = snapshot0;
        budget = budget0;
    }

    Evaluation evaluate(final CombatAssignment assignment, final CombatProjection projection,
            final Map<Integer, List<Integer>> fixedBlocks) {
        return evaluate(assignment, projection, fixedBlocks, null);
    }

    Evaluation evaluate(final CombatAssignment assignment, final CombatProjection projection,
            final Map<Integer, List<Integer>> fixedBlocks, final CombatDamagePlan plan) {
        if (projection.terminal() != CombatProjection.Terminal.NONE) { return new Evaluation(0, true); }
        final Map<Integer, List<Integer>> remaining = new HashMap<>(assignment.blockersByAttacker());
        remaining.entrySet().removeIf(entry -> !entry.getValue().isEmpty()
                && fixedBlocks.getOrDefault(entry.getKey(), List.of()).isEmpty()
                && !projection.lostCreatures().contains(entry.getKey())
                && entry.getValue().stream().allMatch(projection.lostCreatures()::contains));
        if (remaining.size() == assignment.blockersByAttacker().size()) { return new Evaluation(0, true); }

        // Retain profitable trades and safe blocks when measuring only the life saved by chumps.
        final CombatAssignment withoutChumps = new CombatAssignment(assignment.attackersToDefenders(), remaining);
        final CombatDamagePlan retainedPlan = plan == null ? null : new CombatDamagePlan(
                retainAllocations(plan.firstStrike(), remaining), retainAllocations(plan.regular(), remaining));
        final BaselineKey key = new BaselineKey(withoutChumps, retainedPlan);
        CombatProjection baseline = baselines.get(key);
        if (baseline == null) {
            if (!budget.tryConsume()) { return new Evaluation(0, false); }
            // Preserve remaining gangs' chosen allocations; removed groups become unblocked.
            // TODO: Reoptimize retained allocations if changed declaration triggers make them illegal.
            baseline = CombatOutcomePredictor.predict(snapshot, withoutChumps, retainedPlan);
            if (!baseline.supported() || !baseline.available()) { return new Evaluation(0, false); }
            baselines.put(key, baseline);
        }
        // Chumps needed to survive keep full credit, as do all terminal block choices.
        if (baseline.terminal() != CombatProjection.Terminal.NONE) { return new Evaluation(0, true); }
        final int defender = snapshot.defendingPlayerId();
        final long savedDamage = damage(baseline, defender) - damage(projection, defender);
        final int after = projection.playerLifeAfter().get(defender);
        if (savedDamage <= 0 || savedDamage >= after) { return new Evaluation(0, true); }
        // Anchor at the actual ending life so lifelink/life-gain benefits are not also discounted.
        final int savedLifeValue = PlayerResourceValueEvaluator.evaluateLifeChange((int) (after - savedDamage), after);
        final int discount = (int) Math.round(savedLifeValue * 0.5);
        return new Evaluation(snapshot.observingPlayerId() == defender ? -discount : discount, true);
    }

    private static Map<Integer, CombatDamageAllocation.Allocation> retainAllocations(
            final Map<Integer, CombatDamageAllocation.Allocation> allocations, final Map<Integer, List<Integer>> remaining) {
        final Map<Integer, CombatDamageAllocation.Allocation> retained = new HashMap<>();
        allocations.forEach((id, allocation) -> {
            if (!remaining.getOrDefault(id, List.of()).isEmpty()) { retained.put(id, allocation); }
        });
        return retained;
    }

    private static long damage(final CombatProjection projection, final int player) {
        return projection.batches().stream().mapToLong(batch -> batch.playerDamage().getOrDefault(player, 0)).sum();
    }
}
