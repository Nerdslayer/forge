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
            "Mode", "Affected", "AddPower", "AddToughness", "AddKeyword", "Description");
    private static final Set<String> SUPPORTED_KEYWORDS = Set.of(
            "flying", "reach", "first strike", "double strike", "menace", "fear", "intimidate",
            "deathtouch", "lifelink", "trample", "vigilance", "defender", "indestructible",
            "hexproof", "shroud", "ward", "shield", "stun", "detain", "can't attack",
            "cantattack", "can't block", "cantblock", "can't untap", "cantuntap");
    private static final double FUTURE_RECIPIENTS = 2.0;
    private static final double TRIBAL_FUTURE_RECIPIENTS = 1.5;
    private static final CreatureProfile DEFAULT_RECIPIENT =
            new CreatureProfile(true, 2, 2, Set.of(), false, false);
    private static final CreatureProfile DEFAULT_ATTACHED_CREATURE =
            PermanentSurvivalEstimator.DEFAULT_AURA_HOST;

    private IntrinsicStaticAbilityEvaluator() {
    }

    static Evaluation evaluate(final CardAbilityTraversal.AbilityDescription ability,
            final PermanentProfile source) {
        if (ability == null || source == null
                || ability.origin() != CardAbilityTraversal.Origin.STATIC) {
            return unsupported("not a static ability");
        }
        if (!ALLOWED_PARAMS.containsAll(ability.parameters().keySet())
                || !"Continuous".equals(ability.parameters().get("Mode"))) {
            return unsupported("unsupported intrinsic static parameters");
        }

        final String affected = ability.parameters().get("Affected");
        final StaticAbilityScope scope = StaticAbilityScope.parse(affected);
        if (scope == null) {
            return unsupported("unsupported intrinsic static recipient scope");
        }
        final Set<String> addedKeywords = parseSupportedKeywords(ability.parameters().get("AddKeyword"));
        if (addedKeywords == null) {
            return unsupported("static keyword is not observed by the intrinsic permanent scorer");
        }
        final int powerChange = literalInteger(ability.parameters().get("AddPower"), 0);
        final int toughnessChange = literalInteger(ability.parameters().get("AddToughness"), 0);
        if (!ability.parameters().containsKey("AddPower")
                && !ability.parameters().containsKey("AddToughness") && addedKeywords.isEmpty()) {
            return unsupported("static effect has no intrinsically valued change");
        }
        if (powerChange == Integer.MIN_VALUE || toughnessChange == Integer.MIN_VALUE
                || powerChange < -2 || toughnessChange <= -2
                || powerChange > 20 || toughnessChange > 20) {
            // Do not pretend a generic reference creature survives a static effect that can reduce
            // toughness to zero. Conditional, dynamic and lethal changes need richer state.
            return unsupported("static P/T change is outside the safe intrinsic range");
        }

        final IntrinsicOutcomeEvaluator evaluator = new IntrinsicOutcomeEvaluator();
        final int perRecipient;
        final double recipientCount;
        if (scope == StaticAbilityScope.SELF) {
            if (source.kind() != PermanentKind.CREATURE && source.kind() != PermanentKind.TOKEN) {
                return unsupported("self P/T static change requires a creature reference source");
            }
            final PermanentProfile after = withPowerAndToughness(source,
                    source.power() + powerChange, source.toughness() + toughnessChange,
                    addedKeywords);
            perRecipient = evaluator.evaluatePermanentDelta(source, after, true);
            recipientCount = 1;
        } else {
            final CreatureProfile before = scope == StaticAbilityScope.ATTACHED
                    ? DEFAULT_ATTACHED_CREATURE : DEFAULT_RECIPIENT;
            final CreatureProfile after = new CreatureProfile(true,
                    before.power() + powerChange, before.toughness() + toughnessChange,
                    plusKeywords(before.keywords(), addedKeywords), before.hexproof(),
                    before.indestructible());
            perRecipient = evaluator.evaluateCreatureDelta(before, after, true);
            recipientCount = scope.isTribal(affected) ? TRIBAL_FUTURE_RECIPIENTS
                    : scope == StaticAbilityScope.ATTACHED ? 1 : FUTURE_RECIPIENTS;
        }

        final double signedValue = switch (scope) {
        case SELF, ATTACHED, CONTROLLER -> perRecipient * recipientCount;
        case OPPONENT -> -perRecipient * recipientCount;
        case BOTH -> 0;
        };
        return supported(signedValue, scope.description() + " static characteristic potential");
    }

    private static PermanentProfile withPowerAndToughness(final PermanentProfile source,
            final int power, final int toughness, final Set<String> addedKeywords) {
        final Set<String> keywords = plusKeywords(source.keywords(), addedKeywords);
        return new PermanentProfile(true, source.kind(), source.controlledByAi(),
                Math.max(0, power), Math.max(0, toughness), keywords,
                source.basicLand(), source.loyalty());
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
        final CreatureProfile before = DEFAULT_RECIPIENT;
        final CreatureProfile after = new CreatureProfile(true,
                before.power() + powerChange, before.toughness() + toughnessChange,
                plusKeywords(before.keywords(), addedKeywords), before.hexproof(),
                before.indestructible());
        return new IntrinsicOutcomeEvaluator().evaluateCreatureDelta(before, after, true);
    }

    private static Set<String> plusKeywords(final Set<String> original,
            final Set<String> additions) {
        final Set<String> result = new java.util.LinkedHashSet<>(original);
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
