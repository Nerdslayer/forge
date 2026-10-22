package forge.ai.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cheap whole-board proposals, not scores or an optimal-block certificate. */
final class CombatDefenseSeedBuilder {
    private CombatDefenseSeedBuilder() { }

    static List<CombatAssignment> build(final PublicCombatSnapshot snapshot, final PreparedCombatValuation values,
            final Map<Integer, List<Integer>> fixed, final CombatSearchBudget budget) {
        final Map<Integer, List<Integer>> baseline = new LinkedHashMap<>();
        final Set<Integer> used = new HashSet<>();
        for (final var entry : fixed.entrySet()) {
            if (!budget.tryConsume()) { return List.of(); }
            final var attacker = snapshot.creatures().get(entry.getKey());
            if (attacker == null || !snapshot.attackersToDefenders().containsKey(entry.getKey())
                    || entry.getValue().size() > Math.min(8, attacker.maximumBlockers())) { return List.of(); }
            for (final int blocker : entry.getValue()) {
                if (!budget.tryConsume() || !used.add(blocker)
                        || !snapshot.legalBlockers().getOrDefault(entry.getKey(), Set.of()).contains(blocker)) { return List.of(); }
            }
            baseline.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        final Map<Integer, Integer> flexibility = new HashMap<>();
        final Map<Integer, Long> loss = new HashMap<>();
        final Map<Integer, Long> preventable = new HashMap<>();
        for (final int id : snapshot.attackersToDefenders().keySet()) {
            final List<Integer> toughness = new ArrayList<>();
            for (final int blocker : snapshot.legalBlockers().getOrDefault(id, Set.of())) {
                if (!budget.tryConsume()) { return List.of(); }
                flexibility.merge(blocker, 1, Integer::sum);
                loss.computeIfAbsent(blocker, key -> Math.abs((long) values.evaluateLosses(Set.of(key)).total()));
                final var body = snapshot.creatures().get(blocker);
                toughness.add(snapshot.creatures().get(id).deathtouch() ? 1 : Math.max(0, body.toughness() - body.markedDamage()));
            }
            final var attacker = snapshot.creatures().get(id);
            final int minimum = Math.max(1, attacker.minimumBlockers());
            final long prevention = minimum > attacker.maximumBlockers() || toughness.size() < minimum ? 0
                    : !attacker.trample() ? damage(attacker) : Math.min(damage(attacker), toughness.stream()
                            .sorted(Comparator.reverseOrder()).limit(minimum).mapToLong(Integer::longValue).sum());
            preventable.put(id, prevention);
        }
        final Comparator<Integer> blockerOrder = Comparator.<Integer>comparingInt(id -> flexibility.getOrDefault(id, 0))
                .thenComparingLong(id -> loss.getOrDefault(id, 0L))
                .thenComparingInt(Integer::intValue);
        for (final int id : fixed.keySet().stream().sorted().toList()) {
            if (!snapshot.creatures().get(id).permitsBlockerCount(baseline.get(id).size())
                    && !assign(snapshot, id, baseline, used, blockerOrder, budget, true)) { return List.of(); }
        }
        final List<CombatAssignment> proposals = new ArrayList<>();
        proposals.add(new CombatAssignment(snapshot.attackersToDefenders(), baseline));
        final Comparator<Integer> damageOrder = Comparator.<Integer>comparingLong(id -> preventable.getOrDefault(id, 0L)).reversed()
                .thenComparingInt(Integer::intValue);
        for (final boolean scarceFirst : List.of(false, true)) {
            final Map<Integer, List<Integer>> blocks = new LinkedHashMap<>(baseline);
            final Set<Integer> assigned = new HashSet<>(used);
            final Comparator<Integer> order = scarceFirst
                    ? Comparator.<Integer>comparingInt(id -> snapshot.legalBlockers().getOrDefault(id, Set.of()).size()).thenComparing(damageOrder)
                    : damageOrder;
            boolean complete = true;
            for (final int id : snapshot.attackersToDefenders().keySet().stream().sorted(order).toList()) {
                if (!budget.tryConsume()) { complete = false; break; }
                if (damage(snapshot.creatures().get(id)) == 0 || !blocks.getOrDefault(id, List.of()).isEmpty()) { continue; }
                if (!assign(snapshot, id, blocks, assigned, blockerOrder, budget, false)) {
                    repair(snapshot, id, blocks, assigned, fixed, blockerOrder, budget);
                }
            }
            // Reinforce trample only after covering the whole attack, not before other attackers get a blocker.
            if (complete) {
                for (final int id : snapshot.attackersToDefenders().keySet().stream().sorted(damageOrder).toList()) {
                    final var attacker = snapshot.creatures().get(id);
                    if (!attacker.trample() || blocks.getOrDefault(id, List.of()).isEmpty()) { continue; }
                    final var group = new ArrayList<>(blocks.get(id));
                    long toughness = group.stream().mapToLong(b -> attacker.deathtouch() ? 1
                            : Math.max(0, snapshot.creatures().get(b).toughness() - snapshot.creatures().get(b).markedDamage())).sum();
                    for (final int blocker : snapshot.legalBlockers().getOrDefault(id, Set.of()).stream().sorted(blockerOrder).toList()) {
                        if (!budget.tryConsume()) { complete = false; break; }
                        if (toughness >= damage(attacker) || group.size() >= Math.min(3, attacker.maximumBlockers())) { break; }
                        if (assigned.add(blocker)) {
                            group.add(blocker);
                            toughness += attacker.deathtouch() ? 1 : Math.max(0,
                                    snapshot.creatures().get(blocker).toughness() - snapshot.creatures().get(blocker).markedDamage());
                        }
                    }
                    blocks.put(id, List.copyOf(group));
                }
            }
            if (complete) {
                final var proposal = new CombatAssignment(snapshot.attackersToDefenders(), blocks);
                if (!proposals.contains(proposal)) { proposals.add(proposal); }
            }
        }
        // TODO: Repairs involving gangs/multiple displacements, larger minimum groups, and globally mandatory blocks.
        // The scarcity-first variant avoids common flexible-blocker traps without recursive matching.
        return List.copyOf(proposals);
    }

    private static void repair(final PublicCombatSnapshot snapshot, final int uncovered,
            final Map<Integer, List<Integer>> blocks, final Set<Integer> used, final Map<Integer, List<Integer>> fixed,
            final Comparator<Integer> order, final CombatSearchBudget budget) {
        final var attacker = snapshot.creatures().get(uncovered);
        if (attacker.trample() || !attacker.permitsBlockerCount(1)) { return; }
        // One deterministic augmenting step. The donor remains blocked; fixed declarations never move.
        for (final int donor : blocks.keySet().stream().sorted().toList()) {
            if (!budget.tryConsume()) { return; }
            if (fixed.containsKey(donor) || blocks.get(donor).size() != 1 || snapshot.creatures().get(donor).trample()) { continue; }
            final int blocker = blocks.get(donor).get(0);
            if (!snapshot.legalBlockers().getOrDefault(uncovered, Set.of()).contains(blocker)) { continue; }
            for (final int replacement : snapshot.legalBlockers().getOrDefault(donor, Set.of()).stream().sorted(order).toList()) {
                if (!budget.tryConsume()) { return; }
                if (used.add(replacement)) {
                    blocks.put(donor, List.of(replacement));
                    blocks.put(uncovered, List.of(blocker));
                    return;
                }
            }
        }
    }

    private static boolean assign(final PublicCombatSnapshot snapshot, final int id,
            final Map<Integer, List<Integer>> blocks, final Set<Integer> used, final Comparator<Integer> order,
            final CombatSearchBudget budget, final boolean completingFixed) {
        final var attacker = snapshot.creatures().get(id);
        final List<Integer> group = new ArrayList<>(blocks.getOrDefault(id, List.of()));
        final int minimum = Math.max(1, attacker.minimumBlockers());
        if (minimum > Math.min(3, attacker.maximumBlockers())) { return false; }
        for (final int blocker : snapshot.legalBlockers().getOrDefault(id, Set.of()).stream().sorted(order).toList()) {
            if (!budget.tryConsume()) { return false; }
            if (group.size() >= minimum) { break; }
            if (!used.contains(blocker)) { group.add(blocker); }
        }
        if (group.size() < minimum) { return false; }
        if (!completingFixed && attacker.trample()) {
            // Proposal ordering is approximate; shared projection verifies first strike, shields and allocation.
            if (group.stream().allMatch(b -> snapshot.creatures().get(b).toughness() <= snapshot.creatures().get(b).markedDamage())) { return false; }
        }
        blocks.put(id, List.copyOf(group));
        used.addAll(group);
        return true;
    }

    private static long damage(final PublicCombatSnapshot.Creature card) {
        return (long) Math.max(0, card.combatDamage()) * (card.doubleStrike() ? 2 : 1);
    }
}
