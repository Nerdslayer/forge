package forge.ai.effect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One deterministic, weighted combination of requested reference variables. */
public record ReferenceCase(Map<String, Object> values, double probability) {
    public ReferenceCase {
        Objects.requireNonNull(values, "values");
        if (!Double.isFinite(probability) || probability < 0) {
            throw new IllegalArgumentException("Case probability must be finite and nonnegative");
        }
        values = Map.copyOf(new LinkedHashMap<>(values));
    }

    public <T> T value(final String name, final Class<T> type) {
        return type.cast(values.get(name));
    }
}
