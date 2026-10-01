package forge.ai.combat;

import java.util.Map;

/** Source-specific damage decisions retained separately for each simultaneous damage step. */
public record CombatDamagePlan(Map<Integer, CombatDamageAllocation.Allocation> firstStrike,
        Map<Integer, CombatDamageAllocation.Allocation> regular) {
    public CombatDamagePlan {
        firstStrike = Map.copyOf(firstStrike);
        regular = Map.copyOf(regular);
    }

    public Map<Integer, CombatDamageAllocation.Allocation> step(final boolean first) {
        return first ? firstStrike : regular;
    }
}
