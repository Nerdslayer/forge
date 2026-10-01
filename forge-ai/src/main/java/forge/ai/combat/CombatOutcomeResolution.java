package forge.ai.combat;

import java.util.List;
import java.util.Map;

/** Frozen immediate outcome utility and resource continuation; incomplete results are not scored. */
public record CombatOutcomeResolution(boolean supported, int utility,
        Map<Integer, CombatPlayerResources> resourcesAfter, Map<CombatAbilityKey, Integer> resolutions,
        List<String> reasons) {
    public CombatOutcomeResolution {
        resourcesAfter = Map.copyOf(resourcesAfter);
        resolutions = Map.copyOf(resolutions);
        reasons = List.copyOf(reasons);
    }

    public static CombatOutcomeResolution empty() {
        return new CombatOutcomeResolution(true, 0, Map.of(), Map.of(), List.of());
    }
}
