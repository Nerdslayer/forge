package forge.ai.effect;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Cumulative probability that an intrinsic permanent remains available at each checkpoint. */
public record PermanentSurvivalEstimate(Map<SurvivalCheckpoint, Double> probabilities) {
    public PermanentSurvivalEstimate {
        Objects.requireNonNull(probabilities, "probabilities");
        final EnumMap<SurvivalCheckpoint, Double> copy = new EnumMap<>(SurvivalCheckpoint.class);
        for (final SurvivalCheckpoint checkpoint : SurvivalCheckpoint.values()) {
            final Double probability = probabilities.get(checkpoint);
            if (probability == null || !Double.isFinite(probability)
                    || probability < 0 || probability > 1) {
                throw new IllegalArgumentException("Invalid survival probability for " + checkpoint);
            }
            copy.put(checkpoint, probability);
        }
        double previous = 1;
        for (final SurvivalCheckpoint checkpoint : SurvivalCheckpoint.values()) {
            final double current = copy.get(checkpoint);
            if (current > previous + 0.000000001) {
                throw new IllegalArgumentException("Survival probabilities must be cumulative");
            }
            previous = current;
        }
        probabilities = Map.copyOf(copy);
    }

    public double probability(final SurvivalCheckpoint checkpoint) {
        return probabilities.get(Objects.requireNonNull(checkpoint, "checkpoint"));
    }
}
