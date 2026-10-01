package forge.ai.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic bounded declarations shared by immediate and follow-up searches. */
final class CombatAttackCandidates {
    private CombatAttackCandidates() { }

    record Declarations(List<List<Integer>> groups, boolean exhaustive) { }

    static Declarations generate(final List<Integer> candidates) {
        final List<Integer> eligible = candidates.stream().distinct().sorted().toList();
        final Set<List<Integer>> groups = new LinkedHashSet<>();
        groups.add(eligible);
        groups.add(List.of());
        if (eligible.size() <= 8) {
            for (int mask = 1; mask < (1 << eligible.size()); mask++) {
                final List<Integer> group = new ArrayList<>();
                for (int bit = 0; bit < eligible.size(); bit++) {
                    if ((mask & (1 << bit)) != 0) { group.add(eligible.get(bit)); }
                }
                groups.add(List.copyOf(group));
            }
        } else {
            // TODO: Add beam/local reassignment and audited legacy seeds for larger attacks.
            for (final int id : eligible) {
                groups.add(List.of(id));
                groups.add(eligible.stream().filter(other -> other != id).toList());
            }
        }
        return new Declarations(List.copyOf(groups), eligible.size() <= 8);
    }

    static PublicCombatSnapshot select(final PublicCombatSnapshot all, final List<Integer> selected) {
        final Map<Integer, Integer> attacks = new LinkedHashMap<>();
        final Map<Integer, Set<Integer>> blocks = new LinkedHashMap<>();
        for (final int id : selected) {
            if (!all.attackersToDefenders().containsKey(id)) { throw new IllegalArgumentException("Unknown attack alternative"); }
            attacks.put(id, all.attackersToDefenders().get(id));
            blocks.put(id, all.legalBlockers().getOrDefault(id, Set.of()));
        }
        return new PublicCombatSnapshot(all.observingPlayerId(), all.attackingPlayerId(), all.defendingPlayerId(),
                all.creatures(), all.players(), attacks, blocks, all.unavailableReasons(), all.unsupportedReasons(), all.legacyDamageOrder(),
                all.resources(), all.triggers(), all.observedAttackers(), all.observedBlockers(), all.preventionRules(), all.staticWorlds());
    }
}
