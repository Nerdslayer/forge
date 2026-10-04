package forge.ai.effect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import forge.card.CardType;
import forge.card.CardTypeView;
import forge.ai.effect.CardAbilityTraversal.AbilityDescription;

/** Recurring other-creature deaths, distinct from the source's own departure opportunity. */
final class IntrinsicCreatureDeathTriggerAdapter {
    private static final Set<String> PARAMETERS = Set.of("Mode", "Origin", "Destination", "ValidCard",
            "Execute", "TriggerZones", "TriggerDescription", "Secondary");

    private IntrinsicCreatureDeathTriggerAdapter() { }

    private record Filter(String tribe, boolean other, boolean opponent, int tokenStatus) { }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters,
            final IntrinsicReferenceModel model) {
        if (!PARAMETERS.containsAll(parameters.keySet()) || !"ChangesZone".equals(parameters.get("Mode"))
                || !"Battlefield".equals(parameters.get("Origin"))
                || !"Graveyard".equals(parameters.get("Destination"))
                || !"Battlefield".equals(parameters.getOrDefault("TriggerZones", "Battlefield"))) {
            return Optional.empty();
        }
        final var characteristics = IntrinsicStaticRecipientFilter.describe(parameters.getOrDefault("ValidCard", ""), model);
        final Filter filter = filter(characteristics.affected());
        if (filter == null || !filter.other() && !filter.opponent()) { return Optional.empty(); }
        double eligible = filter.tribe() == null ? 1 : model.library().tribalCreatureShare();
        if (filter.tokenStatus() >= 0) {
            eligible *= model.quantities().distribution(IntrinsicReferenceQuantities.Quantity.DYING_CREATURE_IS_TOKEN)
                    .entries().stream().filter(entry -> entry.value() == filter.tokenStatus())
                    .mapToDouble(WeightedValue::weight).sum();
        }
        if (characteristics.profileRestricted()) {
            final double present = model.creatureProfiles().entries().stream().filter(entry -> entry.value().present())
                    .mapToDouble(WeightedValue::weight).sum();
            eligible *= present == 0 ? 0 : model.creatureProfiles().entries().stream()
                    .filter(entry -> entry.value().present() && characteristics.matches(entry.value()))
                    .mapToDouble(WeightedValue::weight).sum() / present;
        }
        // Deaths can happen on either player's turn. Ownership selects the watched population,
        // not a controller-turn restriction. Preserve the existing generic death-rate calibration.
        // TODO: Death-specific tribal/token correlations, Kindred noncreature deaths, event-card
        // death-specific size distributions, caps/batches and self-inclusive sources need explicit modeling.
        return Optional.of(new IntrinsicEventTrigger(IntrinsicReferenceModel.EventType.CREATURE_DIED,
                IntrinsicEventTrigger.TurnScope.ANY_TURN, false, eligible));
    }

    /** Definition type facts can prove that a typed event excludes the printed source. */
    static AbilityDescription excludeNonmatchingSource(final AbilityDescription ability, final CardTypeView sourceType) {
        if (ability.origin() != CardAbilityTraversal.Origin.TRIGGER
                || !"ChangesZone".equals(ability.parameters().get("Mode"))
                || !"Battlefield".equals(ability.parameters().get("Origin"))
                || !"Graveyard".equals(ability.parameters().get("Destination"))) { return ability; }
        final String validity = ability.parameters().getOrDefault("ValidCard", "");
        final Filter filter = filter(validity);
        if (filter == null || filter.tribe() == null || filter.other() || filter.opponent()
                || sourceType.hasCreatureType(filter.tribe())) { return ability; }
        // Intrinsic definition analysis uses printed characteristics. Do not infer that a source
        // with that tribe cannot die, or erase its own death trigger by applying recurring survival.
        // TODO: Type-changing self interactions can invalidate this printed-type exclusion.
        final Map<String, String> parameters = new LinkedHashMap<>(ability.parameters());
        parameters.put("ValidCard", validity + "+Other");
        return new AbilityDescription(ability.path(), ability.origin(), ability.provenance(), parameters, ability.outcome());
    }

    private static Filter filter(final String validity) {
        final String[] pieces = validity.split("[.+]", -1);
        if (!"Creature".equals(pieces[0]) && !CardType.isACreatureType(pieces[0])) { return null; }
        String tribe = "Creature".equals(pieces[0]) ? null : pieces[0];
        boolean other = false;
        String owner = null;
        int tokenStatus = -1;
        for (int i = 1; i < pieces.length; i++) {
            final String part = pieces[i];
            if ("Other".equals(part) && !other) { other = true; }
            else if (Set.of("YouCtrl", "OppCtrl").contains(part) && owner == null) { owner = part; }
            else if (Set.of("token", "!token").contains(part) && tokenStatus < 0) { tokenStatus = "token".equals(part) ? 1 : 0; }
            else if (CardType.isACreatureType(part) && tribe == null) { tribe = part; }
            else { return null; }
        }
        return new Filter(tribe, other, "OppCtrl".equals(owner), tokenStatus);
    }
}
