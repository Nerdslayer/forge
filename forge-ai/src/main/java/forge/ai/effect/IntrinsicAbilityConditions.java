package forge.ai.effect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Binds intrinsic root conditions without evaluating live or hidden game information. */
final class IntrinsicAbilityConditions {
    static final String PRESENT_COUNT = "IntrinsicPresenceCount";
    static final String LIFE_COUNT = "IntrinsicConditionLifeTotal";
    static final String LIFE_COMPARISON = "IntrinsicConditionLifeComparison";
    record Result(Map<String, String> parameters, boolean inactive) { }

    private IntrinsicAbilityConditions() { }

    static Map<String, String> prepare(final Map<String, String> parameters) {
        final Map<String, String> prepared = new LinkedHashMap<>(parameters);
        final String presence = presenceExpression(parameters);
        if (presence != null) { prepared.put(PRESENT_COUNT, presence); }
        final String life = switch (parameters.getOrDefault("LifeTotal", "")) {
        case "You" -> "Count$YourLifeTotal";
        case "OpponentSmallest", "OpponentGreatest" -> "Count$OppGreatestLifeTotal";
        default -> null;
        };
        if (life != null) {
            // The intrinsic reference has one opponent. Do not generalize min/max to multiplayer.
            prepared.put(LIFE_COUNT, life);
            prepared.put(LIFE_COMPARISON, parameters.getOrDefault("LifeAmount", "GE1"));
        }
        return Map.copyOf(prepared);
    }

    private static String presenceExpression(final Map<String, String> parameters) {
        if (!parameters.containsKey("IsPresent")) { return null; }
        final String zone = parameters.get("PresentZone");
        if ("Graveyard".equals(zone) || "Hand".equals(zone)) {
            if (parameters.containsKey("PresentDefined")) { return null; }
            final String player = parameters.getOrDefault("PresentPlayer", "Any");
            final String filter = parameters.get("IsPresent");
            final String[] pieces = filter.split("\\.", -1);
            if (pieces.length > 2 || !("Hand".equals(zone) ? Set.of("Card") : Set.of("Card", "Creature")).contains(pieces[0])) { return null; }
            final String owner = pieces.length == 2 ? switch (pieces[1]) {
            case "YouOwn", "YouCtrl" -> "You";
            case "OppOwn", "OppCtrl" -> "Opponent";
            default -> null;
            } : Set.of("You", "Opponent").contains(player) ? player : null;
            if (owner == null || !"Any".equals(player) && !owner.equals(player)) { return null; }
            return "Count$Valid" + zone + " " + pieces[0] + ("You".equals(owner) ? ".YouOwn" : ".OppOwn");
        }
        return supportedPresenceScope(parameters) ? "Count$Valid " + parameters.get("IsPresent") : null;
    }

    private static boolean supportedPresenceScope(final Map<String, String> parameters) {
        final String filter = parameters.get("IsPresent");
        if (!"Battlefield".equals(parameters.getOrDefault("PresentZone", "Battlefield"))) { return false; }
        if (parameters.containsKey("PresentDefined")
                && (!"Self".equals(parameters.get("PresentDefined"))
                    || !filter.startsWith("Card.Self") && !filter.startsWith("Creature.Self"))) { return false; }
        final String player = parameters.getOrDefault("PresentPlayer", "Any");
        return "Any".equals(player) || "You".equals(player)
                && (filter.contains("YouCtrl") || filter.contains("Self"))
                || "Opponent".equals(player) && filter.contains("OppCtrl");
    }

    static Result resolve(final Map<String, String> parameters) {
        final Map<String, String> remaining = new LinkedHashMap<>(parameters);
        boolean inactive = false;
        final Boolean svar = compare(parameters.get("CheckSVar"), parameters.getOrDefault("SVarCompare", "GE1"));
        if (svar != null) {
            inactive |= !svar;
            remaining.remove("CheckSVar");
            remaining.remove("SVarCompare");
        }
        final Boolean presence = compare(parameters.get(PRESENT_COUNT), parameters.getOrDefault("PresentCompare", "GE1"));
        remaining.remove(PRESENT_COUNT);
        if (presence != null) {
            inactive |= !presence;
            for (final String field : Set.of("IsPresent", "PresentCompare", "PresentDefined", "PresentPlayer", "PresentZone")) {
                remaining.remove(field);
            }
        }
        final Boolean life = compare(parameters.get(LIFE_COUNT), parameters.get(LIFE_COMPARISON));
        remaining.remove(LIFE_COUNT);
        remaining.remove(LIFE_COMPARISON);
        if (life != null) {
            inactive |= !life;
            remaining.remove("LifeTotal");
            remaining.remove("LifeAmount");
        }
        // TODO: Compound/unmodeled variable comparators, intervening-if state evolution, histories,
        // ActivePlayer life bindings, additional zones, subtype predicates and resolution-time outcome conditions.
        // Unresolved fields remain on the description for existing adapters to reject.
        return new Result(Map.copyOf(remaining), inactive);
    }

    static Boolean compare(final String value, final String comparator) {
        if (value == null || !value.matches("-?\\d+")
                || comparator == null || !comparator.matches("(?:GE|GT|LE|LT|EQ|NE)-?\\d+")) { return null; }
        try {
            final int left = Integer.parseInt(value);
            final int right = Integer.parseInt(comparator.substring(2));
            return switch (comparator.substring(0, 2)) {
            case "GE" -> left >= right;
            case "GT" -> left > right;
            case "LE" -> left <= right;
            case "LT" -> left < right;
            case "EQ" -> left == right;
            default -> left != right;
            };
        } catch (final NumberFormatException invalid) { return null; }
    }
}
