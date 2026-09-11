package forge.ai.effect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;

/** Snapshots Forge abilities without resolving expressions or changing target bindings. */
public final class AbilityOutcomeParser {
    private AbilityOutcomeParser() { }

    public static AbilityOutcomeDescription parse(final SpellAbility root, final String path) {
        return parse(root, path, new java.util.HashMap<>());
    }

    static AbilityOutcomeDescription parse(final SpellAbility root, final String path,
            final Map<String, SpellAbility> bindings) {
        return parse(root, path, Collections.newSetFromMap(new IdentityHashMap<>()), 0, new int[] {1024}, bindings);
    }

    private static AbilityOutcomeDescription parse(final SpellAbility ability, final String path,
            final Set<SpellAbility> ancestors, final int depth, final int[] remaining,
            final Map<String, SpellAbility> bindings) {
        if (ability == null) { return AbilityOutcomeDescription.unresolved(path, "Missing outcome"); }
        if (depth > 24 || --remaining[0] < 0) {
            return AbilityOutcomeDescription.unresolved(path, "Ability traversal limit exceeded");
        }
        if (!ancestors.add(ability)) { return AbilityOutcomeDescription.unresolved(path, "Cyclic ability reference"); }
        try {
            bindings.put(path, ability);
            final List<AbilityOutcomeDescription> choices = new ArrayList<>();
            final List<AbilitySub> modes = ability.getAdditionalAbilityList("Choices");
            if (modes != null) {
                for (int i = 0; i < modes.size(); i++) {
                    if (remaining[0] <= 0) {
                        choices.add(AbilityOutcomeDescription.unresolved(path + "/choices", "Ability traversal limit exceeded"));
                        break;
                    }
                    choices.add(parse(modes.get(i), path + "/choice:" + i, ancestors, depth + 1, remaining, bindings));
                }
            }
            // TODO: Additional execution lists and remembered references need explicit adapters.
            // Backends must reject unknown semantic parameters instead of discarding them.
            return new AbilityOutcomeDescription(path, ability.getApi() == null ? "" : ability.getApi().name(),
                    ability.getMapParams(), choices, ability.getSubAbility() == null ? null
                            : parse(ability.getSubAbility(), path + "/next", ancestors, depth + 1, remaining, bindings), "");
        } finally { ancestors.remove(ability); }
    }
}
