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
    private final WeightedDistribution<Integer> remainingCards;
    private final WeightedDistribution<Integer> nonlandManaValues;

    public IntrinsicLibraryReference(final Map<String, Double> primaryTypes, final double tribalCreatureShare) {
        this(primaryTypes, tribalCreatureShare, .60, .80);
    }

    public IntrinsicLibraryReference(final Map<String, Double> primaryTypes, final double tribalCreatureShare,
            final double usefulNextCardProbability, final double nextDrawRealization) {
        this(primaryTypes, tribalCreatureShare, usefulNextCardProbability, nextDrawRealization,
                WeightedDistribution.of(new WeightedValue<>(0, .01), new WeightedValue<>(10, .14),
                        new WeightedValue<>(20, .35), new WeightedValue<>(40, .50)));
    }

    public IntrinsicLibraryReference(final Map<String, Double> primaryTypes, final double tribalCreatureShare,
            final double usefulNextCardProbability, final double nextDrawRealization,
            final WeightedDistribution<Integer> remainingCards) {
        this(primaryTypes, tribalCreatureShare, usefulNextCardProbability, nextDrawRealization, remainingCards,
                WeightedDistribution.of(new WeightedValue<>(0, .05), new WeightedValue<>(1, .20),
                        new WeightedValue<>(2, .30), new WeightedValue<>(3, .22), new WeightedValue<>(4, .12),
                        new WeightedValue<>(5, .07), new WeightedValue<>(6, .03), new WeightedValue<>(8, .01)));
    }

    public IntrinsicLibraryReference(final Map<String, Double> primaryTypes, final double tribalCreatureShare,
            final double usefulNextCardProbability, final double nextDrawRealization,
            final WeightedDistribution<Integer> remainingCards, final WeightedDistribution<Integer> nonlandManaValues) {
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
        if (remainingCards == null || remainingCards.entries().stream().anyMatch(entry -> entry.value() < 0 || entry.value() > 64)) {
            throw new IllegalArgumentException("Remaining library sizes must be between zero and 64");
        }
        this.remainingCards = remainingCards;
        if (nonlandManaValues == null || nonlandManaValues.entries().stream().anyMatch(entry -> entry.value() < 0)) {
            throw new IllegalArgumentException("Nonnegative nonland mana values required");
        }
        // Library/hand printed values are independent of type; this is not the cast-spell
        // event distribution (which can include X paid). Lands always have mana value zero.
        this.nonlandManaValues = nonlandManaValues;
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

    /** An independent reference snapshot, not a fixed assumption that a tutor always finds a card. */
    public java.util.Optional<WeightedDistribution<Integer>> searchCounts(final String validity, final int selectionLimit) {
        final OptionalDouble hit = hitProbability(validity);
        if (hit.isEmpty()) { return java.util.Optional.empty(); }
        final Map<Integer, Double> probabilities = new java.util.TreeMap<>();
        for (final var remaining : remainingCards.entries()) {
            for (final var count : selectedCounts(remaining.value(), selectionLimit, hit.getAsDouble()).entries()) {
                probabilities.merge(count.value(), remaining.weight() * count.weight(), Double::sum);
            }
        }
        // TODO: Tutor card quality, depletion across repeated searches, actual deck composition,
        // shuffle/order effects and named/secondary-type predicates need richer library state.
        return java.util.Optional.of(new WeightedDistribution<>(probabilities.entrySet().stream()
                .map(entry -> new WeightedValue<>(entry.getKey(), entry.getValue())).toList()));
    }

    public static IntrinsicLibraryReference defaults() {
        return new IntrinsicLibraryReference(Map.of("Land", .40, "Creature", .30, "Artifact", .06,
                "Enchantment", .06, "Planeswalker", .02, "Instant", .08, "Sorcery", .08), .50);
    }

    public OptionalDouble hitProbability(final String validity) {
        final var parsed = IntrinsicManaValueFilter.parse(validity);
        if (parsed.isEmpty()) { return OptionalDouble.empty(); }
        // Validate every branch, even when its mana-value constraint has zero probability.
        for (final var branch : parsed.get()) {
            if (typeProbability(branch.types(), IntrinsicPrimaryTypeFilter.TYPES).isEmpty()) { return OptionalDouble.empty(); }
        }
        double result = 0;
        for (final var mana : nonlandManaValues.entries()) {
            result += mana.weight() * hitProbabilityAtMana(parsed.get(), mana.value(), nonlandTypes());
        }
        result += hitProbabilityAtMana(parsed.get(), 0, Set.of("Land"));
        return OptionalDouble.of(result > 1 - 1e-12 ? 1 : Math.min(1, result));
    }

    /** Unnormalized nonland type mass at a known mana value, used to correlate counter value and eligibility. */
    OptionalDouble spellTypeProbabilityAtMana(final String validity, final int manaValue) {
        final var parsed = IntrinsicManaValueFilter.parse(validity);
        if (parsed.isEmpty()) { return OptionalDouble.empty(); }
        for (final var branch : parsed.get()) {
            if (typeProbability(branch.types(), IntrinsicPrimaryTypeFilter.TYPES).isEmpty()) { return OptionalDouble.empty(); }
        }
        return OptionalDouble.of(hitProbabilityAtMana(parsed.get(), manaValue, nonlandTypes()));
    }

    private static Set<String> nonlandTypes() {
        return Set.of("Creature", "Artifact", "Enchantment", "Planeswalker", "Instant", "Sorcery");
    }

    private double hitProbabilityAtMana(final java.util.List<IntrinsicManaValueFilter.Branch> branches,
            final int manaValue, final Set<String> allowedTypes) {
        final String selected = branches.stream().filter(branch -> branch.matches(manaValue))
                .map(IntrinsicManaValueFilter.Branch::types).collect(java.util.stream.Collectors.joining(","));
        return selected.isEmpty() ? 0 : typeProbability(selected, allowedTypes).orElseThrow();
    }

    private OptionalDouble typeProbability(final String validity, final Set<String> allowedTypes) {
        final Set<String> included = new java.util.HashSet<>();
        final Set<String> tribes = new java.util.HashSet<>();
        boolean aura = false;
        for (final String raw : validity.split(",", -1)) {
            final String filter = raw.trim();
            final var primary = IntrinsicPrimaryTypeFilter.parseBranch(filter);
            if (primary.isPresent()) { included.addAll(primary.get()); }
            else if ("Aura".equals(filter)) { aura = true; }
            else {
                final String tribe = filter.startsWith("Creature.") ? filter.substring(9) : filter;
                if (!CardType.isACreatureType(tribe)) { return OptionalDouble.empty(); }
                tribes.add(tribe);
            }
        }
        double probability = included.stream().filter(allowedTypes::contains).mapToDouble(primaryTypes::get).sum();
        if (allowedTypes.contains("Creature") && !included.contains("Creature")) {
            probability += primaryTypes.get("Creature") * (1 - Math.pow(1 - tribalCreatureShare, tribes.size()));
        }
        if (allowedTypes.contains("Enchantment") && aura && !included.contains("Enchantment")) { probability += primaryTypes.get("Enchantment") * .50; }
        // TODO: Multi-type intersections, color predicates, named cards and actual deck
        // composition. Primary types are coarse disjoint buckets; tribal memberships are independent
        // within the creature bucket, not independent of being a creature.
        return OptionalDouble.of(Math.min(1, probability));
    }

    /** Distribution of min(matches, selectionLimit), preserving zero-hit probability. */
    public static WeightedDistribution<Integer> selectedCounts(final int lookedAt, final int selectionLimit, final double hitProbability) {
        if (lookedAt < 0 || lookedAt > 64 || selectionLimit < 0 || selectionLimit > 32
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
