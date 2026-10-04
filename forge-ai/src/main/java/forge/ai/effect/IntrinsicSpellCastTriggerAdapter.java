package forge.ai.effect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import forge.game.trigger.TriggerType;

/** Recognizes the first conservative slice of intrinsic spell-cast trigger filters. */
final class IntrinsicSpellCastTriggerAdapter {
    private static final java.util.regex.Pattern MANA_COMPARISON = java.util.regex.Pattern.compile("(.+)[.+]cmc(GE|GT|LE|LT|EQ|NE)(\\d+)");
    private static final Set<String> SUPPORTED_PARAMETERS = Set.of(
            "Mode", "ValidCard", "ValidActivatingPlayer", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> SUPPORTED_CARD_FILTERS = Set.of(
            "Card", "Instant", "Sorcery", "Instant,Sorcery", "Sorcery,Instant", "Creature",
            "Artifact", "Enchantment", "Planeswalker", "Land", "nonCreature", "Card.nonCreature");
    private static final Set<String> SUPPORTED_PLAYER_FILTERS = Set.of(
            "You", "Opponent", "Player", "Player.Opponent");

    private IntrinsicSpellCastTriggerAdapter() {
    }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters) {
        return describe(parameters, IntrinsicReferenceModel.defaults());
    }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters, final IntrinsicReferenceModel model) {
        if (!supports(parameters)) {
            return Optional.empty();
        }
        // Flash/instant-speed casting during another player's turn and the frequency of copied
        // spells remain intentionally deferred.
        final var comparison = manaComparison(parameters.getOrDefault("ValidCard", "Card"));
        final double eligibility = comparison == null ? 1 : model.quantities()
                .distribution(IntrinsicReferenceQuantities.Quantity.CAST_SPELL_MANA_VALUE).entries().stream()
                .filter(entry -> comparison.test(entry.value())).mapToDouble(WeightedValue::weight).sum();
        return Optional.of(new IntrinsicEventTrigger(IntrinsicReferenceModel.EventType.SPELL_CAST,
                turnScope(parameters), false, eligibility));
    }

    static boolean supports(final Map<String, String> parameters) {
        if (parameters == null || EventTriggerParser.mode(parameters) != TriggerType.SpellCast
                || !SUPPORTED_PARAMETERS.containsAll(parameters.keySet())
                || !SUPPORTED_PLAYER_FILTERS.contains(
                        parameters.getOrDefault("ValidActivatingPlayer", "Player"))) {
            return false;
        }
        if (parameters.containsKey("TriggerZones")
                && !"Battlefield".equalsIgnoreCase(parameters.get("TriggerZones"))) {
            return false;
        }
        final String validity = parameters.getOrDefault("ValidCard", "Card");
        return SUPPORTED_CARD_FILTERS.contains(validity) || manaComparison(validity) != null;
    }

    private static java.util.function.IntPredicate manaComparison(final String validity) {
        final var match = MANA_COMPARISON.matcher(validity);
        if (!match.matches() || !SUPPORTED_CARD_FILTERS.contains(match.group(1)) || match.group(1).contains(",")
                || "Land".equals(match.group(1))) { return null; }
        final int bound;
        try { bound = Integer.parseInt(match.group(3)); }
        catch (final NumberFormatException invalid) { return null; }
        // TODO: Joint spell-type / curve / X-paid distributions, qualified unions, variable
        // thresholds and spell identity bindings. Existing unqualified type calibration stays
        // unchanged; this independently thins it by a conditional cast-spell mana-value prior.
        return switch (match.group(2)) {
        case "GE" -> value -> value >= bound;
        case "GT" -> value -> value > bound;
        case "LE" -> value -> value <= bound;
        case "LT" -> value -> value < bound;
        case "EQ" -> value -> value == bound;
        default -> value -> value != bound;
        };
    }

    private static IntrinsicEventTrigger.TurnScope turnScope(final Map<String, String> parameters) {
        return switch (parameters.getOrDefault("ValidActivatingPlayer", "Player")) {
        case "You" -> IntrinsicEventTrigger.TurnScope.CONTROLLER_TURN;
        case "Opponent", "Player.Opponent" -> IntrinsicEventTrigger.TurnScope.OPPONENT_TURN;
        default -> IntrinsicEventTrigger.TurnScope.ANY_TURN;
        };
    }
}
