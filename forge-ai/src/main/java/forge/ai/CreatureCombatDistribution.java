package forge.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Full fixed-P/T marginals used to estimate creature combat thresholds.
 * TODO: Weight creature profiles by play frequency and add custom/generated token profiles when
 * those data sources become available.
 */
final class CreatureCombatDistribution {
    private static final CreatureCombatDistribution INSTANCE = load();

    private final Map<Integer, Long> powerCounts;
    private final Map<Integer, Long> toughnessCounts;
    private final Map<Integer, Long> positivePowerCounts;
    private final long creatureCount;
    private final long positivePowerCount;

    private CreatureCombatDistribution(final Map<Integer, Long> powerCounts,
            final Map<Integer, Long> toughnessCounts, final Map<Integer, Long> positivePowerCounts,
            final long creatureCount, final long positivePowerCount) {
        this.powerCounts = Map.copyOf(powerCounts);
        this.toughnessCounts = Map.copyOf(toughnessCounts);
        this.positivePowerCounts = Map.copyOf(positivePowerCounts);
        this.creatureCount = creatureCount;
        this.positivePowerCount = positivePowerCount;
    }

    static double fractionWithToughnessAtMost(final int power) {
        return fractionAtMost(INSTANCE.toughnessCounts, power, INSTANCE.creatureCount);
    }

    static double fractionWithPowerBelow(final int toughness) {
        return fractionBelow(INSTANCE.powerCounts, toughness, INSTANCE.creatureCount);
    }

    static double fractionWithDamageBelow(final int toughness) {
        return fractionBelow(INSTANCE.positivePowerCounts, toughness, INSTANCE.positivePowerCount);
    }

    private static double fractionAtMost(final Map<Integer, Long> counts, final int threshold,
            final long denominator) {
        long matching = 0;
        for (final Map.Entry<Integer, Long> entry : counts.entrySet()) {
            if (entry.getKey() <= threshold) {
                matching += entry.getValue();
            }
        }
        return denominator == 0 ? 0 : (double) matching / denominator;
    }

    private static double fractionBelow(final Map<Integer, Long> counts, final int threshold,
            final long denominator) {
        long matching = 0;
        for (final Map.Entry<Integer, Long> entry : counts.entrySet()) {
            if (entry.getKey() >= threshold) {
                continue;
            }
            matching += entry.getValue();
        }
        return denominator == 0 ? 0 : (double) matching / denominator;
    }

    private static CreatureCombatDistribution load() {
        final Map<Integer, Long> powerCounts = new TreeMap<>();
        final Map<Integer, Long> toughnessCounts = new TreeMap<>();
        final Map<Integer, Long> positivePowerCounts = new TreeMap<>();
        long creatureCount = 0;
        long positivePowerCount = 0;
        try (InputStream stream = CreatureCombatDistribution.class.getResourceAsStream(
                "/forge/ai/effect/creature-reference-distribution.csv")) {
            if (stream == null) {
                throw new IllegalStateException("Missing creature-reference-distribution.csv resource");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("PT|")) {
                        continue;
                    }
                    final String[] fields = line.split("\\|", -1);
                    final int power = Integer.parseInt(fields[1]);
                    final int toughness = Integer.parseInt(fields[2]);
                    final long count = Integer.parseInt(fields[3]);
                    // Ignore malformed and non-surviving printed bodies, including 0/0 creatures.
                    if (toughness <= 0 || count <= 0) {
                        continue;
                    }
                    merge(powerCounts, power, count);
                    merge(toughnessCounts, toughness, count);
                    creatureCount += count;
                    if (power > 0) {
                        merge(positivePowerCounts, power, count);
                        positivePowerCount += count;
                    }
                }
            }
        } catch (final IOException | NumberFormatException failure) {
            throw new IllegalStateException("Could not read creature-reference-distribution.csv", failure);
        }
        if (creatureCount == 0 || positivePowerCount == 0) {
            throw new IllegalStateException("Creature combat distribution contains no usable P/T data");
        }
        return new CreatureCombatDistribution(powerCounts, toughnessCounts, positivePowerCounts,
                creatureCount, positivePowerCount);
    }

    private static void merge(final Map<Integer, Long> counts, final int key, final long count) {
        counts.merge(key, count, Long::sum);
    }
}
