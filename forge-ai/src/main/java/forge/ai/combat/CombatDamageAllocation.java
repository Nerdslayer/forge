package forge.ai.combat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Legal integer damage assignments for one ordinary blocked attacker in one damage step. */
public final class CombatDamageAllocation {
    private CombatDamageAllocation() { }

    /** Lethal for assignment, not destruction: indestructible still needs lethal for trample. */
    public record Target(int id, int lethalDamage) {
        public Target {
            if (id < 0 || lethalDamage < 0) { throw new IllegalArgumentException("Nonnegative target ID and lethal damage required"); }
        }
    }

    public record Allocation(Map<Integer, Integer> creatureDamage, int defenderDamage) {
        public Allocation { creatureDamage = Map.copyOf(creatureDamage); }
    }

    public record Result(List<Allocation> allocations, boolean exhaustive, int nodes) {
        public Result { allocations = List.copyOf(allocations); }
    }

    /** Validates a selected allocation without enumerating alternatives or touching live state. */
    public static boolean isLegal(final int damage, final List<Target> targets, final boolean trample,
            final boolean legacyOrder, final Allocation allocation) {
        if (damage < 0 || allocation.defenderDamage() < 0 || !trample && allocation.defenderDamage() != 0) { return false; }
        final HashSet<Integer> ids = new HashSet<>();
        boolean allLethal = true;
        long total = allocation.defenderDamage();
        for (final Target target : targets) {
            if (!ids.add(target.id())) { return false; }
            final int amount = allocation.creatureDamage().getOrDefault(target.id(), 0);
            if (amount < 0 || legacyOrder && amount > 0 && !allLethal) { return false; }
            total += amount;
            allLethal &= amount >= target.lethalDamage();
        }
        if (!ids.containsAll(allocation.creatureDamage().keySet())) { return false; }
        if (targets.isEmpty() && !trample) { return total == 0; }
        return total == damage && (allocation.defenderDamage() == 0 || allLethal);
    }

    /** Explicit order policy matches Combat's legacy/nonlegacy assignment callback. */
    public static Result enumerate(final int damage, final List<Target> targets, final boolean trample,
            final boolean legacyOrder, final CombatSearchBudget budget) {
        if (damage < 0 || new HashSet<>(targets.stream().map(Target::id).toList()).size() != targets.size()) {
            throw new IllegalArgumentException("Nonnegative damage and unique target IDs required");
        }
        final Enumeration enumeration = new Enumeration(List.copyOf(targets), trample, legacyOrder, budget);
        final int before = budget.used();
        enumeration.visit(0, damage, new LinkedHashMap<>(), true);
        // TODO: Preserve optimizer-selected allocations in the real controller callback.
        // Banding/alternative assignment/prevention are separate unsupported mechanics.
        return new Result(enumeration.results, !enumeration.interrupted, budget.used() - before);
    }

    private static final class Enumeration {
        private final List<Target> targets;
        private final boolean trample;
        private final boolean legacy;
        private final CombatSearchBudget budget;
        private final List<Allocation> results = new ArrayList<>();
        private boolean interrupted;

        private Enumeration(final List<Target> targets0, final boolean trample0,
                final boolean legacy0, final CombatSearchBudget budget0) {
            targets = targets0;
            trample = trample0;
            legacy = legacy0;
            budget = budget0;
        }

        private void visit(final int index, final int remaining, final Map<Integer, Integer> hits, final boolean allLethal) {
            if (!budget.tryConsume()) { interrupted = true; return; }
            if (index == targets.size()) {
                // A blocked nontrampler with no remaining blockers deals no player damage.
                if (remaining == 0 || targets.isEmpty() || trample && allLethal) {
                    results.add(new Allocation(hits, trample ? remaining : 0));
                }
                return;
            }
            final Target target = targets.get(index);
            // Without trample the last recipient must take all the remaining damage.
            final int minimum = !trample && index == targets.size() - 1 ? remaining : 0;
            for (int amount = minimum; ; amount++) {
                if (budget.remaining() == 0) { interrupted = true; return; }
                if (amount > 0) { hits.put(target.id(), amount); }
                final boolean lethal = amount >= target.lethalDamage();
                if (!legacy || lethal || amount == remaining) {
                    visit(index + 1, remaining - amount, hits, allLethal && lethal);
                } else {
                    // Charge pruned candidates too: enormous powers must not evade the budget.
                    if (!budget.tryConsume()) { interrupted = true; return; }
                }
                hits.remove(target.id());
                if (amount == remaining) { break; } // no overflow at Integer.MAX_VALUE
            }
        }
    }
}
