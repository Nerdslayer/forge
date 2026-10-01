package forge.ai.combat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Combined source-loss variants prepared once; no live objects or evaluator callbacks survive. */
public record PreparedCombatStaticWorlds(boolean supported, Set<Integer> providers,
        Map<Set<Integer>, Map<Integer, CreatureState>> worlds, List<String> reasons) {
    public PreparedCombatStaticWorlds {
        providers = Set.copyOf(providers);
        final Map<Set<Integer>, Map<Integer, CreatureState>> frozen = new LinkedHashMap<>();
        worlds.forEach((key, value) -> frozen.put(Set.copyOf(key), Map.copyOf(value)));
        worlds = Map.copyOf(frozen);
        reasons = List.copyOf(reasons);
    }

    public record CreatureState(int power, int toughness, int combatDamage, int bodyDelta) { }

    public Map<Integer, CreatureState> afterLosses(final Set<Integer> losses) {
        if (!supported) { throw new IllegalStateException("Unsupported static projection"); }
        final Set<Integer> key = losses.stream().filter(providers::contains).collect(java.util.stream.Collectors.toSet());
        final var world = worlds.get(key);
        if (world == null) { throw new IllegalStateException("Missing combined static-loss variant"); }
        return world;
    }

    public static PreparedCombatStaticWorlds unsupported(final String reason) {
        return new PreparedCombatStaticWorlds(false, Set.of(), Map.of(), List.of(reason));
    }

    public static PreparedCombatStaticWorlds empty() {
        return new PreparedCombatStaticWorlds(true, Set.of(), Map.of(Set.of(), Map.of()), List.of());
    }

    /** Rebases both mechanics and body deltas after one combat, without any live recalculation. */
    public PreparedCombatStaticWorlds surviving(final Set<Integer> losses) {
        final var baseline = afterLosses(losses);
        final Set<Integer> removed = losses.stream().filter(providers::contains).collect(java.util.stream.Collectors.toSet());
        final Set<Integer> kept = providers.stream().filter(id -> !removed.contains(id)).collect(java.util.stream.Collectors.toSet());
        final Map<Set<Integer>, Map<Integer, CreatureState>> rebased = new LinkedHashMap<>();
        worlds.forEach((key, world) -> {
            if (!key.containsAll(removed)) { return; }
            final Set<Integer> nextKey = key.stream().filter(kept::contains).collect(java.util.stream.Collectors.toSet());
            final Map<Integer, CreatureState> nextWorld = new LinkedHashMap<>();
            world.forEach((id, state) -> {
                if (!losses.contains(id)) {
                    nextWorld.put(id, new CreatureState(state.power(), state.toughness(), state.combatDamage(),
                            CombatOutcomePredictor.add(state.bodyDelta(), -baseline.get(id).bodyDelta())));
                }
            });
            rebased.put(nextKey, nextWorld);
        });
        return new PreparedCombatStaticWorlds(true, kept, rebased, reasons);
    }
}
