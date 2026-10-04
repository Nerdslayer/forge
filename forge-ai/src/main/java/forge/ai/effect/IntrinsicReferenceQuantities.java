package forge.ai.effect;

import java.util.EnumMap;
import java.util.Map;

/** Independent, moderate-support calibration inputs; never inferred from hidden live cards. */
public final class IntrinsicReferenceQuantities {
    public enum Quantity { P1P1_COUNTERS, LEVEL_COUNTERS, GRAVEYARD_CARDS, GRAVEYARD_CREATURES,
        OPPONENT_GRAVEYARD_CARDS, OPPONENT_GRAVEYARD_CREATURES, X_PAID, LANDS_CONTROLLED, STARTING_LIFE,
        DYING_CREATURE_IS_TOKEN, RECEIVED_DAMAGE_AMOUNT, ATTACKING_CREATURES, CAST_SPELL_MANA_VALUE,
        EVENT_CASTER_IS_OPPONENT, SELF_TARGETING_CASTER_IS_OPPONENT, WATCHED_CREATURE_IS_OPPONENT }

    private final Map<Quantity, WeightedDistribution<Integer>> distributions;

    public IntrinsicReferenceQuantities(final Map<Quantity, WeightedDistribution<Integer>> distributions) {
        final Map<Quantity, WeightedDistribution<Integer>> copy = new EnumMap<>(Quantity.class);
        for (final Quantity quantity : Quantity.values()) {
            final WeightedDistribution<Integer> distribution = distributions.get(quantity);
            if (distribution == null || distribution.entries().stream().anyMatch(entry -> entry.value() < 0)) {
                throw new IllegalArgumentException("Nonnegative reference distribution required: " + quantity);
            }
            if ((quantity == Quantity.DYING_CREATURE_IS_TOKEN || quantity == Quantity.EVENT_CASTER_IS_OPPONENT
                    || quantity == Quantity.SELF_TARGETING_CASTER_IS_OPPONENT || quantity == Quantity.WATCHED_CREATURE_IS_OPPONENT)
                    && distribution.entries().stream().anyMatch(entry -> entry.value() > 1)) {
                throw new IllegalArgumentException("Reference status must be zero or one");
            }
            copy.put(quantity, distribution);
        }
        this.distributions = Map.copyOf(copy);
    }

    public WeightedDistribution<Integer> distribution(final Quantity quantity) {
        return distributions.get(quantity);
    }

    public IntrinsicReferenceQuantities with(final Quantity quantity,
            final WeightedDistribution<Integer> distribution) {
        final Map<Quantity, WeightedDistribution<Integer>> copy = new EnumMap<>(distributions);
        copy.put(quantity, distribution);
        return new IntrinsicReferenceQuantities(copy);
    }

    public static IntrinsicReferenceQuantities defaults() {
        // Small values dominate, including zero. These assume modest support, not a combo deck.
        // TODO: Add distinct counter types, tribal populations and conditional/event quantities
        // as their consumers are implemented; do not substitute P1P1 for arbitrary counters.
        return new IntrinsicReferenceQuantities(Map.ofEntries(
                Map.entry(Quantity.P1P1_COUNTERS, distribution(0, .35, 1, .25, 2, .20, 3, .12, 5, .06, 8, .02)),
                Map.entry(Quantity.LEVEL_COUNTERS, distribution(0, .40, 1, .25, 2, .20, 3, .10, 4, .04, 6, .01)),
                Map.entry(Quantity.GRAVEYARD_CARDS, distribution(0, .15, 2, .25, 5, .30, 8, .20, 12, .10)),
                Map.entry(Quantity.GRAVEYARD_CREATURES, distribution(0, .20, 1, .30, 2, .25, 4, .20, 6, .05)),
                Map.entry(Quantity.OPPONENT_GRAVEYARD_CARDS, distribution(0, .15, 2, .25, 5, .30, 8, .20, 12, .10)),
                Map.entry(Quantity.OPPONENT_GRAVEYARD_CREATURES, distribution(0, .20, 1, .30, 2, .25, 4, .20, 6, .05)),
                Map.entry(Quantity.X_PAID, distribution(0, .15, 1, .25, 2, .30, 3, .20, 5, .10)),
                Map.entry(Quantity.LANDS_CONTROLLED, distribution(0, .02, 2, .08, 3, .20, 4, .30, 5, .25, 7, .15)),
                // Generic 1v1 reference. Callers can replace this for other game formats.
                Map.entry(Quantity.STARTING_LIFE, distribution(20, 1)),
                // A battlefield event prior, not the library composition (tokens are not cards).
                Map.entry(Quantity.DYING_CREATURE_IS_TOKEN, distribution(0, .65, 1, .35)),
                // Event consumers condition on positive damage; zero cannot produce this trigger.
                Map.entry(Quantity.RECEIVED_DAMAGE_AMOUNT, distribution(0, .05, 1, .20, 2, .30, 3, .25, 5, .15, 8, .05)),
                // Condition on positive count when a declaration actually occurred. The separate
                // attack-event occurrence prior already accounts for turns with no attack.
                Map.entry(Quantity.ATTACKING_CREATURES, distribution(0, .15, 1, .35, 2, .30, 3, .15, 5, .05)),
                // Conditional on casting a spell, not the mana value of a random library card.
                // Curve-weighted ordinary spells dominate; expensive spells still occur.
                Map.entry(Quantity.CAST_SPELL_MANA_VALUE, distribution(0, .03, 1, .20, 2, .30, 3, .22,
                        4, .12, 5, .07, 6, .04, 8, .02)),
                // Conditional recipient identity, not additional event opportunities.
                Map.entry(Quantity.EVENT_CASTER_IS_OPPONENT, distribution(0, .50, 1, .50)),
                // A permanent targets itself less often than opponents target it with removal;
                // modest friendly support still allows buffs/protection. Calibratable, not hidden information.
                Map.entry(Quantity.SELF_TARGETING_CASTER_IS_OPPONENT, distribution(0, .25, 1, .75)),
                // Conditional on an unrestricted watched creature event, not a second event rate.
                Map.entry(Quantity.WATCHED_CREATURE_IS_OPPONENT, distribution(0, .50, 1, .50))));
    }

    private static WeightedDistribution<Integer> distribution(final double... valuesAndWeights) {
        final java.util.List<WeightedValue<Integer>> entries = new java.util.ArrayList<>();
        for (int i = 0; i < valuesAndWeights.length; i += 2) {
            entries.add(new WeightedValue<>((int) valuesAndWeights[i], valuesAndWeights[i + 1]));
        }
        return new WeightedDistribution<>(entries);
    }
}
