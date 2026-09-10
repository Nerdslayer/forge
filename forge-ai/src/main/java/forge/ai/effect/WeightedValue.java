package forge.ai.effect;

import java.util.Objects;

/** One value and its nonnegative probability weight in a reference distribution. */
public record WeightedValue<T>(T value, double weight) {
    public WeightedValue {
        Objects.requireNonNull(value, "value");
        if (!Double.isFinite(weight) || weight < 0) {
            throw new IllegalArgumentException("Weight must be finite and nonnegative");
        }
    }
}
