package forge.ai.effect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import forge.card.CardType;
import forge.card.CardTypeView;
import forge.ai.effect.CardAbilityTraversal.AbilityDescription;

/** Recurring other-creature entries, distinct from the source's one-time deployment trigger. */
final class IntrinsicCreatureEntryTriggerAdapter {
    private static final Set<String> PARAMETERS = Set.of("Mode", "Origin", "Destination", "ValidCard", "ValidCards",
            "Execute", "TriggerZones", "TriggerDescription", "Secondary", "ActivationLimit", "Condition");

    private IntrinsicCreatureEntryTriggerAdapter() { }

    /** Printed type facts can exclude the source without suppressing a real self-entry event. */
    static AbilityDescription excludeNonmatchingSource(final AbilityDescription ability, final CardTypeView sourceType) {
        if (ability.origin() != CardAbilityTraversal.Origin.TRIGGER
                || !Set.of("ChangesZone", "ChangesZoneAll").contains(ability.parameters().getOrDefault("Mode", ""))
                || !"Battlefield".equals(ability.parameters().get("Destination"))) { return ability; }
        final String key = "ChangesZoneAll".equals(ability.parameters().get("Mode")) ? "ValidCards" : "ValidCard";
        final String validity = ability.parameters().getOrDefault(key, "");
        final String[] pieces = validity.split("[.+]", -1);
        if (!"Creature".equals(pieces[0]) && !CardType.isACreatureType(pieces[0])) { return ability; }
        String tribe = "Creature".equals(pieces[0]) ? null : pieces[0];
        for (int i = 1; i < pieces.length; i++) {
            if (Set.of("Other", "OppCtrl").contains(pieces[i])) { return ability; }
            if (CardType.isACreatureType(pieces[i])) {
                if (tribe != null) { return ability; }
                tribe = pieces[i];
            }
        }
        if (tribe == null || sourceType.hasCreatureType(tribe)) { return ability; }
        // Unknown conditions/filters remain intact and must still pass the normal evaluation
        // gate. TODO: Intrinsic type-changing self interactions can invalidate printed exclusion.
        final var parameters = new java.util.LinkedHashMap<>(ability.parameters());
        parameters.put(key, validity + "+Other");
        return new AbilityDescription(ability.path(), ability.origin(), ability.provenance(), parameters, ability.outcome());
    }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters,
            final IntrinsicLibraryReference library) {
        return describe(parameters, library, null);
    }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters,
            final IntrinsicLibraryReference library, final IntrinsicReferenceModel.PermanentProfile source) {
        return describe(parameters, IntrinsicReferenceModel.defaults().withLibrary(library), source);
    }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters,
            final IntrinsicReferenceModel model, final IntrinsicReferenceModel.PermanentProfile source) {
        if (parameters == null || !PARAMETERS.containsAll(parameters.keySet())
                || !Set.of("ChangesZone", "ChangesZoneAll").contains(parameters.getOrDefault("Mode", ""))
                || !"Battlefield".equals(parameters.get("Destination"))
                || !"Battlefield".equals(parameters.getOrDefault("TriggerZones", "Battlefield"))) {
            return Optional.empty();
        }
        final boolean batch = "ChangesZoneAll".equals(parameters.get("Mode"));
        if (parameters.containsKey("Condition") && (!"Evolve".equals(parameters.get("Condition"))
                || batch || source == null || !Set.of(IntrinsicReferenceModel.PermanentKind.CREATURE,
                        IntrinsicReferenceModel.PermanentKind.TOKEN).contains(source.kind()))) { return Optional.empty(); }
        final boolean oncePerTurn = "1".equals(parameters.get("ActivationLimit"));
        if (parameters.containsKey("ActivationLimit") && !oncePerTurn || batch && !oncePerTurn
                || parameters.containsKey(batch ? "ValidCard" : "ValidCards")) { return Optional.empty(); }
        final String origin = parameters.getOrDefault("Origin", "Any");
        if (!Set.of("Any", "Hand", "Library", "Graveyard", "Exile").contains(origin)) { return Optional.empty(); }
        final String valid = parameters.getOrDefault(batch ? "ValidCards" : "ValidCard", "");
        // Existing counters on battlefield recipients are not a distribution of entry counters.
        // TODO: Entry replacements and enter-with-counter distributions need separate modeling.
        if (IntrinsicCounterPredicates.parse(valid).isPresent()) { return Optional.empty(); }
        final var profileFilter = IntrinsicStaticRecipientFilter.describe(valid, model);
        final String[] pieces = profileFilter.affected().replace('.', '+').split("\\+", -1);
        if (!"Creature".equals(pieces[0]) && !CardType.isACreatureType(pieces[0])) {
            return Optional.empty();
        }
        IntrinsicEventTrigger.TurnScope scope = IntrinsicEventTrigger.TurnScope.ANY_TURN;
        boolean owner = false;
        boolean other = false;
        boolean tribal = !"Creature".equals(pieces[0]);
        for (int i = 1; i < pieces.length; i++) {
            final String piece = pieces[i];
            if ("Other".equals(piece) && !other) { other = true; }
            else if (Set.of("YouCtrl", "OppCtrl").contains(piece) && !owner) {
                owner = true;
                scope = "YouCtrl".equals(piece) ? IntrinsicEventTrigger.TurnScope.CONTROLLER_TURN
                        : IntrinsicEventTrigger.TurnScope.OPPONENT_TURN;
            } else if (CardType.isACreatureType(piece) && !tribal) { tribal = true; }
            else { return Optional.empty(); }
        }
        // A friendly/all-creature filter including Self also needs a deployment opportunity.
        // Do not silently omit that one-time component or fold it into recurring survival rates.
        final boolean cannotEnterAsCreature = "Creature".equals(pieces[0]) && source != null
                && source.kind() != IntrinsicReferenceModel.PermanentKind.CREATURE
                && source.kind() != IntrinsicReferenceModel.PermanentKind.TOKEN;
        // Self cannot satisfy Evolve's strict comparison with itself, so no deployment
        // component is omitted when its script does not redundantly specify Other.
        if (!other && scope != IntrinsicEventTrigger.TurnScope.OPPONENT_TURN && !cannotEnterAsCreature
                && !"Evolve".equals(parameters.get("Condition"))) {
            return Optional.empty();
        }
        final double originMultiplier = switch (origin) {
        case "Hand" -> .85;
        case "Library" -> .35;
        case "Graveyard", "Exile" -> .15;
        default -> 1;
        };
        // TODO: Self-inclusive creature sources need separate deployment/future credit so removal
        // cannot count already-realized self entry as a lost future benefit. Uncapped batches,
        // instant-speed/token populations,
        // multiple tribes, noncreature Kindred entries, replacements, and watched-card identity/LKI
        // need explicit bindings. Bare tribal filters use the reference creature-tribe population.
        final double eligibleShare = originMultiplier * (tribal ? model.library().tribalCreatureShare() : 1)
                * profileEligibility(model, profileFilter, parameters, source);
        final double multiplier = oncePerTurn ? firstEligibleMultiplier(model, eligibleShare) : eligibleShare;
        return Optional.of(new IntrinsicEventTrigger(IntrinsicReferenceModel.EventType.CREATURE_ENTERED,
                scope, oncePerTurn, multiplier));
    }

    /** An entry is already known to be a creature; absent-board cases are not failed entries. */
    private static double profileEligibility(final IntrinsicReferenceModel model,
            final IntrinsicStaticRecipientFilter.Filter filter, final Map<String, String> parameters,
            final IntrinsicReferenceModel.PermanentProfile source) {
        if (!filter.profileRestricted() && !parameters.containsKey("Condition")) { return 1; }
        double present = 0;
        double eligible = 0;
        for (final var entry : model.creatureProfiles().entries()) {
            if (!entry.value().present()) { continue; }
            present += entry.weight();
            if (filter.matches(entry.value()) && matchesEventCondition(parameters, source,
                    entry.value().power(), entry.value().toughness())) { eligible += entry.weight(); }
        }
        // Reuse the shared generic size/keyword profiles, not a live battlefield census. Tribal
        // membership and origin are independent calibration factors; size/keyword clauses share
        // one profile so contradictory or correlated predicates are not multiplied independently.
        return present > 0 ? eligible / present : 0;
    }

    static boolean matchesEventCondition(final Map<String, String> parameters,
            final IntrinsicReferenceModel.PermanentProfile source, final int power, final int toughness) {
        // A single snapshot supplies the intervening-if comparison. Do not independently
        // multiply power and toughness chances: either greater characteristic is sufficient.
        // TODO: Resolution-time changes and declining eligibility after repeated growth.
        return !parameters.containsKey("Condition") || "Evolve".equals(parameters.get("Condition"))
                && source != null && (power > source.power() || toughness > source.toughness());
    }

    /** Thin individual entries before capping: two potential entries provide two chances to qualify. */
    private static double firstEligibleMultiplier(final IntrinsicReferenceModel model, final double share) {
        final var rates = model.eventRates(IntrinsicReferenceModel.EventType.CREATURE_ENTERED);
        if (rates == null) { return share; }
        double anyEntry = 0;
        double anyEligible = 0;
        for (final var entry : rates.entries()) {
            if (entry.value() <= 0) { continue; }
            anyEntry += Math.min(1, entry.value()) * entry.weight();
            anyEligible += (1 - Math.pow(1 - share, entry.value())) * entry.weight();
        }
        // Eligibility uses independent per-entry reference samples, not real deck correlations.
        return anyEntry > 0 ? anyEligible / anyEntry : 0;
    }
}
