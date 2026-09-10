package forge.ai.effect;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, moderate-support assumptions for evaluating a card without a live game.
 *
 * <p>The default weights are calibration inputs, not claims about a particular format. A future
 * caller may provide a different model without changing the intrinsic evaluator.</p>
 */
public final class IntrinsicReferenceModel {
    public static final String LIFE_TOTAL = "lifeTotal";
    public static final String HAND_SIZE = "handSize";
    public static final String AVAILABLE_MANA = "availableMana";
    public static final String FRIENDLY_CREATURE_COUNT = "friendlyCreatureCount";
    public static final String OPPOSING_CREATURE_COUNT = "opposingCreatureCount";
    public static final String CREATURE_PROFILE = "creatureProfile";
    public static final String PERMANENT_PROFILE = "permanentProfile";

    public enum PermanentKind {
        PLAYER, CREATURE, PERMANENT, ARTIFACT, ENCHANTMENT, PLANESWALKER, LAND, TOKEN
    }

    public enum EventType {
        ATTACK, COMBAT_DAMAGE, SPELL_CAST, CREATURE_DIED, PERMANENT_SACRIFICED,
        TOKEN_CREATED, COUNTER_ADDED, TAPPED
    }

    public record CreatureProfile(boolean present, int power, int toughness,
            Set<String> keywords, boolean hexproof, boolean indestructible) {
        public CreatureProfile {
            if (power < 0 || toughness < 0) {
                throw new IllegalArgumentException("Reference creature size must be nonnegative");
            }
            keywords = keywords == null ? Set.of() : Set.copyOf(keywords);
        }

        public static CreatureProfile absent() {
            return new CreatureProfile(false, 0, 0, Set.of(), false, false);
        }
    }

    public record PermanentProfile(boolean present, PermanentKind kind, boolean controlledByAi,
            int power, int toughness, Set<String> keywords) {
        public PermanentProfile {
            if (kind == null) {
                throw new IllegalArgumentException("Reference permanent needs a kind");
            }
            if (power < 0 || toughness < 0) {
                throw new IllegalArgumentException("Reference permanent size must be nonnegative");
            }
            keywords = keywords == null ? Set.of() : Set.copyOf(keywords);
        }

        public static PermanentProfile absent() {
            return new PermanentProfile(false, PermanentKind.PERMANENT, false,
                    0, 0, Set.of());
        }
    }

    private final WeightedDistribution<Integer> lifeTotals;
    private final WeightedDistribution<Integer> handSizes;
    private final WeightedDistribution<Integer> availableMana;
    private final WeightedDistribution<Integer> friendlyCreatureCounts;
    private final WeightedDistribution<Integer> opposingCreatureCounts;
    private final WeightedDistribution<CreatureProfile> creatureProfiles;
    private final WeightedDistribution<PermanentProfile> permanentProfiles;
    private final Map<PermanentKind, WeightedDistribution<Boolean>> targetAvailability;
    private final Map<EventType, WeightedDistribution<Double>> eventRates;

    public IntrinsicReferenceModel(final WeightedDistribution<Integer> lifeTotals,
            final WeightedDistribution<Integer> handSizes,
            final WeightedDistribution<Integer> availableMana,
            final WeightedDistribution<Integer> friendlyCreatureCounts,
            final WeightedDistribution<Integer> opposingCreatureCounts,
            final WeightedDistribution<CreatureProfile> creatureProfiles,
            final WeightedDistribution<PermanentProfile> permanentProfiles,
            final Map<PermanentKind, WeightedDistribution<Boolean>> targetAvailability,
            final Map<EventType, WeightedDistribution<Double>> eventRates) {
        this.lifeTotals = require(lifeTotals, "lifeTotals");
        this.handSizes = require(handSizes, "handSizes");
        this.availableMana = require(availableMana, "availableMana");
        this.friendlyCreatureCounts = require(friendlyCreatureCounts, "friendlyCreatureCounts");
        this.opposingCreatureCounts = require(opposingCreatureCounts, "opposingCreatureCounts");
        this.creatureProfiles = require(creatureProfiles, "creatureProfiles");
        this.permanentProfiles = require(permanentProfiles, "permanentProfiles");
        this.targetAvailability = copyAvailability(targetAvailability);
        this.eventRates = copyEventRates(eventRates);
    }

    public static IntrinsicReferenceModel defaults() {
        final Map<PermanentKind, WeightedDistribution<Boolean>> availability = new EnumMap<>(PermanentKind.class);
        availability.put(PermanentKind.PLAYER, booleanDistribution(1.0, 0));
        availability.put(PermanentKind.CREATURE, booleanDistribution(0.65, 0.35));
        availability.put(PermanentKind.PERMANENT, booleanDistribution(0.75, 0.25));
        availability.put(PermanentKind.ARTIFACT, booleanDistribution(0.35, 0.65));
        availability.put(PermanentKind.ENCHANTMENT, booleanDistribution(0.30, 0.70));
        availability.put(PermanentKind.PLANESWALKER, booleanDistribution(0.10, 0.90));
        availability.put(PermanentKind.LAND, booleanDistribution(0.55, 0.45));
        availability.put(PermanentKind.TOKEN, booleanDistribution(0.50, 0.50));

        final Map<EventType, WeightedDistribution<Double>> events = new EnumMap<>(EventType.class);
        events.put(EventType.ATTACK, rateDistribution(0, .20, .5, .50, 1, .30));
        events.put(EventType.COMBAT_DAMAGE, rateDistribution(0, .30, .5, .50, 1, .20));
        events.put(EventType.SPELL_CAST, rateDistribution(0, .10, 1, .50, 2, .30, 3, .10));
        events.put(EventType.CREATURE_DIED, rateDistribution(0, .25, 1, .50, 2, .20, 3, .05));
        events.put(EventType.PERMANENT_SACRIFICED, rateDistribution(0, .50, 1, .40, 2, .10));
        events.put(EventType.TOKEN_CREATED, rateDistribution(0, .30, 1, .50, 2, .20));
        events.put(EventType.COUNTER_ADDED, rateDistribution(0, .40, 1, .45, 2, .15));
        events.put(EventType.TAPPED, rateDistribution(0, .20, 1, .55, 2, .25));

        return new IntrinsicReferenceModel(
                lifeDistribution(),
                integerDistribution(0, .05, 1, .10, 2, .15, 3, .20, 4, .20, 5, .15, 6, .10, 7, .05),
                integerDistribution(0, .10, 1, .15, 2, .20, 3, .20, 4, .15, 5, .10, 6, .05, 7, .05),
                integerDistribution(0, .20, 1, .25, 2, .25, 3, .15, 4, .10, 5, .05),
                integerDistribution(0, .20, 1, .25, 2, .25, 3, .15, 4, .10, 5, .05),
                WeightedDistribution.of(
                        new WeightedValue<>(CreatureProfile.absent(), .20),
                        new WeightedValue<>(new CreatureProfile(true, 1, 1, Set.of(), false, false), .20),
                        new WeightedValue<>(new CreatureProfile(true, 2, 2, Set.of(), false, false), .25),
                        new WeightedValue<>(new CreatureProfile(true, 3, 3, Set.of(), false, false), .20),
                        new WeightedValue<>(new CreatureProfile(true, 4, 4, Set.of(), false, false), .10),
                        new WeightedValue<>(new CreatureProfile(true, 6, 6, Set.of(), true, false), .05)),
                WeightedDistribution.of(
                        new WeightedValue<>(PermanentProfile.absent(), .15),
                        new WeightedValue<>(new PermanentProfile(true, PermanentKind.CREATURE,
                                false, 3, 3, Set.of()), .35),
                        new WeightedValue<>(new PermanentProfile(true, PermanentKind.ARTIFACT,
                                false, 0, 0, Set.of()), .15),
                        new WeightedValue<>(new PermanentProfile(true, PermanentKind.ENCHANTMENT,
                                false, 0, 0, Set.of()), .15),
                        new WeightedValue<>(new PermanentProfile(true, PermanentKind.PLANESWALKER,
                                false, 0, 0, Set.of()), .05),
                        new WeightedValue<>(new PermanentProfile(true, PermanentKind.LAND,
                                false, 0, 0, Set.of()), .15)),
                availability,
                events);
    }

    public WeightedDistribution<Integer> lifeTotals() {
        return lifeTotals;
    }

    public WeightedDistribution<Integer> handSizes() {
        return handSizes;
    }

    public WeightedDistribution<Integer> availableMana() {
        return availableMana;
    }

    public WeightedDistribution<Integer> friendlyCreatureCounts() {
        return friendlyCreatureCounts;
    }

    public WeightedDistribution<Integer> opposingCreatureCounts() {
        return opposingCreatureCounts;
    }

    public WeightedDistribution<CreatureProfile> creatureProfiles() {
        return creatureProfiles;
    }

    public WeightedDistribution<PermanentProfile> permanentProfiles() {
        return permanentProfiles;
    }

    public WeightedDistribution<Boolean> targetAvailability(final PermanentKind kind) {
        return targetAvailability.get(kind);
    }

    public WeightedDistribution<Double> eventRates(final EventType type) {
        return eventRates.get(type);
    }

    private static WeightedDistribution<Integer> lifeDistribution() {
        return integerDistribution(1, .005, 2, .01, 3, .015, 4, .02,
                5, .05, 10, .20, 15, .30, 20, .40);
    }

    private static WeightedDistribution<Boolean> booleanDistribution(final double present,
            final double absent) {
        return WeightedDistribution.of(new WeightedValue<>(true, present),
                new WeightedValue<>(false, absent));
    }

    private static WeightedDistribution<Double> rateDistribution(final double... valuesAndWeights) {
        if (valuesAndWeights.length % 2 != 0) {
            throw new IllegalArgumentException("Rate values and weights must be paired");
        }
        final List<WeightedValue<Double>> values = new java.util.ArrayList<>();
        for (int i = 0; i < valuesAndWeights.length; i += 2) {
            values.add(new WeightedValue<>(valuesAndWeights[i], valuesAndWeights[i + 1]));
        }
        return new WeightedDistribution<>(values);
    }

    private static WeightedDistribution<Integer> integerDistribution(final double... valuesAndWeights) {
        if (valuesAndWeights.length % 2 != 0) {
            throw new IllegalArgumentException("Integer values and weights must be paired");
        }
        final List<WeightedValue<Integer>> values = new java.util.ArrayList<>();
        for (int i = 0; i < valuesAndWeights.length; i += 2) {
            values.add(new WeightedValue<>((int) valuesAndWeights[i], valuesAndWeights[i + 1]));
        }
        return new WeightedDistribution<>(values);
    }

    private static <T> T require(final T value, final String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static Map<PermanentKind, WeightedDistribution<Boolean>> copyAvailability(
            final Map<PermanentKind, WeightedDistribution<Boolean>> source) {
        if (source == null) {
            throw new IllegalArgumentException("targetAvailability is required");
        }
        final Map<PermanentKind, WeightedDistribution<Boolean>> copy = new EnumMap<>(PermanentKind.class);
        source.forEach((kind, distribution) -> copy.put(require(kind, "target kind"),
                require(distribution, "target availability")));
        return Map.copyOf(copy);
    }

    private static Map<EventType, WeightedDistribution<Double>> copyEventRates(
            final Map<EventType, WeightedDistribution<Double>> source) {
        if (source == null) {
            throw new IllegalArgumentException("eventRates is required");
        }
        final Map<EventType, WeightedDistribution<Double>> copy = new EnumMap<>(EventType.class);
        source.forEach((type, distribution) -> copy.put(require(type, "event type"),
                require(distribution, "event rate")));
        return Map.copyOf(copy);
    }
}
