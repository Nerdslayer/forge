package forge.ai.effect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministically combines only the reference variables requested by an evaluation. */
public final class ReferenceCaseCombiner {
    private ReferenceCaseCombiner() {
    }

    public static List<ReferenceCase> combine(final Collection<ReferenceDimension> dimensions) {
        final List<ReferenceDimension> ordered = dimensions.stream()
                .sorted(Comparator.comparing(ReferenceDimension::name))
                .toList();
        final Set<String> names = new HashSet<>();
        for (final ReferenceDimension dimension : ordered) {
            if (!names.add(dimension.name())) {
                throw new IllegalArgumentException("Duplicate reference dimension: " + dimension.name());
            }
        }

        List<ReferenceCase> cases = List.of(new ReferenceCase(Map.of(), 1));
        for (final ReferenceDimension dimension : ordered) {
            final List<ReferenceCase> expanded = new ArrayList<>();
            for (final ReferenceCase base : cases) {
                for (final WeightedValue<?> value : dimension.distribution().entries()) {
                    final Map<String, Object> values = new java.util.LinkedHashMap<>(base.values());
                    values.put(dimension.name(), value.value());
                    expanded.add(new ReferenceCase(values, base.probability() * value.weight()));
                }
            }
            cases = List.copyOf(expanded);
        }
        return cases;
    }
}
