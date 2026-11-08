package forge.ai.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import forge.ai.ability.AnimateAi;
import forge.ai.effect.CombatStaticProjectionPreparation;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CardLists;
import forge.game.combat.Combat;
import forge.game.cost.CostTapType;
import forge.game.player.Player;
import forge.game.staticability.StaticAbilityMode;
import forge.game.zone.ZoneType;

/** Read-only standard crew preparation; payment and opponent choices are never executed. */
final class CombatCrewPreparation {
    private CombatCrewPreparation() { }
    private static final int MAX_RESPONSES = 128;

    private record Vehicle(Card animated, List<Set<Integer>> payments) { }

    static Optional<PreparedCombatCrewResponses> prepare(final Player observer, final Combat alternatives,
            final BooleanSupplier checkpoint) {
        final List<Vehicle> vehicles = new ArrayList<>();
        final Map<Integer, Long> identities = new LinkedHashMap<>();
        final List<String> reasons = new ArrayList<>();
        final Map<Integer, List<String>> rules = new LinkedHashMap<>();
        final List<Card> battlefield = observer.getGame().getCardsIn(ZoneType.Battlefield).stream().toList();
        for (final Card card : battlefield) {
            if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
            if (card.getController() == observer || !card.getController().isOpponentOf(observer) || card.isFaceDown()
                    || card.isPhasedOut() || card.isCreature() || card.isTapped() || !card.getType().hasSubtype("Vehicle")) { continue; }
            final Set<Set<Integer>> payments = new LinkedHashSet<>();
            Card animated = null;
            for (final var original : card.getSpellAbilities()) {
                if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
                if (!original.isCrew() || original.isSuppressed()) { continue; }
                final var ability = original.copy(card, card.getController(), false, true);
                rules.computeIfAbsent(card.getId(), key -> new ArrayList<>()).add(new java.util.TreeMap<>(ability.getMapParams()).toString());
                if (card.isDetained() || !ability.checkRestrictions(card, card.getController())
                        || !ability.getRestrictions().canPlay(card, ability)) { continue; }
                // The engine permits ordinary instant-speed activated abilities independently
                // of sorcery timing; restrictions also enforce activation limits and controller.
                if (ability.getApi() != ApiType.Animate || ability.usesTargeting() || ability.getSubAbility() != null
                        || !Set.of("AB", "Cost", "PrecostDesc", "CostDesc", "Secondary", "Defined", "Types", "SpellDescription")
                                .containsAll(ability.getMapParams().keySet())
                        || !"Self".equals(ability.getParamOrDefault("Defined", "Self"))
                        || !"Artifact,Creature".equals(ability.getParam("Types"))
                        || ability.getPayCosts().getCostParts().size() != 1
                        || !(ability.getPayCosts().getCostParts().get(0) instanceof CostTapType tap)
                        || !tap.getType().matches("Creature.Other\\+withTotalPowerGE[0-9]{1,4}")) {
                    reasons.add("Crew response needs nonstandard cost/animation projection: " + card.getId());
                    continue;
                }
                if (!tap.canPay(ability, card.getController(), false)) { continue; }
                final int required = Integer.parseInt(tap.getType().split("withTotalPowerGE")[1]);
                final List<Card> pilots = battlefield.stream().filter(pilot -> !pilot.isFaceDown() && !pilot.isPhasedOut()
                        && pilot.getController() == card.getController() && pilot.isCreature() && pilot.canCrew()
                        && pilot.getId() != card.getId()).sorted(java.util.Comparator.comparingInt(Card::getId)).toList();
                final Map<Integer, Integer> contributions = new LinkedHashMap<>();
                for (final Card pilot : pilots) {
                    if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
                    contributions.put(pilot.getId(), CardLists.getTotalPower(List.of(pilot), ability));
                }
                final List<Set<Integer>> groups = new ArrayList<>();
                if (!groups(new ArrayList<>(contributions.keySet()), contributions, required, 0, 0,
                        new LinkedHashSet<>(), groups, checkpoint)) {
                    if (groups.size() >= MAX_RESPONSES) {
                        return Optional.of(new PreparedCombatCrewResponses(List.of(), identities,
                                List.of("Crew payment enumeration exceeds " + MAX_RESPONSES + " alternatives: " + card.getId())));
                    }
                    return Optional.empty();
                }
                payments.addAll(groups);
                animated = AnimateAi.becomeAnimatedForEvaluation(card, ability);
                animated.setZone(card.getZone());
                // A creature-only anthem did not affect the live noncreature. Add its supported
                // layer on the analysis copy, using the same tracked key as source-loss worlds.
                for (final Card source : battlefield) {
                    for (final var staticAbility : source.getStaticAbilities()) {
                        if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
                        if (!CombatStaticProjectionPreparation.supportsFixedPowerToughness(staticAbility)) { continue; }
                        final var effect = observer.getGame().getStaticEffects().findStaticEffect(staticAbility);
                        // AnimateAi's approximate P/T pass uses source layer timestamps,
                        // which can differ from the tracked layer key (notably empty effects).
                        // Normalize instead of stacking a second copy of the same anthem.
                        animated.removePTBoost(source.getLayerTimestamp(), staticAbility.getId());
                        if (effect != null) { animated.removePTBoost(effect.getTimestamp(), staticAbility.getId()); }
                        if (source.isFaceDown() || source.isPhasedOut() || !staticAbility.checkConditions(StaticAbilityMode.Continuous)
                                || !animated.isValid(staticAbility.getParam("Affected"), source.getController(), source, staticAbility)) { continue; }
                        animated.addPTBoost(Integer.parseInt(staticAbility.getParamOrDefault("AddPower", "0")),
                                Integer.parseInt(staticAbility.getParamOrDefault("AddToughness", "0")),
                                effect == null ? observer.getGame().getTimestamp() + 1 : effect.getTimestamp(), staticAbility.getId());
                    }
                }
            }
            if (animated != null && !payments.isEmpty()) {
                identities.put(card.getId(), card.getGameTimestamp());
                for (final var group : payments) {
                    for (final int id : group) {
                        final Card pilot = battlefield.stream().filter(c -> c.getId() == id).findFirst().orElseThrow();
                        identities.put(id, pilot.getGameTimestamp());
                    }
                }
                vehicles.add(new Vehicle(animated, List.copyOf(payments)));
            }
        }
        // TODO: Animation/untap chains, extra costs, payment replacements and tap/crew
        // consequences, and delayed lifetime extraction for initially noncreature Vehicles.
        // Unrelated ETB/attack triggers retain the ordinary ignored-effect policy.
        final List<PreparedCombatCrewResponses.Response> responses = new ArrayList<>();
        if (!worlds(observer, alternatives, vehicles, 0, new LinkedHashMap<>(), new LinkedHashSet<>(),
                responses, checkpoint)) {
            if (responses.size() >= MAX_RESPONSES) {
                return Optional.of(new PreparedCombatCrewResponses(responses, identities,
                        List.of("Crew response enumeration exceeds " + MAX_RESPONSES + " alternatives")));
            }
            return Optional.empty();
        }
        return Optional.of(new PreparedCombatCrewResponses(responses, identities, reasons, rules));
    }

    private static boolean groups(final List<Integer> pilots, final Map<Integer, Integer> contributions,
            final int required, final int index, final int total, final Set<Integer> chosen,
            final List<Set<Integer>> result, final BooleanSupplier checkpoint) {
        if (!checkpoint.getAsBoolean() || result.size() >= MAX_RESPONSES) { return false; }
        if (total >= required) {
            // Threshold-only payment: extra taps cannot improve the modeled defense.
            if (chosen.stream().allMatch(id -> total - contributions.get(id) < required)) { result.add(Set.copyOf(chosen)); }
            return true;
        }
        int remaining = 0;
        for (int i = index; i < pilots.size(); i++) { remaining += contributions.get(pilots.get(i)); }
        if (total + remaining < required) { return true; }
        for (int i = index; i < pilots.size(); i++) {
            final int id = pilots.get(i);
            if (contributions.get(id) <= 0) { continue; }
            chosen.add(id);
            if (!groups(pilots, contributions, required, i + 1, total + contributions.get(id), chosen, result, checkpoint)) { return false; }
            chosen.remove(id);
        }
        return true;
    }

    private static boolean worlds(final Player observer, final Combat alternatives, final List<Vehicle> vehicles,
            final int index, final Map<Integer, Set<Integer>> crews, final Set<Integer> spent,
            final List<PreparedCombatCrewResponses.Response> result, final BooleanSupplier checkpoint) {
        if (vehicles.isEmpty()) { return true; }
        if (!checkpoint.getAsBoolean() || result.size() >= MAX_RESPONSES) { return false; }
        if (index == vehicles.size()) {
            if (crews.isEmpty()) { return true; }
            final Map<Integer, Card> views = new HashMap<>();
            for (final Vehicle vehicle : vehicles) {
                if (crews.containsKey(vehicle.animated().getId())) { views.put(vehicle.animated().getId(), vehicle.animated()); }
            }
            for (final Card card : observer.getGame().getCardsIn(ZoneType.Battlefield)) {
                if (!checkpoint.getAsBoolean()) { return false; }
                if (spent.contains(card.getId())) {
                    final Card copy = CardCopyService.getLKICopy(card);
                    // LKI copies omit the current zone, which snapshot effect checks require.
                    copy.setZone(card.getZone());
                    copy.setTapped(true);
                    views.put(card.getId(), copy);
                }
            }
            final var world = PublicCombatSnapshot.captureViews(observer, alternatives, false, checkpoint, views);
            if (world == null) { return false; }
            result.add(new PreparedCombatCrewResponses.Response(crews,
                    world.withCrew(crews.keySet()).withModeledCrew(observer, crews.keySet())));
            return true;
        }
        final Vehicle vehicle = vehicles.get(index);
        if (!worlds(observer, alternatives, vehicles, index + 1, crews, spent, result, checkpoint)) { return false; }
        for (final var group : vehicle.payments()) {
            if (group.stream().anyMatch(spent::contains)) { continue; }
            crews.put(vehicle.animated().getId(), group);
            spent.addAll(group);
            if (!worlds(observer, alternatives, vehicles, index + 1, crews, spent, result, checkpoint)) { return false; }
            spent.removeAll(group);
            crews.remove(vehicle.animated().getId());
        }
        return true;
    }
}
