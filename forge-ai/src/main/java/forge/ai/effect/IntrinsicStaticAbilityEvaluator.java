package forge.ai.effect;

import java.util.Locale;
import java.util.Set;

import forge.ai.effect.IntrinsicReferenceModel.CreatureProfile;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;

/**
 * Evaluates a deliberately small set of static abilities without a live game state.
 *
 * <p>This is intrinsic potential, not current static relationship value. It assumes a modest
 * number of future recipients in a generally suitable deck and therefore does not inspect the
 * current battlefield. The live {@link StaticAbilityAnalyzer} remains responsible for exact
 * affected-card deltas.</p>
 */
final class IntrinsicStaticAbilityEvaluator {
    // TODO(effect analysis): Add validated adapters for unsupported static keywords, restrictions,
    // dynamic predicates, characteristic-defining abilities, permissions, and multi-effect text.
    private static final Set<String> ALLOWED_PARAMS = Set.of(
            "Mode", "Affected", "AddPower", "AddToughness", "SetPower", "SetToughness",
            "AddKeyword", "RemoveKeyword", "AIEffectValue", "Description");
    private static final Set<String> SUPPORTED_KEYWORDS = Set.of(
            "flying", "reach", "first strike", "double strike", "menace", "fear", "intimidate",
            "deathtouch", "lifelink", "trample", "vigilance", "defender", "indestructible",
            "hexproof", "shroud", "ward", "shield", "stun", "detain", "can't attack",
            "cantattack", "can't block", "cantblock", "can't untap", "cantuntap");
    private static final double FUTURE_RECIPIENTS = 2.0;
    private static final double TRIBAL_FUTURE_RECIPIENTS = 1.5;

    private IntrinsicStaticAbilityEvaluator() {
    }

    static Evaluation evaluate(final CardAbilityTraversal.AbilityDescription ability,
            final PermanentProfile source, final IntrinsicReferenceModel model) {
        if (ability == null || source == null
                || ability.origin() != CardAbilityTraversal.Origin.STATIC) {
            return unsupported("not a static ability");
        }
        if (!ALLOWED_PARAMS.containsAll(ability.parameters().keySet())
                || !"Continuous".equals(ability.parameters().get("Mode"))) {
            return unsupported("unsupported intrinsic static parameters");
        }

        final IntrinsicStaticRecipientFilter.Filter recipients = IntrinsicStaticRecipientFilter.describe(
                ability.parameters().get("Affected"), model);
        final String affected = recipients.affected();
        final StaticAbilityScope scope = StaticAbilityScope.parse(affected);
        if (scope == null) {
            return unsupported("unsupported intrinsic static recipient scope");
        }
        if (recipients.profileRestricted() && !scope.isCreatureScope(affected)
                && !(scope == StaticAbilityScope.SELF && (source.kind() == PermanentKind.CREATURE || source.kind() == PermanentKind.TOKEN))) {
            return unsupported("static profile eligibility requires creature recipients");
        }
        final Set<String> addedKeywords = parseSupportedKeywords(ability.parameters().get("AddKeyword"));
        final Set<String> removedKeywords = parseSupportedKeywords(ability.parameters().get("RemoveKeyword"));
        if (addedKeywords == null || removedKeywords == null) {
            return unsupported("static keyword is not observed by the intrinsic permanent scorer");
        }
        if (addedKeywords.stream().anyMatch(removedKeywords::contains)) {
            return unsupported("conflicting static keyword changes require layer ordering");
        }
        // TODO: CantHaveKeyword, RemoveAllAbilities, conditional/parameterized removals and
        // timestamp/dependency interactions require more than a first-order profile delta.
        final int powerChange = literalInteger(ability.parameters().get("AddPower"), 0);
        final int toughnessChange = literalInteger(ability.parameters().get("AddToughness"), 0);
        final int setPower = literalInteger(ability.parameters().get("SetPower"), Integer.MIN_VALUE);
        final int setToughness = literalInteger(ability.parameters().get("SetToughness"), Integer.MIN_VALUE);
        final int hintedValue = literalInteger(ability.parameters().get("AIEffectValue"), 0);
        final boolean hasAutomaticChange = ability.parameters().containsKey("AddPower")
                || ability.parameters().containsKey("AddToughness")
                || ability.parameters().containsKey("SetPower")
                || ability.parameters().containsKey("SetToughness") || !addedKeywords.isEmpty() || !removedKeywords.isEmpty();
        if (!ability.parameters().containsKey("AddPower")
                && !ability.parameters().containsKey("AddToughness")
                && !ability.parameters().containsKey("SetPower")
                && !ability.parameters().containsKey("SetToughness") && addedKeywords.isEmpty() && removedKeywords.isEmpty()) {
            if (hintedValue == 0) {
                return unsupported("static effect has no intrinsically valued change");
            }
        }
        if (powerChange == Integer.MIN_VALUE || toughnessChange == Integer.MIN_VALUE
                || hintedValue == Integer.MIN_VALUE
                || (ability.parameters().containsKey("SetPower") && setPower < 0)
                || (ability.parameters().containsKey("SetToughness") && setToughness <= 0)
                || powerChange < -2 || toughnessChange <= -2
                || powerChange > 20 || toughnessChange > 20) {
            // Large reductions remain outside this bounded adapter; the reference distribution
            // separately models the common -1/-1 case killing small recipients.
            return unsupported("static P/T change is outside the safe intrinsic range");
        }
        if (hasAutomaticChange && scope != StaticAbilityScope.SELF
                && scope != StaticAbilityScope.ATTACHED && !scope.isCreatureScope(affected)) {
            return unsupported("automatic static characteristic changes require creature recipients");
        }

        final IntrinsicOutcomeEvaluator evaluator = new IntrinsicOutcomeEvaluator();
        final int automaticPerRecipient;
        final double recipientCount;
        final double eligibleRecipients;
        if (scope == StaticAbilityScope.SELF) {
            if (hasAutomaticChange && source.kind() != PermanentKind.CREATURE
                    && source.kind() != PermanentKind.TOKEN) {
                return unsupported("self P/T static change requires a creature reference source");
            }
            if (hasAutomaticChange) {
                final PermanentProfile after = withPowerAndToughness(source,
                        applySetAndAdd(source.power(), setPower, powerChange),
                        applySetAndAdd(source.toughness(), setToughness, toughnessChange),
                        addedKeywords, removedKeywords);
                automaticPerRecipient = evaluator.evaluatePermanentDelta(source, after, true);
            } else {
                automaticPerRecipient = 0;
            }
            recipientCount = 1;
            eligibleRecipients = recipients.matches(source) ? 1 : 0;
        } else {
            if (hasAutomaticChange) {
                automaticPerRecipient = averageCreatureDelta(model, evaluator, setPower,
                        setToughness, powerChange, toughnessChange, addedKeywords, removedKeywords, recipients::matches);
            } else {
                automaticPerRecipient = 0;
            }
            recipientCount = scope.isTribal(affected) ? TRIBAL_FUTURE_RECIPIENTS
                    : scope == StaticAbilityScope.ATTACHED ? 1 : FUTURE_RECIPIENTS;
            final double present = model.creatureProfiles().entries().stream().filter(entry -> entry.value().present())
                    .mapToDouble(WeightedValue::weight).sum();
            eligibleRecipients = !recipients.profileRestricted() ? 1 : present == 0 ? 0 : model.creatureProfiles().entries().stream()
                    .filter(entry -> entry.value().present() && recipients.matches(entry.value()))
                    .mapToDouble(WeightedValue::weight).sum() / present;
        }
        final double perRecipient = !recipients.profileRestricted() ? EffectMath.add(automaticPerRecipient, hintedValue)
                : (scope == StaticAbilityScope.SELF ? automaticPerRecipient * eligibleRecipients : automaticPerRecipient)
                        + hintedValue * eligibleRecipients;

        final double signedValue = switch (scope) {
        case SELF, ATTACHED, CONTROLLER -> perRecipient * recipientCount;
        case OPPONENT -> -perRecipient * recipientCount;
        case BOTH -> 0;
        };
        return supported(signedValue * recipients.probability(), scope.description() + " static characteristic potential"
                + (hintedValue == 0 ? "" : " with AIEffectValue supplement"));
    }

    private static PermanentProfile withPowerAndToughness(final PermanentProfile source,
            final int power, final int toughness, final Set<String> addedKeywords, final Set<String> removedKeywords) {
        if (toughness <= 0 && (source.kind() == PermanentKind.CREATURE
                || source.kind() == PermanentKind.TOKEN)) {
            return PermanentProfile.absent();
        }
        final Set<String> keywords = changeKeywords(source.keywords(), addedKeywords, removedKeywords);
        return new PermanentProfile(true, source.kind(), source.controlledByAi(),
                Math.max(0, power), Math.max(0, toughness), keywords,
                source.basicLand(), source.loyalty());
    }

    private static int averageCreatureDelta(final IntrinsicReferenceModel model,
            final IntrinsicOutcomeEvaluator evaluator, final int setPower, final int setToughness,
            final int powerChange, final int toughnessChange, final Set<String> addedKeywords) {
        return averageCreatureDelta(model, evaluator, setPower, setToughness, powerChange, toughnessChange,
                addedKeywords, Set.of(), profile -> true);
    }

    private static int averageCreatureDelta(final IntrinsicReferenceModel model,
            final IntrinsicOutcomeEvaluator evaluator, final int setPower, final int setToughness,
            final int powerChange, final int toughnessChange, final Set<String> addedKeywords, final Set<String> removedKeywords,
            final java.util.function.Predicate<CreatureProfile> eligible) {
        double presentProbability = 0;
        double weightedValue = 0;
        for (final WeightedValue<CreatureProfile> weighted : model.creatureProfiles().entries()) {
            final CreatureProfile before = weighted.value();
            if (!before.present()) {
                continue;
            }
            presentProbability += weighted.weight();
            if (!eligible.test(before)) { continue; }
            final int power = Math.max(0, applySetAndAdd(before.power(), setPower, powerChange));
            final int toughness = applySetAndAdd(before.toughness(), setToughness, toughnessChange);
            final CreatureProfile after = toughness <= 0 ? CreatureProfile.absent()
                    : new CreatureProfile(true, power, toughness,
                            changeKeywords(before.keywords(), addedKeywords, removedKeywords),
                            before.hexproof() && !removedKeywords.contains("hexproof"),
                            before.indestructible() && !removedKeywords.contains("indestructible"));
            weightedValue += weighted.weight()
                    * evaluator.evaluateCreatureDelta(before, after, true);
        }
        if (presentProbability <= 0) {
            return 0;
        }
        return (int) Math.round(weightedValue / presentProbability);
    }

    /**
     * Parses the bounded keyword vocabulary that the nonrecursive creature scorer understands.
     * Package-private callers use this to keep intrinsic and live future-recipient adapters in
     * lockstep. A null result means that the text contains an unsupported or parameterized form.
     */
    static Set<String> parseSupportedKeywords(final String value) {
        if (value == null) {
            return Set.of();
        }
        final Set<String> result = new java.util.LinkedHashSet<>();
        for (final String piece : value.split("\\s*&\\s*")) {
            final String keyword = piece.trim().toLowerCase(Locale.ROOT);
            if (keyword.isEmpty() || !SUPPORTED_KEYWORDS.contains(keyword)) {
                return null;
            }
            result.add(keyword);
        }
        return Set.copyOf(result);
    }

    /** Values a fixed change on the generic creature used by future-recipient estimates. */
    static int evaluateGenericCreatureDelta(final int powerChange, final int toughnessChange,
            final Set<String> addedKeywords) {
        return evaluateGenericCreatureDelta(powerChange, toughnessChange, Integer.MIN_VALUE,
                Integer.MIN_VALUE, addedKeywords);
    }

    /** Values a fixed set-and-add change on the generic creature used by future estimates. */
    static int evaluateGenericCreatureDelta(final int powerChange, final int toughnessChange,
            final int setPower, final int setToughness, final Set<String> addedKeywords) {
        return averageCreatureDelta(IntrinsicReferenceModel.defaults(), new IntrinsicOutcomeEvaluator(),
                setPower, setToughness, powerChange, toughnessChange, addedKeywords);
    }

    private static int applySetAndAdd(final int before, final int set, final int add) {
        return (set == Integer.MIN_VALUE ? before : set) + add;
    }

    private static Set<String> changeKeywords(final Set<String> original,
            final Set<String> additions, final Set<String> removals) {
        final Set<String> result = new java.util.LinkedHashSet<>(original);
        result.removeIf(keyword -> removals.contains(keyword.toLowerCase(Locale.ROOT)));
        // First-order snapshot semantics, not a full timestamp/dependency layer simulation.
        result.addAll(additions);
        return Set.copyOf(result);
    }

    private static int literalInteger(final String value, final int fallback) {
        if (value == null) {
            return fallback;
        }
        if (!value.trim().matches("-?\\d+")) {
            return Integer.MIN_VALUE;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (final NumberFormatException ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private static Evaluation supported(final double value, final String reason) {
        return new Evaluation(true, value, reason);
    }

    private static Evaluation unsupported(final String reason) {
        return new Evaluation(false, 0, reason);
    }

    record Evaluation(boolean supported, double value, String reason) {
    }
}
