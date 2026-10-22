package forge.ai.combat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Frozen persistent creature protection. No live keyword/validity callbacks enter search. */
public record PreparedCombatProtection(Map<Integer, Recipient> creatures, Map<Integer, Source> sources,
        List<String> unsupportedReasons) {
    public PreparedCombatProtection {
        creatures = Map.copyOf(creatures);
        sources = Map.copyOf(sources);
        unsupportedReasons = List.copyOf(unsupportedReasons);
    }

    public record Recipient(Set<Integer> preventedSources, List<String> qualities) {
        public Recipient { preventedSources = Set.copyOf(preventedSources); qualities = List.copyOf(qualities); }
    }

    /** Matching provenance also invalidates execution when characteristics change but P/T does not. */
    public record Source(int controller, int color, String type, int manaValue) { }

    public boolean prevents(final int source, final int recipient, final CombatEventBatch.Recipient kind) {
        return kind == CombatEventBatch.Recipient.CREATURE && creatures.containsKey(recipient)
                && creatures.get(recipient).preventedSources().contains(source);
    }

    public PreparedCombatProtection surviving(final Set<Integer> losses) {
        final Map<Integer, Recipient> kept = new LinkedHashMap<>();
        creatures.forEach((id, entry) -> {
            if (!losses.contains(id)) {
                kept.put(id, new Recipient(entry.preventedSources().stream().filter(source -> !losses.contains(source))
                        .collect(Collectors.toSet()), entry.qualities()));
            }
        });
        // With no protected recipients, capture also publishes the canonical empty state.
        if (kept.isEmpty() && unsupportedReasons.isEmpty()) { return empty(); }
        final Map<Integer, Source> remaining = new LinkedHashMap<>(sources);
        losses.forEach(remaining::remove);
        return new PreparedCombatProtection(kept, remaining, unsupportedReasons);
    }

    public static PreparedCombatProtection empty() { return new PreparedCombatProtection(Map.of(), Map.of(), List.of()); }
}
