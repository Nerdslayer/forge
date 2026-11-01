package forge.ai.combat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable public response worlds. No live objects, abilities, or callbacks enter search. */
public record PreparedCombatCrewResponses(List<Response> responses, Map<Integer, Long> identities,
        List<String> reasons, Map<Integer, List<String>> abilityRules) {
    public PreparedCombatCrewResponses {
        responses = List.copyOf(responses);
        identities = Map.copyOf(identities);
        reasons = List.copyOf(reasons);
        final Map<Integer, List<String>> rules = new LinkedHashMap<>();
        abilityRules.forEach((id, entries) -> rules.put(id, List.copyOf(entries)));
        abilityRules = Map.copyOf(rules);
    }

    public PreparedCombatCrewResponses(final List<Response> responses, final Map<Integer, Long> identities,
            final List<String> reasons) {
        this(responses, identities, reasons, Map.of());
    }

    public record Response(Map<Integer, Set<Integer>> crews, PublicCombatSnapshot world) {
        public Response {
            final Map<Integer, Set<Integer>> frozen = new LinkedHashMap<>();
            crews.forEach((id, pilots) -> frozen.put(id, Set.copyOf(pilots)));
            crews = Map.copyOf(frozen);
        }
    }

    public static PreparedCombatCrewResponses empty() {
        return new PreparedCombatCrewResponses(List.of(), Map.of(), List.of());
    }
}
