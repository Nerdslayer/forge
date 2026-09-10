package forge.ai.effect;

import java.util.Objects;

/** A named reference variable requested by one intrinsic evaluation. */
public record ReferenceDimension(String name, WeightedDistribution<?> distribution) {
    public ReferenceDimension {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Reference dimension needs a name");
        }
        Objects.requireNonNull(distribution, "distribution");
    }
}
