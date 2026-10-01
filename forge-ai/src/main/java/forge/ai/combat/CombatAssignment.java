package forge.ai.combat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable declarations keyed by public entity IDs; no live cards or callbacks. */
public record CombatAssignment(Map<Integer, Integer> attackersToDefenders,
        Map<Integer, List<Integer>> blockersByAttacker) {
    public CombatAssignment {
        attackersToDefenders = Map.copyOf(attackersToDefenders);
        final Map<Integer, List<Integer>> blocks = new LinkedHashMap<>();
        blockersByAttacker.forEach((id, group) -> blocks.put(id, List.copyOf(group)));
        blockersByAttacker = Map.copyOf(blocks);
    }
}
