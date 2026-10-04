package forge.ai.effect;

import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import forge.card.CardType;
import forge.card.CardTypeView;
import forge.ai.effect.CardAbilityTraversal.AbilityDescription;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;

/** Player-hit opportunities from a supported creature population, not just the host. */
final class IntrinsicGroupCombatDamageAdapter {
    private static final String SOURCE_MATCHES = "IntrinsicSourceMatchesCombatFilter";
    private static final Set<String> PARAMETERS = Set.of("Mode", "ValidSource", "ValidTarget", "CombatDamage",
            "Execute", "TriggerZones", "TriggerDescription", "Secondary", SOURCE_MATCHES);
    private record Filter(boolean friendly, Set<String> tribes, boolean other) { }

    private IntrinsicGroupCombatDamageAdapter() { }

    static Optional<String> damagedPlayer(final Map<String, String> parameters) {
        if (describe(parameters, IntrinsicReferenceModel.defaults(), null).isEmpty()) { return Optional.empty(); }
        final Filter filter = filter(parameters.get("ValidSource"));
        // In the 1v1 reference a friendly combat hit reaches the opponent and vice versa.
        // An opposing source cannot satisfy an explicitly opponent-only target restriction.
        if (!filter.friendly() && !"Player".equals(parameters.get("ValidTarget"))) { return Optional.empty(); }
        return Optional.of(filter.friendly() ? "Opponent" : "You");
    }

    static AbilityDescription prepare(final AbilityDescription ability, final CardTypeView sourceType) {
        // Root conditions are resolved later. They must not prevent collecting immutable
        // printed type facts now; unsupported fields are retained and still block evaluation.
        if (ability.origin() != CardAbilityTraversal.Origin.TRIGGER
                || !"DamageDone".equals(ability.parameters().get("Mode"))
                || !"True".equals(ability.parameters().get("CombatDamage"))) { return ability; }
        final Filter filter = filter(ability.parameters().get("ValidSource"));
        if (filter == null) { return ability; }
        final boolean matches = filter.friendly() && !filter.other() && sourceType.isCreature()
                && (filter.tribes().isEmpty() || filter.tribes().stream().anyMatch(sourceType::hasCreatureType));
        final var parameters = new java.util.LinkedHashMap<>(ability.parameters());
        parameters.put(SOURCE_MATCHES, String.valueOf(matches));
        return new AbilityDescription(ability.path(), ability.origin(), ability.provenance(), parameters, ability.outcome());
    }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters,
            final IntrinsicReferenceModel model, final PermanentProfile source) {
        if (!PARAMETERS.containsAll(parameters.keySet()) || !"DamageDone".equals(parameters.get("Mode"))
                || !"True".equals(parameters.get("CombatDamage"))
                || !Set.of("Player", "Opponent", "Player.Opponent").contains(parameters.getOrDefault("ValidTarget", ""))
                || !"Battlefield".equals(parameters.getOrDefault("TriggerZones", "Battlefield"))
                || parameters.containsKey(SOURCE_MATCHES) && !Set.of("true", "false").contains(parameters.get(SOURCE_MATCHES))) {
            return Optional.empty();
        }
        final Filter filter = filter(parameters.getOrDefault("ValidSource", ""));
        if (filter == null) { return Optional.empty(); }
        final double hits = donors(parameters, filter, model, source).stream().mapToDouble(WeightedValue::weight).sum();
        // A per-creature COMBAT_DAMAGE reference hit rate is thinned by positive-power/nondefender
        // eligibility and multiplied by the reference population, not by assuming every attack hits.
        // TODO: Evasion-specific hit rates, correlated tribes, token/size/color filters, simultaneous damage aggregates,
        // shared target identities and actual combat projection. Unqualified mixed-owner filters
        // need separate scopes. Printed host eligibility does not model type-changing abilities.
        return Optional.of(new IntrinsicEventTrigger(IntrinsicReferenceModel.EventType.COMBAT_DAMAGE,
                filter.friendly() ? IntrinsicEventTrigger.TurnScope.CONTROLLER_TURN : IntrinsicEventTrigger.TurnScope.OPPONENT_TURN,
                false, hits));
    }

    static Optional<List<WeightedValue<IntrinsicDamageAmountBinding>>> amountCases(final AbilityDescription ability,
            final Map<String, String> variables, final IntrinsicReferenceModel model, final PermanentProfile source) {
        if (ability.origin() != CardAbilityTraversal.Origin.TRIGGER
                || describe(AbilityOptionality.triggerParameters(ability.parameters()), model, source).isEmpty()
                || !variables.values().stream().anyMatch(IntrinsicDamageAmountBinding::quantity)
                    && !IntrinsicDamageAmountBinding.needed(ability.outcome())) { return Optional.empty(); }
        final var parameters = AbilityOptionality.triggerParameters(ability.parameters());
        final var donors = donors(parameters, filter(parameters.get("ValidSource")), model, source);
        final double total = donors.stream().mapToDouble(WeightedValue::weight).sum();
        if (total == 0) { return Optional.of(List.of()); }
        final Map<Integer, Double> amounts = new java.util.TreeMap<>();
        for (final var donor : donors) {
            for (final var amount : IntrinsicOutgoingCombatDamageBinding.amounts(donor.value())) {
                amounts.merge(amount.value().amount(), donor.weight() * amount.weight() / total, Double::sum);
            }
        }
        // Conditional per-hit distribution: a double-striker contributes twice as many event
        // samples, never twice the damage in a single event. Collapse identical amounts so the
        // full keyword/profile Cartesian product does not multiply outcome work unnecessarily.
        return Optional.of(amounts.entrySet().stream().map(entry ->
                new WeightedValue<>(new IntrinsicDamageAmountBinding(entry.getKey()), entry.getValue())).toList());
    }

    private static List<WeightedValue<PermanentProfile>> donors(final Map<String, String> parameters,
            final Filter filter, final IntrinsicReferenceModel model, final PermanentProfile source) {
        final List<WeightedValue<PermanentProfile>> donors = new java.util.ArrayList<>();
        final double tribeShare = filter.tribes().isEmpty() ? 1
                : 1 - Math.pow(1 - model.library().tribalCreatureShare(), filter.tribes().size());
        final double count = (filter.friendly() ? model.friendlyCreatureCounts() : model.opposingCreatureCounts())
                .entries().stream().mapToDouble(entry -> entry.value() * entry.weight()).sum();
        final double present = model.creatureProfiles().entries().stream().filter(entry -> entry.value().present())
                .mapToDouble(WeightedValue::weight).sum();
        for (final var entry : model.creatureProfiles().entries()) {
            final var profile = entry.value();
            if (!profile.present() || present == 0 || count == 0) { continue; }
            final var donor = new PermanentProfile(true, PermanentKind.CREATURE, filter.friendly(),
                    profile.power(), profile.toughness(), profile.keywords());
            final double weight = count * tribeShare * entry.weight() / present * hitMultiplicity(donor);
            if (weight > 0) { donors.add(new WeightedValue<>(donor, weight)); }
        }
        final boolean hostMatches = filter.friendly() && !filter.other() && source != null
                && (parameters.containsKey(SOURCE_MATCHES) ? "true".equals(parameters.get(SOURCE_MATCHES)) : filter.tribes().isEmpty());
        if (hostMatches && hitMultiplicity(source) > 0) { donors.add(new WeightedValue<>(source, hitMultiplicity(source))); }
        return donors;
    }

    private static double hitMultiplicity(final PermanentProfile profile) {
        if (!profile.present() || profile.power() <= 0 || !Set.of(PermanentKind.CREATURE, PermanentKind.TOKEN).contains(profile.kind())
                || profile.keywords().stream().anyMatch("Defender"::equalsIgnoreCase)) { return 0; }
        return profile.keywords().stream().anyMatch("Double strike"::equalsIgnoreCase) ? 2 : 1;
    }

    private static Filter filter(final String validity) {
        if (validity == null) { return null; }
        final Set<String> tribes = new java.util.HashSet<>();
        Boolean friendly = null;
        Boolean other = null;
        boolean allCreatures = false;
        for (final String alternative : validity.split(",", -1)) {
            final String[] pieces = alternative.trim().split("[.+]", -1);
            if (!"Creature".equals(pieces[0]) && !CardType.isACreatureType(pieces[0])) { return null; }
            String tribe = "Creature".equals(pieces[0]) ? null : pieces[0];
            Boolean owner = null;
            boolean excludesHost = false;
            for (int i = 1; i < pieces.length; i++) {
                final String part = pieces[i];
                if (Set.of("YouCtrl", "OppCtrl").contains(part) && owner == null) { owner = "YouCtrl".equals(part); }
                else if ("Other".equals(part) && !excludesHost) { excludesHost = true; }
                else if (CardType.isACreatureType(part) && tribe == null) { tribe = part; }
                else { return null; }
            }
            if (owner == null || friendly != null && !friendly.equals(owner)
                    || other != null && other != excludesHost) { return null; }
            friendly = owner;
            other = excludesHost;
            if (tribe == null) { allCreatures = true; } else { tribes.add(tribe); }
        }
        return new Filter(Boolean.TRUE.equals(friendly), allCreatures ? Set.of() : Set.copyOf(tribes), Boolean.TRUE.equals(other));
    }
}
