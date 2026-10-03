package forge.ai.combat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Select a frozen declaration from public alternatives for immediate and follow-up searches. */
final class CombatAttackCandidates {
    private CombatAttackCandidates() { }

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
                all.resources(), all.triggers(), all.observedAttackers(), all.observedBlockers(), all.preventionRules(), all.staticWorlds(), all.ignoredEffects());
    }
}
