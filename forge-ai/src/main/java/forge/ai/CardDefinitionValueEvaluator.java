package forge.ai;

import forge.ai.effect.CardAbilityTraversal;
import forge.ai.effect.CardValueBreakdown;
import forge.ai.effect.IntrinsicAbilityEvaluator;
import forge.ai.effect.IntrinsicEvaluationSettings;
import forge.ai.effect.IntrinsicOutcomeEvaluator;
import forge.ai.effect.IntrinsicReferenceAggregate;
import forge.ai.effect.IntrinsicReferenceModel;
import forge.ai.effect.ValuationCompleteness;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.CardStateName;
import forge.card.ICardFace;
import forge.game.ability.AbilityFactory;
import forge.game.cost.Cost;
import forge.game.cost.CostPartMana;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final IntrinsicAbilityEvaluator INTRINSIC_EVALUATOR = new IntrinsicAbilityEvaluator(
            IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
    private static final IntrinsicOutcomeEvaluator PERMANENT_EVALUATOR = new IntrinsicOutcomeEvaluator(
            IntrinsicEvaluationSettings.defaults());

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

        /** Adapts definition evaluation to the shared card-value breakdown. */
        public CardValueBreakdown toCardValueBreakdown() {
            int currentPresence = 0;
            int futurePotential = 0;
            for (final Contribution contribution : contributions) {
                if ("Battlefield".equals(contribution.category())) {
                    currentPresence += contribution.value();
                } else {
                    futurePotential += contribution.value();
                }
            }
            return new CardValueBreakdown(currentPresence, futurePotential, 0,
                    cardOpportunityCost + manaInvestment, 0,
                    warnings.isEmpty() ? ValuationCompleteness.COMPLETE
                            : ValuationCompleteness.PARTIAL,
                    warnings);
        }
    }

    public Evaluation evaluate(final CardRules rules) {
        return evaluate(rules, CardEdition.UNKNOWN_CODE);
    }

    /**
     * Evaluates a definition and uses the selected edition when resolving definition-only card
     * data such as token profiles. The edition is intentionally not part of the card's value;
     * it only supplies context for the game-free intrinsic ability traversal.
     */
    public Evaluation evaluate(final CardRules rules, final String editionCode) {
        if (rules == null || rules.getMainPart() == null) {
            return unsupported("No card definition is available.");
        }

        final ICardFace face = rules.getMainPart();
        final List<Contribution> contributions = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        addIntrinsicAbilityContributions(rules, editionCode, face, contributions, warnings);

        if (!face.getType().isCreature()) {
            addNonCreaturePermanentContribution(face, contributions);
            if (rules.getOtherPart() != null || rules.getAllFaces().size() > 1) {
                warnings.add("Only single-faced cards are fully evaluated.");
            }
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

    /**
     * Reuses the nonrecursive reference permanent metric for noncreature permanents. Instants and
     * sorceries have no battlefield presence; their supported printed spell outcomes are still
     * included by the intrinsic ability evaluator above.
     */
    private static void addNonCreaturePermanentContribution(final ICardFace face,
            final List<Contribution> contributions) {
        final IntrinsicReferenceModel.PermanentKind kind = permanentKind(face);
        if (kind == null) {
            return;
        }
        final String loyaltyText = face.getInitialLoyalty();
        final int loyalty = loyaltyText != null && loyaltyText.matches("\\d+")
                ? Integer.parseInt(loyaltyText) : 0;
        final IntrinsicReferenceModel.PermanentProfile profile =
                new IntrinsicReferenceModel.PermanentProfile(true, kind, true, 0, 0,
                        Set.of(), face.getType().isBasicLand(), loyalty);
        final int value = PERMANENT_EVALUATOR.evaluatePermanent(profile);
        if (value != 0) {
            add(contributions, "Battlefield", "Base permanent", value);
        }
    }

    private static IntrinsicReferenceModel.PermanentKind permanentKind(final ICardFace face) {
        if (face.getType().isAura()) {
            return IntrinsicReferenceModel.PermanentKind.AURA;
        }
        if (face.getType().isPlaneswalker()) {
            return IntrinsicReferenceModel.PermanentKind.PLANESWALKER;
        }
        if (face.getType().isArtifact()) {
            return IntrinsicReferenceModel.PermanentKind.ARTIFACT;
        }
        if (face.getType().isEnchantment()) {
            return IntrinsicReferenceModel.PermanentKind.ENCHANTMENT;
        }
        if (face.getType().isLand()) {
            return IntrinsicReferenceModel.PermanentKind.LAND;
        }
        return face.getType().isPermanent()
                ? IntrinsicReferenceModel.PermanentKind.PERMANENT : null;
    }

    private static void addIntrinsicAbilityContributions(final CardRules rules, final String editionCode,
            final ICardFace face, final List<Contribution> contributions, final List<String> warnings) {
        if (!hasAbilityRecords(face)) {
            return;
        }

        final String edition = editionCode == null || editionCode.isBlank()
                ? CardEdition.UNKNOWN_CODE : editionCode;
        final PaperCard definition = new PaperCard(rules, edition, CardRarity.Special);
        final IntrinsicAbilityEvaluator.DefinitionEvaluation intrinsic;
        try {
            intrinsic = INTRINSIC_EVALUATOR.evaluateDefinitionDetails(definition, CardStateName.Original);
        } catch (final RuntimeException ignored) {
            // Keep the definition evaluator useful when Forge cannot materialize an unusual card
            // definition. It is better to report an unsupported ability than to invent a value.
            addFallbackAbilityWarnings(warnings, face);
            return;
        }

        final Map<String, CardAbilityTraversal.AbilityDescription> byPath = new HashMap<>();
        for (final CardAbilityTraversal.AbilityDescription description : intrinsic.descriptions()) {
            if (description.provenance() == CardAbilityTraversal.Provenance.PRINTED
                    && hasUnfactoredAdditionalCost(description)) {
                warnings.add(additionalCostMessage(description));
            }
            byPath.put(description.path(), description);
        }
        for (final IntrinsicAbilityEvaluator.AbilityValue value : intrinsic.values()) {
            final CardAbilityTraversal.AbilityDescription description = byPath.get(value.path());
            if (description == null || description.provenance() != CardAbilityTraversal.Provenance.PRINTED) {
                // Keyword-generated abilities are represented by the existing keyword model, and
                // granted abilities are not intrinsic to this definition. Avoid counting either.
                continue;
            }

            final IntrinsicReferenceAggregate aggregate = value.contribution();
            if (value.triggerStatus() != IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED
                    || value.outcomeStatus() != IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED) {
                warnings.add(unsupportedAbilityMessage(description, value));
                continue;
            }

            final int valuePoints = toInt(aggregate.value());
            if (valuePoints != 0) {
                add(contributions, "Intrinsic ability", intrinsicLabel(description, value), valuePoints);
            }
        }
    }

    /** Returns whether an ability has a non-mana cost that the intrinsic model does not price. */
    private static boolean hasUnfactoredAdditionalCost(
            final CardAbilityTraversal.AbilityDescription description) {
        if (description.origin() != CardAbilityTraversal.Origin.SPELL
                && description.origin() != CardAbilityTraversal.Origin.ACTIVATION) {
            return false;
        }
        return hasUnfactoredAdditionalCost(description.parameters());
    }

    private static boolean hasUnfactoredAdditionalCost(final Map<String, String> parameters) {
        final String encoded = parameters.get("Cost");
        if (encoded == null || encoded.isBlank()) {
            return false;
        }
        try {
            return new Cost(encoded, true).getCostParts().stream()
                    .anyMatch(part -> !(part instanceof CostPartMana));
        } catch (final RuntimeException ignored) {
            // The ordinary ability diagnostics report malformed or otherwise unsupported costs.
            return false;
        }
    }

    private static String additionalCostMessage(
            final CardAbilityTraversal.AbilityDescription description) {
        final String kind = description.origin() == CardAbilityTraversal.Origin.ACTIVATION
                ? "activated ability" : "spell ability";
        return "Additional cost on " + kind + " " + abilityNumber(description)
                + " is not factored into this evaluation.";
    }

    private static boolean hasAbilityRecords(final ICardFace face) {
        return face.getTriggers().iterator().hasNext()
                || face.getAbilities().iterator().hasNext()
                || face.getStaticAbilities().iterator().hasNext()
                || face.getReplacements().iterator().hasNext();
    }

    private static void addFallbackAbilityWarnings(final List<String> warnings, final ICardFace face) {
        addFallbackAbilityWarning(warnings, face.getTriggers(), "Triggered");
        addFallbackAbilityWarning(warnings, face.getAbilities(), "Activated or spell", true);
        addFallbackAbilityWarning(warnings, face.getStaticAbilities(), "Static");
        addFallbackAbilityWarning(warnings, face.getReplacements(), "Replacement");
    }

    private static void addFallbackAbilityWarning(final List<String> warnings,
            final Iterable<String> abilities, final String kind) {
        addFallbackAbilityWarning(warnings, abilities, kind, false);
    }

    private static void addFallbackAbilityWarning(final List<String> warnings,
            final Iterable<String> abilities, final String kind, final boolean inspectCosts) {
        int count = 0;
        for (final String ability : abilities) {
            if (inspectCosts && hasUnfactoredAdditionalCost(ability)) {
                warnings.add("Additional cost on " + additionalCostKind(ability) + " " + (count + 1)
                        + " is not factored into this evaluation.");
            }
            warnings.add(kind + " ability " + ++count
                    + " evaluation is not supported because the ability could not be analyzed.");
        }
    }

    private static boolean hasUnfactoredAdditionalCost(final String ability) {
        try {
            final Map<String, String> parameters = AbilityFactory.getMapParams(ability);
            return (parameters.containsKey("SP") || parameters.containsKey("AB"))
                    && hasUnfactoredAdditionalCost(parameters);
        } catch (final RuntimeException ignored) {
            return false;
        }
    }

    private static String additionalCostKind(final String ability) {
        try {
            return AbilityFactory.getMapParams(ability).containsKey("AB")
                    ? "activated ability" : "spell ability";
        } catch (final RuntimeException ignored) {
            return "spell ability";
        }
    }

    private static String intrinsicLabel(final CardAbilityTraversal.AbilityDescription description,
            final IntrinsicAbilityEvaluator.AbilityValue value) {
        final String triggerDescription = description.parameters().get("TriggerDescription");
        final String detail = triggerDescription == null || triggerDescription.isBlank()
                ? description.origin().name().toLowerCase(Locale.ROOT)
                : triggerDescription;
        return value.path() + " (" + detail + ", "
                + String.format(Locale.ROOT, "%.2f", value.expectedOccurrences()) + " expected uses)";
    }

    private static String abilityKind(final CardAbilityTraversal.AbilityDescription description) {
        return switch (description.origin()) {
        case TRIGGER -> "Triggered";
        case ACTIVATION, SPELL -> "Activated or spell";
        case STATIC -> "Static";
        case REPLACEMENT -> "Replacement";
        };
    }

    private static String unsupportedAbilityMessage(final CardAbilityTraversal.AbilityDescription description,
            final IntrinsicAbilityEvaluator.AbilityValue value) {
        final IntrinsicAbilityEvaluator.SupportStatus trigger = value.triggerStatus();
        final IntrinsicAbilityEvaluator.SupportStatus outcome = value.outcomeStatus();
        final String reason;
        if (description.origin() == CardAbilityTraversal.Origin.TRIGGER) {
            final boolean triggerUnsupported = trigger == IntrinsicAbilityEvaluator.SupportStatus.UNSUPPORTED;
            final boolean outcomeUnsupported = outcome == IntrinsicAbilityEvaluator.SupportStatus.UNSUPPORTED;
            if (triggerUnsupported && outcomeUnsupported) {
                reason = "the trigger and outcome were not recognized";
            } else if (triggerUnsupported) {
                reason = "the trigger was not recognized";
            } else if (outcomeUnsupported) {
                reason = "the outcome was not recognized";
            } else {
                reason = "the outcome could only be partially evaluated";
            }
        } else if (outcome == IntrinsicAbilityEvaluator.SupportStatus.UNSUPPORTED) {
            reason = "the ability type and outcome were not recognized";
        } else {
            reason = "this ability type is not supported yet";
        }
        return abilityKind(description) + " ability " + abilityNumber(description)
                + " evaluation is not supported because " + reason + ".";
    }

    private static int abilityNumber(final CardAbilityTraversal.AbilityDescription description) {
        final String path = description.path();
        final String marker = switch (description.origin()) {
        case TRIGGER -> "/trigger:";
        case ACTIVATION, SPELL -> "/ability:";
        case STATIC -> "/static:";
        case REPLACEMENT -> "/replacement:";
        };
        final int markerIndex = path.lastIndexOf(marker);
        if (markerIndex < 0) return 1;
        try {
            return Integer.parseInt(path.substring(markerIndex + marker.length())) + 1;
        } catch (final NumberFormatException ignored) {
            return 1;
        }
    }

    private static int toInt(final double value) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE
                : value <= Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) Math.round(value);
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
