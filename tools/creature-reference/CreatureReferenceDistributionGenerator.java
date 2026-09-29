import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import forge.CardStorageReader;
import forge.GuiDesktop;
import forge.card.CardRules;
import forge.card.ICardFace;
import forge.gui.GuiBase;
import forge.util.Lang;
import forge.util.Localizer;

/**
 * Generates the reference P/T and keyword marginals used by intrinsic creature evaluation.
 * Run from forge-gui-desktop so the repository asset paths resolve correctly:
 *
 * <pre>
 * java -cp target/forge-gui-desktop-*-jar-with-dependencies.jar \
 *   ../tools/creature-reference/CreatureReferenceDistributionGenerator.java \
 *   ../forge-ai/src/main/resources/forge/ai/effect/creature-reference-distribution.json
 * </pre>
 *
 * This reads the repository's regular card scripts only. It does not initialize FModel or load
 * player preferences/custom-card directories. Each unique regular card definition is counted once.
 */
public final class CreatureReferenceDistributionGenerator {
    private static final int MAX_CMC = 5;
    private static final int TOP_KEYWORDS = 10;
    private static final String INTEGER_CHARACTERISTIC = "-?\\d+";

    private CreatureReferenceDistributionGenerator() {
    }

    private record Body(int power, int toughness) { }

    public static void main(final String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: CreatureReferenceDistributionGenerator <output.json>");
        }

        // The card reader needs Forge's asset paths and localized parser messages. This setup
        // avoids FModel.initialize(), which also reads/writes the local player profile.
        GuiBase.setInterface(new GuiDesktop());
        Lang.createInstance("en-US");
        final Path assets = Path.of("..", "forge-gui", "res").toAbsolutePath().normalize();
        final String languageDirectory = assets.resolve("languages").toString()
                + java.io.File.separator;
        Localizer.getInstance().initialize("en-US", languageDirectory);

        final CardStorageReader reader = new CardStorageReader(
                assets.resolve("cardsfolder").toString(), null, false);
        final Map<Body, Integer> bodyCounts = new TreeMap<>(Comparator
                .comparingInt(Body::power).thenComparingInt(Body::toughness));
        final Map<String, Integer> keywordCounts = new HashMap<>();
        int regularDefinitions = 0;
        int eligibleCreatures = 0;
        int fixedPtCreatures = 0;
        int nonNumericPtCreatures = 0;

        for (final CardRules rules : reader.readAllCards()) {
            if (rules.isCustom() || rules.isVariant()) {
                continue;
            }
            regularDefinitions++;
            final ICardFace face = rules.getMainPart();
            if (face == null || !face.getType().isCreature()
                    || face.getManaCost().getCMC() > MAX_CMC) {
                continue;
            }
            eligibleCreatures++;

            final Set<String> cardKeywords = new HashSet<>();
            if (face.getKeywords() != null) {
                for (final String rawKeyword : face.getKeywords()) {
                    final String keyword = normalizeKeyword(rawKeyword);
                    if (!keyword.isEmpty()) {
                        cardKeywords.add(keyword);
                    }
                }
            }
            cardKeywords.forEach(keyword -> keywordCounts.merge(keyword, 1, Integer::sum));

            final String power = face.getPower();
            final String toughness = face.getToughness();
            if (power == null || toughness == null
                    || !power.trim().matches(INTEGER_CHARACTERISTIC)
                    || !toughness.trim().matches(INTEGER_CHARACTERISTIC)) {
                nonNumericPtCreatures++;
                continue;
            }
            final Body body = new Body(Integer.parseInt(power.trim()), Integer.parseInt(toughness.trim()));
            bodyCounts.merge(body, 1, Integer::sum);
            fixedPtCreatures++;
        }

        final List<Map.Entry<String, Integer>> topKeywords = keywordCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_KEYWORDS).toList();
        final String json = render(regularDefinitions, eligibleCreatures, fixedPtCreatures,
                nonNumericPtCreatures, bodyCounts, topKeywords);
        final Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Files.writeString(output, json, StandardCharsets.UTF_8);
        final Path runtimeData = output.resolveSibling("creature-reference-distribution.csv");
        Files.writeString(runtimeData, renderRuntimeData(eligibleCreatures, fixedPtCreatures,
                bodyCounts, topKeywords), StandardCharsets.UTF_8);

        System.out.printf("Scanned %d regular card definitions; %d main-face creatures have CMC <= %d.%n",
                regularDefinitions, eligibleCreatures, MAX_CMC);
        System.out.printf("P/T distribution: %d fixed-P/T creatures across %d body profiles; "
                        + "%d variable/missing P/T excluded from that distribution.%n",
                fixedPtCreatures, bodyCounts.size(), nonNumericPtCreatures);
        System.out.println("Top keyword abilities (count / eligible creatures):");
        for (final Map.Entry<String, Integer> entry : topKeywords) {
            System.out.printf("  %s: %d / %d (%.4f%%)%n", entry.getKey(), entry.getValue(),
                    eligibleCreatures, 100d * entry.getValue() / eligibleCreatures);
        }
        System.out.println("Wrote " + output);
        System.out.println("Wrote " + runtimeData);
    }

    private static String normalizeKeyword(final String raw) {
        if (raw == null) {
            return "";
        }
        String keyword = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        final int parameterSeparator = keyword.indexOf(':');
        if (parameterSeparator >= 0) {
            keyword = keyword.substring(0, parameterSeparator).trim();
        }
        // Forge stores some implementation tags beside rules keywords. They describe how an
        // effect is scripted rather than an ability the creature itself has (for example,
        // K:etbCounter:P1P1:1).
        if (keyword.equals("etbcounter") || keyword.equals("etbreplacement")) {
            return "";
        }
        if (keyword.startsWith("protection from ")) {
            return "protection";
        }
        if (keyword.startsWith("ward ")) {
            return "ward";
        }
        return keyword;
    }

    private static String render(final int regularDefinitions, final int eligibleCreatures,
            final int fixedPtCreatures, final int nonNumericPtCreatures,
            final Map<Body, Integer> bodyCounts,
            final List<Map.Entry<String, Integer>> topKeywords) {
        final StringBuilder json = new StringBuilder(8192);
        json.append("{\n")
                .append("  \"schemaVersion\": 1,\n")
                .append("  \"source\": \"Forge regular unique card scripts\",\n")
                .append("  \"filter\": {\n")
                .append("    \"mainFaceCreature\": true,\n")
                .append("    \"maximumCmc\": ").append(MAX_CMC).append(",\n")
                .append("    \"customCardsIncluded\": false,\n")
                .append("    \"variantsIncluded\": false,\n")
                .append("    \"scriptImplementationTagsExcluded\": [\"etbcounter\", \"etbreplacement\"],\n")
                .append("    \"eachCardDefinitionCountedOnce\": true\n")
                .append("  },\n")
                .append("  \"regularCardDefinitionsScanned\": ").append(regularDefinitions).append(",\n")
                .append("  \"eligibleCreatureDefinitions\": ").append(eligibleCreatures).append(",\n")
                .append("  \"fixedPtCreatureDefinitions\": ").append(fixedPtCreatures).append(",\n")
                .append("  \"nonNumericPtCreatureDefinitions\": ").append(nonNumericPtCreatures).append(",\n")
                .append("  \"ptDistributionDenominator\": ").append(fixedPtCreatures).append(",\n")
                .append("  \"ptDistribution\": [\n");
        int index = 0;
        for (final Map.Entry<Body, Integer> entry : bodyCounts.entrySet()) {
            final Body body = entry.getKey();
            json.append("    {\"power\": ").append(body.power())
                    .append(", \"toughness\": ").append(body.toughness())
                    .append(", \"count\": ").append(entry.getValue())
                    .append(", \"probability\": ")
                    .append(probability(entry.getValue(), fixedPtCreatures)).append("}");
            json.append(++index == bodyCounts.size() ? "\n" : ",\n");
        }
        json.append("  ],\n")
                .append("  \"keywordDistributionDenominator\": ").append(eligibleCreatures).append(",\n")
                .append("  \"keywordDistribution\": [\n");
        for (int i = 0; i < topKeywords.size(); i++) {
            final Map.Entry<String, Integer> entry = topKeywords.get(i);
            json.append("    {\"keyword\": \"").append(escapeJson(entry.getKey()))
                    .append("\", \"count\": ").append(entry.getValue())
                    .append(", \"probability\": ")
                    .append(probability(entry.getValue(), eligibleCreatures)).append("}");
            json.append(i + 1 == topKeywords.size() ? "\n" : ",\n");
        }
        return json.append("  ]\n}\n").toString();
    }

    private static String probability(final int count, final int denominator) {
        return String.format(Locale.ROOT, "%.10f", (double) count / denominator);
    }

    private static String renderRuntimeData(final int eligibleCreatures, final int fixedPtCreatures,
            final Map<Body, Integer> bodyCounts,
            final List<Map.Entry<String, Integer>> topKeywords) {
        final StringBuilder data = new StringBuilder(4096)
                .append("# Generated by tools/creature-reference/CreatureReferenceDistributionGenerator.java\n")
                .append("# PT rows contain power, toughness, and count; KEYWORD rows contain name and count.\n")
                .append("PT_DENOMINATOR|").append(fixedPtCreatures).append('\n')
                .append("KEYWORD_DENOMINATOR|").append(eligibleCreatures).append('\n');
        for (final Map.Entry<Body, Integer> entry : bodyCounts.entrySet()) {
            data.append("PT|").append(entry.getKey().power()).append('|')
                    .append(entry.getKey().toughness()).append('|').append(entry.getValue()).append('\n');
        }
        for (final Map.Entry<String, Integer> entry : topKeywords) {
            data.append("KEYWORD|").append(entry.getKey()).append('|').append(entry.getValue()).append('\n');
        }
        return data.toString();
    }

    private static String escapeJson(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
