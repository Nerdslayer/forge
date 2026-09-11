package forge.ai.effect;

import java.util.List;
import java.util.Map;

/** Immutable script structure; expressions remain symbolic until bound by an environment. */
public record AbilityOutcomeDescription(String path, String api, Map<String, String> parameters,
        List<AbilityOutcomeDescription> choices, AbilityOutcomeDescription next, String issue) {
    public AbilityOutcomeDescription {
        parameters = Map.copyOf(parameters);
        choices = List.copyOf(choices);
    }

    public static AbilityOutcomeDescription unresolved(final String path, final String reason) {
        return new AbilityOutcomeDescription(path, "", Map.of(), List.of(), null, reason);
    }
}
