package forge.ai;

import forge.card.CardRules;
import forge.card.ICardFace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Evaluates the printed, game-independent value of a card definition.
 *
 * <p>This is intentionally separate from {@link CreatureEvaluator}. CreatureEvaluator evaluates a
 * live permanent and therefore includes information such as counters, current restrictions, and
 * whether the object is a token. A card creator needs a stable value for the definition itself.</p>
 */
public final class CardDefinitionValueEvaluator {
    private static final Set<String> SUPPORTED_SIMPLE_KEYWORDS = Set.of(
            "flying", "vigilance", "trample", "lifelink", "deathtouch", "reach", "first strike",
            "double strike", "defender", "menace", "fear", "intimidate", "hexproof", "shroud",
            "indestructible");

    public record Contribution(String category, String label, int value) {
    }

    public record Evaluation(int grossPointValue, int battlefieldValue, int cardOpportunityCost,
            int manaInvestment, int netRate, List<Contribution> contributions,
            List<String> warnings) {
        public Evaluation {
            contributions = List.copyOf(contributions);
            warnings = List.copyOf(warnings);
        }

        public boolean isComplete() {
            return warnings.isEmpty();
        }
    }

    public Evaluation evaluate(final CardRules rules) {
        if (rules == null || rules.getMainPart() == null) {
            return unsupported("No card definition is available.");
        }

        final ICardFace face = rules.getMainPart();
        final List<Contribution> contributions = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        if (!face.getType().isCreature()) {
            warnings.add("The initial definition evaluator supports creatures only.");
            return finish(contributions, warnings, face.getManaCost().getCMC());
        }
        if (rules.getOtherPart() != null || rules.getAllFaces().size() > 1) {
            warnings.add("Only single-faced cards are fully evaluated.");
        }

        add(contributions, "Battlefield", "Base creature", 80);
        final String powerText = face.getPower();
        final String toughnessText = face.getToughness();
        final boolean integerPower = powerText != null && powerText.matches("\\d+");
        final boolean integerToughness = toughnessText != null && toughnessText.matches("\\d+");
        if (!integerPower || !integerToughness) {
            warnings.add("Variable power and toughness are not evaluated yet.");
        } else {
            final int power = face.getIntPower();
            final int toughness = face.getIntToughness();
            add(contributions, "Battlefield", "Power", power * 15);
            add(contributions, "Battlefield", "Toughness", toughness * 10);
            addKeywordContributions(contributions, warnings, face, power, toughness);
        }

        for (final String keyword : face.getKeywords()) {
            final String normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                continue;
            }
            final String base = normalized.contains(":")
                    ? normalized.substring(0, normalized.indexOf(':')).trim() : normalized;
            if (!SUPPORTED_SIMPLE_KEYWORDS.contains(base)) {
                warnings.add("Keyword not evaluated yet: " + keyword);
            } else if (normalized.contains(":")) {
                warnings.add("Parameterized keyword not evaluated yet: " + keyword);
            }
        }

        return finish(contributions, warnings, face.getManaCost().getCMC());
    }

    private void addKeywordContributions(final List<Contribution> contributions, final List<String> warnings,
            final ICardFace face, final int power, final int toughness) {
        if (hasSimpleKeyword(face, "flying")) add(contributions, "Keyword", "Flying", power * 10);
        if (hasSimpleKeyword(face, "vigilance")) add(contributions, "Keyword", "Vigilance", power * 5 + toughness * 5);
        if (hasSimpleKeyword(face, "trample") && power > 1) add(contributions, "Keyword", "Trample", (power - 1) * 5);
        if (hasSimpleKeyword(face, "lifelink") && power > 0) add(contributions, "Keyword", "Lifelink", power * 10);
        if (hasSimpleKeyword(face, "deathtouch") && power > 0) add(contributions, "Keyword", "Deathtouch", 25);
        if (hasSimpleKeyword(face, "reach") && !hasSimpleKeyword(face, "flying")) add(contributions, "Keyword", "Reach", 5);
        if (hasSimpleKeyword(face, "double strike") && power > 0) add(contributions, "Keyword", "Double strike", 10 + power * 15);
        else if (hasSimpleKeyword(face, "first strike") && power > 0) add(contributions, "Keyword", "First strike", 10 + power * 5);
        if (hasSimpleKeyword(face, "menace") && power > 0) add(contributions, "Keyword", "Menace", power * 4);
        if (hasSimpleKeyword(face, "fear") && power > 0) add(contributions, "Keyword", "Fear", power * 6);
        if (hasSimpleKeyword(face, "intimidate") && power > 0) add(contributions, "Keyword", "Intimidate", power * 6);
        if (hasSimpleKeyword(face, "indestructible")) add(contributions, "Keyword", "Indestructible", 70);
        else if (hasSimpleKeyword(face, "hexproof")) add(contributions, "Keyword", "Hexproof", 35);
        else if (hasSimpleKeyword(face, "shroud")) add(contributions, "Keyword", "Shroud", 30);
        if (hasSimpleKeyword(face, "defender")) add(contributions, "Keyword", "Defender", -((power * 9) + 40));

        // These are editable in the basic form but do not yet have an agreed vacuum formula.
        if (hasSimpleKeyword(face, "haste")) warnings.add("Keyword not evaluated yet: Haste");
        if (hasSimpleKeyword(face, "flash")) warnings.add("Keyword not evaluated yet: Flash");
    }

    private static boolean hasSimpleKeyword(final ICardFace face, final String keyword) {
        for (final String value : face.getKeywords()) {
            if (value != null && value.trim().equalsIgnoreCase(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static Evaluation finish(final List<Contribution> contributions, final List<String> warnings,
            final int manaValue) {
        final int battlefield = contributions.stream().mapToInt(Contribution::value).sum();
        final int cardCost = CardResourceValueEvaluator.evaluateCardOpportunityCost();
        final int manaCost = CardResourceValueEvaluator.evaluateManaInvestment(manaValue);
        return new Evaluation(battlefield, battlefield, cardCost, manaCost,
                battlefield - cardCost - manaCost, contributions, deduplicate(warnings));
    }

    private static Evaluation unsupported(final String warning) {
        return finish(Collections.emptyList(), List.of(warning), 0);
    }

    private static void add(final List<Contribution> contributions, final String category,
            final String label, final int value) {
        contributions.add(new Contribution(category, label, value));
    }

    private static List<String> deduplicate(final List<String> warnings) {
        return new ArrayList<>(new LinkedHashSet<>(warnings));
    }
}
