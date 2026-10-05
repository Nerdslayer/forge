package forge.ai.effect;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Literal card-type predicates over the intrinsic model's disjoint primary-type buckets. */
final class IntrinsicPrimaryTypeFilter {
    static final Set<String> TYPES = Set.of("Land", "Creature", "Artifact", "Enchantment", "Planeswalker", "Instant", "Sorcery");
    private static final Set<String> PERMANENTS = Set.of("Land", "Creature", "Artifact", "Enchantment", "Planeswalker");

    private IntrinsicPrimaryTypeFilter() { }

    static Optional<Set<String>> parseBranch(final String validity) {
        if (validity == null) { return Optional.empty(); }
        final String[] parts = validity.trim().split("[.+]", -1);
        final Set<String> types = new HashSet<>();
        String positive = null;
        switch (parts[0]) {
        case "Card" -> types.addAll(TYPES);
        case "Permanent" -> types.addAll(PERMANENTS);
        default -> {
            if (!TYPES.contains(parts[0])) { return Optional.empty(); }
            positive = parts[0];
            types.add(positive);
        }
        }
        for (int i = 1; i < parts.length; i++) {
            final String part = "nonland".equals(parts[i]) ? "nonLand" : parts[i];
            if (TYPES.contains(part)) {
                // Artifact creatures are real, but absent from the disjoint reference buckets.
                // Do not call an unmodeled secondary-type intersection known impossible.
                if (positive != null && !positive.equals(part)) { return Optional.empty(); }
                positive = part;
                types.retainAll(Set.of(part));
            } else if (part.startsWith("non") && TYPES.contains(part.substring(3))) {
                types.remove(part.substring(3));
            } else if ("nonPermanent".equals(part)) {
                types.removeAll(PERMANENTS);
            } else { return Optional.empty(); }
        }
        // TODO: Secondary types, subtype/color predicates and controller/owner
        // scope require richer reference cases or separate event/recipient binding.
        return Optional.of(Set.copyOf(types));
    }
}
