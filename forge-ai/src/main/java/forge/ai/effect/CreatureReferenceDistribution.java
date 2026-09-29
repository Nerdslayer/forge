package forge.ai.effect;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import forge.ai.effect.IntrinsicReferenceModel.CreatureProfile;

/** Builds a compact sample distribution from the card-dataset marginals. */
final class CreatureReferenceDistribution {
    private static final double ABSENT_PROFILE_WEIGHT = .20;
    private static final double PRESENT_PROFILE_WEIGHT = 1 - ABSENT_PROFILE_WEIGHT;
    private static final double MINIMUM_BODY_PROBABILITY = .01;
    private static final WeightedDistribution<CreatureProfile> PROFILES = loadProfiles();
    private static final WeightedDistribution<CreatureProfile> COARSE_PROFILES = createCoarseProfiles();

    private CreatureReferenceDistribution() {
    }

    static WeightedDistribution<CreatureProfile> profiles() {
        return PROFILES;
    }

    /**
     * Keeps the former small profile set as a bounded-search fallback when several reference
     * dimensions would otherwise exceed the intrinsic outcome evaluator's case budget.
     */
    static WeightedDistribution<CreatureProfile> coarseProfiles() {
        return COARSE_PROFILES;
    }

    private static WeightedDistribution<CreatureProfile> createCoarseProfiles() {
        return WeightedDistribution.of(
                new WeightedValue<>(CreatureProfile.absent(), .20),
                new WeightedValue<>(new CreatureProfile(true, 1, 1, Set.of(), false, false), .25),
                new WeightedValue<>(new CreatureProfile(true, 2, 2, Set.of(), false, false), .23),
                new WeightedValue<>(new CreatureProfile(true, 3, 3, Set.of(), false, false), .15),
                new WeightedValue<>(new CreatureProfile(true, 4, 4, Set.of("trample"), false, false), .08),
                new WeightedValue<>(new CreatureProfile(true, 1, 3,
                        Set.of("flying", "first strike"), false, false), .04),
                new WeightedValue<>(new CreatureProfile(true, 2, 4,
                        Set.of("lifelink"), true, false), .03),
                new WeightedValue<>(new CreatureProfile(true, 4, 1,
                        Set.of("double strike"), false, false), .02));
    }

    private static WeightedDistribution<CreatureProfile> loadProfiles() {
        final List<BodyCount> bodies = new ArrayList<>();
        final List<KeywordCount> keywords = new ArrayList<>();
        int bodyDenominator = 0;
        int keywordDenominator = 0;
        try (InputStream stream = CreatureReferenceDistribution.class
                .getResourceAsStream("creature-reference-distribution.csv")) {
            if (stream == null) {
                throw new IllegalStateException("Missing creature-reference-distribution.csv resource");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    final String[] fields = line.split("\\|", -1);
                    switch (fields[0]) {
                    case "PT_DENOMINATOR" -> bodyDenominator = Integer.parseInt(fields[1]);
                    case "KEYWORD_DENOMINATOR" -> keywordDenominator = Integer.parseInt(fields[1]);
                    case "PT" -> bodies.add(new BodyCount(Integer.parseInt(fields[1]),
                            Integer.parseInt(fields[2]), Integer.parseInt(fields[3])));
                    case "KEYWORD" -> keywords.add(new KeywordCount(fields[1],
                            Integer.parseInt(fields[2])));
                    default -> throw new IllegalStateException("Unknown creature distribution row: " + line);
                    }
                }
            }
        } catch (final IOException | NumberFormatException failure) {
            throw new IllegalStateException("Could not read creature-reference-distribution.csv", failure);
        }
        if (bodyDenominator <= 0 || keywordDenominator <= 0 || bodies.isEmpty() || keywords.isEmpty()) {
            throw new IllegalStateException("Creature reference distribution data is incomplete");
        }
        final int fixedBodyDenominator = bodyDenominator;
        final int eligibleCreatureDenominator = keywordDenominator;

        final List<BodyCount> retainedBodies = bodies.stream()
                .filter(body -> body.power() != 0 || body.toughness() != 0)
                .filter(body -> (double) body.count() / fixedBodyDenominator >= MINIMUM_BODY_PROBABILITY)
                .toList();
        final int retainedBodyCount = retainedBodies.stream().mapToInt(BodyCount::count).sum();
        if (retainedBodyCount <= 0) {
            throw new IllegalStateException("No creature P/T profiles meet the reference threshold");
        }

        final double keywordProbability = keywords.stream()
                .mapToDouble(keyword -> (double) keyword.count() / eligibleCreatureDenominator).sum();
        if (keywordProbability >= 1) {
            throw new IllegalStateException("Top keyword marginal weights leave no vanilla profile mass");
        }

        // Keyword marginals are treated as mutually exclusive sample categories. This deliberately
        // ignores multi-keyword co-occurrence; the unrepresented remainder is the vanilla category.
        final List<WeightedValue<CreatureProfile>> profiles = new ArrayList<>();
        profiles.add(new WeightedValue<>(CreatureProfile.absent(), ABSENT_PROFILE_WEIGHT));
        for (final BodyCount body : retainedBodies) {
            final double bodyWeight = (double) body.count() / retainedBodyCount;
            final double bodyProfileWeight = PRESENT_PROFILE_WEIGHT * bodyWeight;
            profiles.add(new WeightedValue<>(new CreatureProfile(true, body.power(), body.toughness(),
                    Set.of(), false, false), bodyProfileWeight * (1 - keywordProbability)));
            for (final KeywordCount keyword : keywords) {
                final double keywordWeight = (double) keyword.count() / eligibleCreatureDenominator;
                profiles.add(new WeightedValue<>(new CreatureProfile(true, body.power(), body.toughness(),
                        Set.of(keyword.keyword()), false, false), bodyProfileWeight * keywordWeight));
            }
        }
        return new WeightedDistribution<>(profiles);
    }

    private record BodyCount(int power, int toughness, int count) {
    }

    private record KeywordCount(String keyword, int count) {
    }
}
