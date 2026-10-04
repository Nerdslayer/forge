package forge.ai.effect;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import forge.card.CardType;

/** Coarse deck-independent library composition, with modest tribal support and no hidden cards. */
public final class IntrinsicLibraryReference {
    private final Map<String, Double> primaryTypes;
    private final double tribalCreatureShare;
    private final double usefulNextCardProbability;
    private final double nextDrawRealization;

    public IntrinsicLibraryReference(final Map<String, Double> primaryTypes, final double tribalCreatureShare) {
        this(primaryTypes, tribalCreatureShare, .60, .80);
    }

    public IntrinsicLibraryReference(final Map<String, Double> primaryTypes, final double tribalCreatureShare,
            final double usefulNextCardProbability, final double nextDrawRealization) {
        if (!primaryTypes.keySet().equals(Set.of("Land", "Creature", "Artifact", "Enchantment", "Planeswalker", "Instant", "Sorcery"))
                || primaryTypes.values().stream().anyMatch(value -> !Double.isFinite(value) || value < 0)
                || Math.abs(primaryTypes.values().stream().mapToDouble(Double::doubleValue).sum() - 1) > 1e-9
                || !probability(tribalCreatureShare) || !probability(usefulNextCardProbability) || !probability(nextDrawRealization)) {
            throw new IllegalArgumentException("A normalized primary-type distribution and tribal share are required");
        }
        this.primaryTypes = Map.copyOf(primaryTypes);
        this.tribalCreatureShare = tribalCreatureShare;
        this.usefulNextCardProbability = usefulNextCardProbability;
        this.nextDrawRealization = nextDrawRealization;
    }

    private static boolean probability(final double value) { return Double.isFinite(value) && value >= 0 && value <= 1; }

    /** Expected improvement to the next draw, not a card actually added to the hand. */
    public double filteringCardFraction(final int lookedAt) {
        if (lookedAt < 0) { throw new IllegalArgumentException("Nonnegative filtering amount required"); }
        final double bad = 1 - usefulNextCardProbability;
        return nextDrawRealization * bad * (1 - Math.pow(bad, lookedAt));
    }

    /** Modest support for a requested creature tribe, conditional on a creature opportunity. */
    public double tribalCreatureShare() { return tribalCreatureShare; }

    public static IntrinsicLibraryReference defaults() {
        return new IntrinsicLibraryReference(Map.of("Land", .40, "Creature", .30, "Artifact", .06,
                "Enchantment", .06, "Planeswalker", .02, "Instant", .08, "Sorcery", .08), .50);
    }

    public OptionalDouble hitProbability(final String validity) {
        final Set<String> included = new java.util.HashSet<>();
        final Set<String> tribes = new java.util.HashSet<>();
        boolean any = false;
        boolean aura = false;
        for (final String raw : validity.split(",", -1)) {
            final String filter = raw.trim();
            if ("Card".equals(filter)) { any = true; }
            else if (primaryTypes.containsKey(filter)) { included.add(filter); }
            else if ("Permanent".equals(filter)) {
                included.addAll(Set.of("Land", "Creature", "Artifact", "Enchantment", "Planeswalker"));
            } else if ("Aura".equals(filter)) { aura = true; }
            else {
                final String tribe = filter.startsWith("Creature.") ? filter.substring(9) : filter;
                if (!CardType.isACreatureType(tribe)) { return OptionalDouble.empty(); }
                tribes.add(tribe);
            }
        }
        if (any) { return OptionalDouble.of(1); }
        double probability = included.stream().mapToDouble(primaryTypes::get).sum();
        if (!included.contains("Creature")) {
            probability += primaryTypes.get("Creature") * (1 - Math.pow(1 - tribalCreatureShare, tribes.size()));
        }
        if (aura && !included.contains("Enchantment")) { probability += primaryTypes.get("Enchantment") * .50; }
        // TODO: Multi-type intersections, mana-value/color predicates, named cards and actual deck
        // composition. Primary types are coarse disjoint buckets; tribal memberships are independent
        // within the creature bucket, not independent of being a creature.
        return OptionalDouble.of(Math.min(1, probability));
    }

    /** Distribution of min(matches, selectionLimit), preserving zero-hit probability. */
    public static WeightedDistribution<Integer> selectedCounts(final int lookedAt, final int selectionLimit, final double hitProbability) {
        if (lookedAt < 0 || lookedAt > 32 || selectionLimit < 0 || selectionLimit > 32
                || !Double.isFinite(hitProbability) || hitProbability < 0 || hitProbability > 1) {
            throw new IllegalArgumentException("Bounded library selection required");
        }
        final int limit = Math.min(lookedAt, selectionLimit);
        double[] probabilities = new double[limit + 1];
        probabilities[0] = 1;
        for (int card = 0; card < lookedAt; card++) {
            final double[] next = new double[limit + 1];
            for (int count = 0; count <= limit; count++) {
                next[count] += probabilities[count] * (1 - hitProbability);
                next[Math.min(limit, count + 1)] += probabilities[count] * hitProbability;
            }
            probabilities = next;
        }
        final java.util.List<WeightedValue<Integer>> result = new java.util.ArrayList<>();
        for (int count = 0; count <= limit; count++) {
            if (probabilities[count] > 0) { result.add(new WeightedValue<>(count, probabilities[count])); }
        }
        return new WeightedDistribution<>(result);
    }
}
