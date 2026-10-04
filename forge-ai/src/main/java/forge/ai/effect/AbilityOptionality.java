package forge.ai.effect;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Validated script-semantic views; original descriptions remain unchanged for attribution. */
final class AbilityOptionality {
    record Decision(boolean optional, boolean opponent, String issue) {
        boolean supported() { return issue.isEmpty(); }
    }

    private static final Decision MANDATORY = new Decision(false, false, "");
    private AbilityOptionality() { }

    static Decision trigger(final Map<String, String> parameters) {
        return decider(parameters.get("OptionalDecider"));
    }

    static Map<String, String> triggerParameters(final Map<String, String> parameters) {
        final Decision decision = trigger(parameters);
        if (!decision.supported() || !decision.optional()) { return parameters; }
        final Map<String, String> result = new HashMap<>(parameters);
        result.remove("OptionalDecider");
        return Map.copyOf(result);
    }

    static Decision effect(final AbilityOutcomeDescription node) {
        final Map<String, String> parameters = node.parameters();
        if (parameters.containsKey("Optional") && parameters.containsKey("OptionalDecider")) {
            return unresolved("Ambiguous optional effect metadata");
        }
        if (parameters.containsKey("OptionalDecider")) {
            if ("DealDamage".equals(node.api())) { return decider(parameters.get("OptionalDecider")); }
            // DrawEffect asks the recipient, not the OptionalDecider expression.
            if ("Draw".equals(node.api())) { return recipient(parameters); }
            return unresolved("Unsupported effect-level optional decider");
        }
        if (!parameters.containsKey("Optional")) { return MANDATORY; }
        if (!"True".equalsIgnoreCase(parameters.get("Optional"))) {
            return unresolved("Unsupported Optional value");
        }
        // These effects ask the activator before this node. AbilityUtils executes the next
        // subability independently, so it must remain outside this optional boundary.
        if (Set.of("PutCounter", "RemoveCounter", "Mana", "GainControl", "CopyPermanent", "Charm", "Dig")
                .contains(node.api())) { return new Decision(true, false, ""); }
        if ("Sacrifice".equals(node.api()) && "Self".equals(
                parameters.getOrDefault("SacValid", "Self"))) {
            return new Decision(true, false, "");
        }
        if ("Discard".equals(node.api()) || "Sacrifice".equals(node.api())) {
            return recipient(parameters);
        }
        if ("Scry".equals(node.api()) || "Surveil".equals(node.api())) {
            return recipient(parameters);
        }
        // TODO: Per-recipient/group decisions, up-to amounts, payment/unless choices,
        // remembered "if you do" continuations, and ambiguous random-information boundaries.
        return unresolved("Unsupported optional effect boundary");
    }

    private static Decision recipient(final Map<String, String> parameters) {
        if (parameters.containsKey("ValidTgts")) { return unresolved("Target-dependent optional recipient"); }
        return decider(parameters.getOrDefault("Defined", "You"));
    }

    private static Decision decider(final String value) {
        if (value == null) { return MANDATORY; }
        if ("You".equals(value)) { return new Decision(true, false, ""); }
        if ("Opponent".equals(value)) { return new Decision(true, true, ""); }
        // TODO: Multiplayer and remembered/targeted deciders require explicit public context.
        return unresolved("Unsupported optional decider: " + value);
    }

    private static Decision unresolved(final String issue) { return new Decision(true, false, issue); }

    static AbilityOutcomeDescription effectParameters(final AbilityOutcomeDescription node) {
        if (!effect(node).supported()) { return node; }
        final Map<String, String> parameters = new HashMap<>(node.parameters());
        parameters.remove("Optional");
        parameters.remove("OptionalDecider");
        return new AbilityOutcomeDescription(node.path(), node.api(), parameters, node.choices(),
                node.next(), node.issue());
    }
}
