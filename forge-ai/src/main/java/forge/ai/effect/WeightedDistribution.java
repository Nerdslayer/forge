package forge.ai.effect;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Immutable normalized finite distribution used by intrinsic reference evaluation. */
public final class WeightedDistribution<T> {
    private static final double NORMALIZATION_EPSILON = 1e-9;

    private final List<WeightedValue<T>> entries;

    public WeightedDistribution(final List<WeightedValue<T>> entries) {
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("A distribution needs at least one value");
        }
        final double total = entries.stream().mapToDouble(WeightedValue::weight).sum();
        if (!Double.isFinite(total) || total <= 0) {
            throw new IllegalArgumentException("A distribution needs positive finite weight");
        }
        this.entries = entries.stream()
                .filter(entry -> entry.weight() > 0)
                .map(entry -> new WeightedValue<>(entry.value(), entry.weight() / total))
                .toList();
        if (!isNormalized(this.entries)) {
            throw new IllegalArgumentException("Distribution weights did not normalize");
        }
    }

    @SafeVarargs
    public static <T> WeightedDistribution<T> of(final WeightedValue<T>... entries) {
        return new WeightedDistribution<>(List.of(entries));
    }

    public List<WeightedValue<T>> entries() {
        return entries;
    }

    public <R> WeightedDistribution<R> map(final Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return new WeightedDistribution<>(entries.stream()
                .map(entry -> new WeightedValue<>(mapper.apply(entry.value()), entry.weight()))
                .toList());
    }

    private static boolean isNormalized(final List<? extends WeightedValue<?>> values) {
        return Math.abs(values.stream().mapToDouble(WeightedValue::weight).sum() - 1) <= NORMALIZATION_EPSILON;
    }
}
