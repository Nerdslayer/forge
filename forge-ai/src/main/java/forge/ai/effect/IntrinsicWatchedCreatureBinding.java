package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import forge.ai.effect.CardAbilityTraversal.AbilityDescription;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;

/** A watched entering or dying creature is distinct from the host and generic removal targets. */
record IntrinsicWatchedCreatureBinding(PermanentProfile creature) {
    static boolean needed(final AbilityDescription ability, final Map<String, String> variables) {
        return variables.values().stream().anyMatch(IntrinsicWatchedCreatureBinding::quantity)
                || needsBinding(ability.outcome());
    }

    private static boolean quantity(final String expression) {
        return expression != null && expression.matches("TriggeredCard\\$Card(Power|Toughness)(/.*)?");
    }

    private static boolean needsBinding(final AbilityOutcomeDescription node) {
        return node != null && (node.parameters().values().stream().anyMatch(value ->
                "TriggeredCard".equals(value) || "TriggeredCardController".equals(value) || quantity(value))
                || node.choices().stream().anyMatch(IntrinsicWatchedCreatureBinding::needsBinding)
                || needsBinding(node.next()));
    }

    static Optional<List<WeightedValue<IntrinsicWatchedCreatureBinding>>> cases(final AbilityDescription ability,
            final IntrinsicReferenceModel model, final PermanentProfile host) {
        final var parameters = AbilityOptionality.triggerParameters(ability.parameters());
        if (ability.origin() != CardAbilityTraversal.Origin.TRIGGER
                || !"ChangesZone".equals(parameters.get("Mode"))) { return Optional.empty(); }
        final boolean death = "Battlefield".equals(parameters.get("Origin")) && "Graveyard".equals(parameters.get("Destination"));
        final var event = death ? IntrinsicCreatureDeathTriggerAdapter.describe(parameters, model).orElse(null)
                : IntrinsicCreatureEntryTriggerAdapter.describe(parameters, model, host).orElse(null);
        if (event == null) { return Optional.empty(); }
        final var owners = java.util.Arrays.asList(parameters.getOrDefault("ValidCard", "").split("[.+]"));
        final var filter = IntrinsicStaticRecipientFilter.describe(parameters.get("ValidCard"), model);
        final var eligible = model.creatureProfiles().entries().stream()
                .filter(entry -> entry.value().present() && filter.matches(entry.value())
                        && IntrinsicCreatureEntryTriggerAdapter.matchesEventCondition(parameters, host,
                                entry.value().power(), entry.value().toughness())).toList();
        final double total = eligible.stream().mapToDouble(WeightedValue::weight).sum();
        if (total == 0) { return Optional.of(List.of()); }
        final List<WeightedValue<Integer>> controllerCases = owners.contains("YouCtrl")
                ? List.of(new WeightedValue<>(0, 1.0)) : owners.contains("OppCtrl")
                ? List.of(new WeightedValue<>(1, 1.0))
                : model.quantities().distribution(IntrinsicReferenceQuantities.Quantity.WATCHED_CREATURE_IS_OPPONENT).entries();
        // Qualifying entries are conditioned here only for outcome value. Occurrence still uses
        // the original full reference model, including eligibility and the once-per-turn cap.
        // TriggerChangesZone supplies CardLKI as TriggeredCard for battlefield departures.
        // TODO: Calibrated death-specific and controller-dependent size/keyword populations,
        // multiplayer identities and changing controllers require richer event snapshots.
        // Controller identity is conditional on one occurrence; do not separately count an
        // event for each side or choose a fresh controller in each outcome continuation.
        return Optional.of(eligible.stream().flatMap(entry -> {
            final var profile = entry.value();
            final Set<String> keywords = new java.util.HashSet<>(profile.keywords());
            if (profile.hexproof()) { keywords.add("Hexproof"); }
            if (profile.indestructible()) { keywords.add("Indestructible"); }
            return controllerCases.stream().map(controller -> new WeightedValue<>(new IntrinsicWatchedCreatureBinding(new PermanentProfile(true,
                    PermanentKind.CREATURE, controller.value() == 0, profile.power(), profile.toughness(), keywords)),
                    entry.weight() / total * controller.weight()));
        }).toList());
    }

    Map<String, String> bindVariables(final Map<String, String> variables) {
        final Map<String, String> bound = new java.util.LinkedHashMap<>(variables);
        bound.replaceAll((name, expression) -> bindQuantity(expression));
        return Map.copyOf(bound);
    }

    AbilityOutcomeDescription bindOutcome(final AbilityOutcomeDescription node) {
        if (node == null || Set.of("ImmediateTrigger", "DelayedTrigger").contains(node.api())) { return node; }
        final Map<String, String> parameters = new java.util.LinkedHashMap<>(node.parameters());
        parameters.replaceAll((name, expression) -> bindQuantity(expression));
        if ("TriggeredCardController".equals(parameters.get("Defined"))) {
            parameters.put("Defined", creature.controlledByAi() ? "You" : "Opponent");
        }
        // TODO: Batch/event-object targets, mutable resolution-time characteristics, LKI, entry
        // replacements and delayed event scopes require explicit object state, not this snapshot.
        return new AbilityOutcomeDescription(node.path(), node.api(), parameters,
                node.choices().stream().map(this::bindOutcome).toList(), bindOutcome(node.next()), node.issue());
    }

    private String bindQuantity(final String expression) {
        if (!quantity(expression)) { return expression; }
        final String quantity = expression.substring("TriggeredCard$".length());
        final boolean power = quantity.startsWith("CardPower");
        final int slash = quantity.indexOf('/');
        return "Number$" + (power ? creature.power() : creature.toughness())
                + (slash < 0 ? "" : quantity.substring(slash));
    }
}
