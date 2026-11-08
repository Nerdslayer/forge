package forge.ai.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.effect.ValuationDecision;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.combat.Combat;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

/** Generic payment exclusivity, combat-world parity and attack-policy regressions. */
public class CombatCrewResponseTest extends AITest {
    private record Fixture(Game game, Player ai, Player opponent, Combat combat) { }

    @Test
    public void flyingVehicleResponsePreventsBadAttackWithoutMutatingTheLiveGame() {
        final var f = fixture();
        final Card flyer = creature(f.ai(), 3, 3, "K:Flying");
        final Card pilot = creature(f.opponent(), 2, 2);
        final Card vehicle = vehicle(f.opponent(), 5, 5, 2, "K:Flying");
        pilot.setSickness(true);
        vehicle.setSickness(true);
        final long timestamp = f.game().getTimestamp();
        final var crewAbility = vehicle.getSpellAbilities().stream().filter(sa -> sa.isCrew()).findFirst().orElseThrow();
        final Player activator = crewAbility.getActivatingPlayer();
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), budget());
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertTrue(plan.search().orElseThrow().best().orElseThrow().attackers().isEmpty());
        Assert.assertEquals(plan.crewResponses().responses().size(), 1);
        final var response = plan.crewResponses().responses().get(0);
        Assert.assertEquals(response.crews(), Map.of(vehicle.getId(), Set.of(pilot.getId())));
        Assert.assertEquals(response.world().legalBlockers().get(flyer.getId()), Set.of(vehicle.getId()));
        Assert.assertTrue(response.world().creatures().get(pilot.getId()).tapped());
        Assert.assertFalse(pilot.isTapped() || vehicle.isTapped() || vehicle.isCreature());
        Assert.assertEquals(f.game().getTimestamp(), timestamp);
        Assert.assertSame(crewAbility.getActivatingPlayer(), activator);
        Assert.assertTrue(CombatAttackPlanner.tryDeclare(f.ai(), f.ai(), f.combat(), budget()));
        Assert.assertTrue(f.combat().getAttackers().isEmpty());
    }

    @Test
    public void counterBearingVehiclePreservesBothFliersWhenNoDamageIsWorthTheLoss() {
        final var f = fixture();
        creature(f.ai(), 3, 3, "K:Flying");
        creature(f.ai(), 2, 2, "K:Flying");
        creature(f.opponent(), 2, 2);
        final Card vehicle = vehicle(f.opponent(), 1, 1, 2, "K:Flying");
        vehicle.setCounters(CounterEnumType.P1P1, 5);
        f.opponent().setLife(35, null);
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), budget());
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertTrue(plan.search().orElseThrow().best().orElseThrow().attackers().isEmpty());
        Assert.assertEquals(plan.crewResponses().responses().get(0).world().creatures().get(vehicle.getId()).toughness(), 6);
    }

    @Test
    public void unavailableCrewAndGroundVehiclesDoNotInventFlyingBlockers() {
        for (int mode = 0; mode < 5; mode++) {
            final var f = fixture();
            final Card attacker = creature(f.ai(), 3, 3, "K:Flying");
            final Card pilot = creature(f.opponent(), mode == 0 ? 1 : 2, 2);
            final Card vehicle = vehicle(f.opponent(), 5, 5, 2, mode == 3 ? "K:Vigilance" : "K:Flying");
            if (mode == 1) { pilot.setTapped(true); }
            if (mode == 2) { vehicle.setTapped(true); }
            if (mode == 4) {
                creature(f.ai(), 1, 1, "S:Mode$ CantBeActivated | ValidCard$ Artifact | Activator$ Opponent");
            }
            final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), budget());
            Assert.assertTrue(plan.applicable(), plan.reasons().toString());
            Assert.assertTrue(plan.search().orElseThrow().best().orElseThrow().attackers().contains(attacker.getId()), "mode=" + mode);
            if (mode < 3 || mode == 4) { Assert.assertTrue(plan.crewResponses().responses().isEmpty(), "mode=" + mode); }
        }
    }

    @Test
    public void paymentsPreserveAlternativeBlockersAndCannotBeSharedAcrossVehicles() {
        final var f = fixture();
        final Card attacker = creature(f.ai(), 3, 3, "K:Flying");
        final Card flyingPilot = creature(f.opponent(), 2, 2, "K:Flying");
        final Card groundPilot = creature(f.opponent(), 2, 2);
        final Card first = vehicle(f.opponent(), 4, 4, 2, "K:Flying");
        final Card second = vehicle(f.opponent(), 4, 4, 2, "K:Flying");
        f.combat().addAttacker(attacker, f.opponent());
        final var prepared = CombatCrewPreparation.prepare(f.ai(), f.combat(), () -> true).orElseThrow();
        Assert.assertTrue(prepared.reasons().isEmpty());
        Assert.assertEquals(prepared.responses().size(), 6);
        Assert.assertTrue(prepared.responses().stream().anyMatch(response ->
                response.crews().equals(Map.of(first.getId(), Set.of(groundPilot.getId())))
                        && response.world().legalBlockers().get(attacker.getId()).contains(flyingPilot.getId())));
        for (final var response : prepared.responses()) {
            final List<Integer> tapped = response.crews().values().stream().flatMap(Set::stream).toList();
            Assert.assertEquals(Set.copyOf(tapped).size(), tapped.size());
            Assert.assertTrue(response.world().legalBlockers().values().stream().noneMatch(ids -> ids.stream().anyMatch(tapped::contains)));
        }
        groundPilot.setTapped(true);
        final var onePilot = CombatCrewPreparation.prepare(f.ai(), f.combat(), () -> true).orElseThrow();
        Assert.assertEquals(onePilot.responses().size(), 2);
        Assert.assertTrue(onePilot.responses().stream().noneMatch(response -> response.crews().size() == 2));
        Assert.assertTrue(prepared.identities().containsKey(second.getId()));
    }

    @Test
    public void multiplePilotsPayCrewAndCleanupRetainsThePermanentNotItsCreatureForm() {
        final var f = fixture();
        final Card attacker = creature(f.ai(), 1, 4, "K:Flying");
        final Card a = creature(f.opponent(), 1, 1);
        final Card b = creature(f.opponent(), 1, 1);
        final Card vehicle = vehicle(f.opponent(), 5, 5, 2, "K:Flying");
        f.combat().addAttacker(attacker, f.opponent());
        final var base = PublicCombatSnapshot.capture(f.ai(), f.combat());
        final var response = CombatCrewPreparation.prepare(f.ai(), f.combat(), () -> true).orElseThrow().responses().get(0);
        Assert.assertEquals(response.crews().get(vehicle.getId()), Set.of(a.getId(), b.getId()));
        final var projection = CombatOutcomePredictor.predict(response.world(),
                new CombatAssignment(response.world().attackersToDefenders(), Map.of(attacker.getId(), List.of(vehicle.getId()))));
        Assert.assertTrue(projection.available() && projection.supported(), projection.reasons().toString());
        final var values = CombatPlanningPreparation.values(f.ai(), ValuationDecision.ATTACK, budget()).orElseThrow();
        final var next = CombatTurnProjectionBuilder.advance(response.world(), projection,
                PublicCombatReadiness.capture(f.ai(), base), values).turn().orElseThrow();
        Assert.assertFalse(next.alternatives().creatures().containsKey(vehicle.getId()));
        Assert.assertTrue(next.values().permanents().containsKey(vehicle.getId()));
        Assert.assertFalse(next.alternatives().creatures().get(a.getId()).tapped());
        Assert.assertFalse(next.alternatives().creatures().get(b.getId()).tapped());
        Assert.assertTrue(vehicle.isInPlay() && !vehicle.isCreature() && !a.isTapped() && !b.isTapped());
    }

    @Test
    public void preparedCrewCombatMatchesARealCrewResolutionAndBlock() {
        final var f = fixture();
        final Card attacker = creature(f.ai(), 3, 3, "K:Flying", "K:Lifelink");
        final Card pilot = creature(f.opponent(), 2, 2);
        final Card vehicle = vehicle(f.opponent(), 4, 4, 2, "K:Flying", "K:First strike");
        f.combat().addAttacker(attacker, f.opponent());
        final var response = CombatCrewPreparation.prepare(f.ai(), f.combat(), () -> true).orElseThrow().responses().get(0);
        final var predicted = CombatOutcomePredictor.predict(response.world(),
                new CombatAssignment(response.world().attackersToDefenders(), Map.of(attacker.getId(), List.of(vehicle.getId()))));
        pilot.setTapped(true);
        final var crew = vehicle.getSpellAbilities().stream().filter(sa -> sa.isCrew()).findFirst().orElseThrow();
        crew.setActivatingPlayer(f.opponent());
        AbilityUtils.resolve(crew);
        f.game().getAction().checkStaticAbilities();
        Assert.assertTrue(vehicle.isCreature());
        f.combat().addBlocker(attacker, vehicle);
        f.combat().setBlocked(attacker, true);
        f.combat().orderBlockersForDamageAssignment();
        f.combat().orderAttackersForDamageAssignment();
        for (final boolean first : List.of(true, false)) {
            f.game().getPhaseHandler().devModeSet(first ? PhaseType.COMBAT_FIRST_STRIKE_DAMAGE : PhaseType.COMBAT_DAMAGE, f.ai(), false);
            f.combat().removeAbsentCombatants();
            if (f.combat().assignCombatDamage(first)) { f.combat().dealAssignedDamage(); }
            f.game().getAction().checkStateEffects(true);
        }
        Assert.assertEquals(attacker.isInPlay(), !predicted.lostCreatures().contains(attacker.getId()));
        Assert.assertEquals(vehicle.isInPlay(), !predicted.lostCreatures().contains(vehicle.getId()));
        Assert.assertEquals(f.ai().getLife(), predicted.playerLifeAfter().get(f.ai().getId()).intValue());
    }

    @Test
    public void animationAppliesCreatureAnthemsAndKeepsSourceLossWorldsConsistent() {
        final var f = fixture();
        final Card attacker = creature(f.ai(), 3, 3);
        final Card pilot = creature(f.opponent(), 2, 2);
        final Card vehicle = vehicle(f.opponent(), 2, 2, 2);
        final Card anthem = creature(f.opponent(), 1, 1,
                "S:Mode$ Continuous | Affected$ Creature.YouCtrl+Other | AddPower$ 1 | AddToughness$ 1");
        f.game().getAction().checkStaticAbilities();
        f.combat().addAttacker(attacker, f.opponent());
        final var response = CombatCrewPreparation.prepare(f.ai(), f.combat(), () -> true).orElseThrow().responses().stream()
                .filter(r -> r.crews().get(vehicle.getId()).equals(Set.of(pilot.getId()))).findFirst().orElseThrow();
        Assert.assertEquals(response.world().creatures().get(vehicle.getId()).toughness(), 3);
        Assert.assertEquals(response.world().creatureAfterLosses(vehicle.getId(), Set.of(anthem.getId())).toughness(), 2);
        Assert.assertFalse(vehicle.isCreature(), "The anthem projection must not animate the live Vehicle");
    }

    @Test
    public void noCrewCanBeBetterThanTappingTheOnlyProfitableBlocker() {
        final var f = fixture();
        final Card attacker = creature(f.ai(), 3, 3);
        final Card pilot = creature(f.opponent(), 4, 4);
        final Card vehicle = vehicle(f.opponent(), 1, 1, 2);
        f.combat().addAttacker(attacker, f.opponent());
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), budget());
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        final var selected = plan.search().orElseThrow().best().orElseThrow();
        Assert.assertTrue(selected.hypotheticalCrews().isEmpty());
        Assert.assertEquals(selected.combat().assignment().blockersByAttacker().get(attacker.getId()), List.of(pilot.getId()));
        Assert.assertTrue(selected.combat().projection().lostCreatures().contains(attacker.getId()));
        Assert.assertFalse(selected.combat().projection().lostCreatures().contains(vehicle.getId()));
    }

    @Test
    public void attackSelectionEvaluatesAlternativePilotsRatherThanAlwaysTappingTheSmallest() {
        final var f = fixture();
        final Card attacker = creature(f.ai(), 3, 3, "K:Flying");
        final Card smallReach = creature(f.opponent(), 2, 4, "K:Reach");
        final Card ground = creature(f.opponent(), 4, 4);
        final Card vehicle = vehicle(f.opponent(), 3, 5, 2, "K:Flying");
        f.combat().addAttacker(attacker, f.opponent());
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), budget());
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertEquals(plan.crewResponses().responses().size(), 2);
        // Both assignments are examined even if the optimum is simply to leave the reach
        // blocker untapped and not crew at all. The crew policy is not a cost heuristic.
        Assert.assertTrue(plan.crewResponses().responses().stream().anyMatch(r ->
                r.crews().equals(Map.of(vehicle.getId(), Set.of(ground.getId())))
                        && r.world().legalBlockers().get(attacker.getId()).contains(smallReach.getId())));
        Assert.assertTrue(plan.search().orElseThrow().best().orElseThrow().combat().projection().lostCreatures().contains(attacker.getId()));
    }

    @Test
    public void existingAnimatedVehicleIsNotAddedOrChargedTwice() {
        final var f = fixture();
        final Card attacker = creature(f.ai(), 3, 3, "K:Flying");
        final Card vehicle = vehicle(f.opponent(), 5, 5, 2, "K:Flying");
        final var crew = vehicle.getSpellAbilities().stream().filter(sa -> sa.isCrew()).findFirst().orElseThrow();
        crew.setActivatingPlayer(f.opponent());
        AbilityUtils.resolve(crew);
        f.combat().addAttacker(attacker, f.opponent());
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), budget());
        Assert.assertTrue(plan.crewResponses().responses().isEmpty());
        Assert.assertEquals(plan.snapshot().orElseThrow().legalBlockers().get(attacker.getId()), Set.of(vehicle.getId()));
    }

    @Test
    public void vehicleDeathChargesTheEnduringPermanentExactlyOnce() {
        final var f = fixture();
        final Card attacker = creature(f.ai(), 8, 8, "K:Flying");
        creature(f.opponent(), 2, 2);
        final Card vehicle = vehicle(f.opponent(), 2, 2, 2, "K:Flying");
        f.combat().addAttacker(attacker, f.opponent());
        final var response = CombatCrewPreparation.prepare(f.ai(), f.combat(), () -> true).orElseThrow().responses().get(0);
        final var projection = CombatOutcomePredictor.predict(response.world(),
                new CombatAssignment(response.world().attackersToDefenders(), Map.of(attacker.getId(), List.of(vehicle.getId()))));
        final var values = CombatPlanningPreparation.values(f.ai(), ValuationDecision.ATTACK, budget()).orElseThrow();
        Assert.assertEquals(CombatTransitionValueEvaluator.evaluate(response.world(), values, projection).permanentLoss().body(),
                values.permanents().get(vehicle.getId()).bodyLossValue());
        final var next = CombatTurnProjectionBuilder.advance(response.world(), projection,
                PublicCombatReadiness.capture(f.ai(), PublicCombatSnapshot.capture(f.ai(), f.combat())), values).turn().orElseThrow();
        Assert.assertFalse(next.values().permanents().containsKey(vehicle.getId()));
    }

    @Test
    public void hypotheticalVehiclesUseSharedProtectionAndShieldMechanics() {
        final var f = fixture();
        final Card attacker = creature(f.ai(), 8, 8, "K:Flying", "K:Double strike");
        creature(f.opponent(), 2, 2);
        final Card vehicle = vehicle(f.opponent(), 4, 4, 2, "K:Flying", "K:Protection:Creature:creatures");
        vehicle.setCounters(CounterEnumType.SHIELD, 1);
        f.combat().addAttacker(attacker, f.opponent());
        final var response = CombatCrewPreparation.prepare(f.ai(), f.combat(), () -> true).orElseThrow().responses().get(0);
        final var projection = CombatOutcomePredictor.predict(response.world(),
                new CombatAssignment(response.world().attackersToDefenders(), Map.of(attacker.getId(), List.of(vehicle.getId()))));
        Assert.assertTrue(projection.available() && projection.supported(), projection.reasons().toString());
        Assert.assertFalse(projection.lostCreatures().contains(vehicle.getId()));
        Assert.assertEquals(projection.survivors().get(vehicle.getId()).shieldCounters(), 1);
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), budget());
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertTrue(plan.search().orElseThrow().best().orElseThrow().hypotheticalCrews().containsKey(vehicle.getId()));
    }

    @Test
    public void crewResponsesApplyToPlaneswalkerDestinationsAndSharedPilotResources() {
        final var f = fixture();
        final Card attacker = creature(f.ai(), 4, 4, "K:Flying");
        creature(f.opponent(), 2, 2);
        final Card vehicle = vehicle(f.opponent(), 5, 5, 2, "K:Flying");
        final Card walker = Card.fromPaperCard(new PaperCard(CardRules.fromScript(List.of("Name:Crew Walker",
                "ManaCost:4", "Types:Legendary Planeswalker Test", "Loyalty:4")), CardEdition.UNKNOWN_CODE, CardRarity.Special), f.opponent());
        walker.setGameTimestamp(f.game().getNextTimestamp());
        f.opponent().getZone(ZoneType.Battlefield).add(walker);
        walker.setCounters(CounterEnumType.LOYALTY, 4);
        f.combat().addAttacker(attacker, walker);
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), budget());
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        final var selected = plan.search().orElseThrow().best().orElseThrow();
        Assert.assertEquals(selected.hypotheticalCrews().keySet(), Set.of(vehicle.getId()));
        Assert.assertEquals(selected.combat().projection().planeswalkerLoyaltyAfter().get(walker.getId()).intValue(), 4);
        Assert.assertTrue(selected.combat().projection().lostCreatures().contains(attacker.getId()));
    }

    @Test
    public void changedResourcesChangeFrozenResponsesAndEnumerationLimitsAreNotCompleteDefense() {
        final var f = fixture();
        final Card attacker = creature(f.ai(), 3, 3, "K:Flying");
        final Card pilot = creature(f.opponent(), 2, 2);
        vehicle(f.opponent(), 5, 5, 2, "K:Flying");
        f.combat().addAttacker(attacker, f.opponent());
        final var before = CombatCrewPreparation.prepare(f.ai(), f.combat(), () -> true).orElseThrow();
        pilot.setTapped(true);
        Assert.assertNotEquals(CombatCrewPreparation.prepare(f.ai(), f.combat(), () -> true).orElseThrow(), before);
        pilot.setTapped(false);
        pilot.setGameTimestamp(f.game().getNextTimestamp());
        Assert.assertNotEquals(CombatCrewPreparation.prepare(f.ai(), f.combat(), () -> true).orElseThrow(), before);
        final var tiny = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(10, 1000, () -> 0L));
        Assert.assertFalse(tiny.applicable());
        for (int i = 0; i < 17; i++) { creature(f.opponent(), 1, 1); }
        final var crowded = CombatCrewPreparation.prepare(f.ai(), f.combat(), () -> true).orElseThrow();
        Assert.assertFalse(crowded.reasons().isEmpty());
    }

    @Test
    public void crewContributionsUseTheEnginePowerModifiersAndToughnessRules() {
        for (final String value : List.of("2", "Toughness")) {
            final var f = fixture();
            final Card attacker = creature(f.ai(), 3, 3, "K:Flying");
            final Card pilot = creature(f.opponent(), 1, 4,
                    "S:Mode$ TapPowerValue | EffectZone$ Battlefield | ValidSA$ Activated.Crew+Vehicle | ValidCard$ Card.Self | Value$ " + value);
            final Card vehicle = vehicle(f.opponent(), 5, 5, 3, "K:Flying");
            f.combat().addAttacker(attacker, f.opponent());
            final var response = CombatCrewPreparation.prepare(f.ai(), f.combat(), () -> true).orElseThrow();
            Assert.assertEquals(response.responses().size(), 1, value);
            Assert.assertEquals(response.responses().get(0).crews(), Map.of(vehicle.getId(), Set.of(pilot.getId())));
            Assert.assertTrue(response.responses().get(0).world().creatures().get(pilot.getId()).tapped());
            Assert.assertFalse(pilot.isTapped());
        }
    }

    @Test
    public void anEmptyTrackedAnthemLayerAcquiresAnAnimatedRecipientWithoutLiveRecalculation() {
        final var f = fixture();
        final Card attacker = creature(f.ai(), 3, 3);
        final Card source = permanent(f.opponent(), "Enchantment", 0, 0, List.of(
                "S:Mode$ Continuous | Affected$ Creature.YouCtrl | AddPower$ 1 | AddToughness$ 1"));
        final Card vehicle = vehicle(f.opponent(), 2, 2, 0, "K:Flying");
        f.game().getAction().checkStaticAbilities();
        f.combat().addAttacker(attacker, f.opponent());
        final var response = CombatCrewPreparation.prepare(f.ai(), f.combat(), () -> true).orElseThrow().responses().get(0);
        Assert.assertTrue(response.crews().get(vehicle.getId()).isEmpty());
        Assert.assertEquals(response.world().creatures().get(vehicle.getId()).toughness(), 3);
        Assert.assertEquals(response.world().creatureAfterLosses(vehicle.getId(), Set.of(source.getId())).toughness(), 2);
        Assert.assertFalse(vehicle.isCreature());
    }

    @Test
    public void crewCannotBeIgnoredWhenCertifyingAnOtherwiseLethalAttack() {
        final var f = fixture();
        final Card attacker = creature(f.ai(), 4, 4, "K:Flying");
        creature(f.opponent(), 2, 2);
        vehicle(f.opponent(), 5, 5, 2, "K:Flying");
        f.opponent().setLife(4, null);
        f.combat().addAttacker(attacker, f.opponent());
        final var base = PublicCombatSnapshot.capture(f.ai(), f.combat());
        final var responses = CombatCrewPreparation.prepare(f.ai(), f.combat(), () -> true).orElseThrow();
        final var values = CombatPlanningPreparation.values(f.ai(), ValuationDecision.ATTACK, budget()).orElseThrow();
        final var evaluation = new CombatAttackSearch.Evaluation(base, values, PublicCombatReadiness.capture(f.ai(), base),
                budget(), new java.util.LinkedHashSet<>(), responses);
        Assert.assertNull(evaluation.immediateWin(base, budget()));
        Assert.assertTrue(CombatCrewPreparation.prepare(f.ai(), f.combat(), () -> false).isEmpty());
    }

    @Test
    public void anthemExpiryRebasesEnduringCreaturesButNotTemporaryCrewBodies() {
        final var f = fixture();
        final Card attacker = creature(f.ai(), 1, 4, "K:Flying");
        final Card source = creature(f.opponent(), 1, 1, "K:Unearth:0",
                "S:Mode$ Continuous | Affected$ Creature.YouCtrl+Other | AddPower$ 1 | AddToughness$ 1");
        f.opponent().getZone(ZoneType.Battlefield).remove(source);
        f.opponent().getZone(ZoneType.Graveyard).add(source);
        final var unearth = source.getSpellAbilities().stream().filter(sa -> sa.isKeyword(Keyword.UNEARTH)).findFirst().orElseThrow();
        unearth.setActivatingPlayer(f.opponent());
        AbilityUtils.resolve(unearth);
        final Card returned = f.game().getCardState(source);
        Assert.assertTrue(returned.isInPlay() && returned.isUnearthed());
        final Card pilot = creature(f.opponent(), 2, 2);
        final Card vehicle = vehicle(f.opponent(), 3, 3, 2, "K:Flying");
        f.game().getAction().checkStaticAbilities();
        f.combat().addAttacker(attacker, f.opponent());
        final var base = PublicCombatSnapshot.capture(f.ai(), f.combat());
        final var response = CombatCrewPreparation.prepare(f.ai(), f.combat(), () -> true).orElseThrow().responses().stream()
                .filter(r -> r.crews().get(vehicle.getId()).equals(Set.of(pilot.getId()))).findFirst().orElseThrow();
        final var projection = CombatOutcomePredictor.predict(response.world(),
                new CombatAssignment(response.world().attackersToDefenders(), Map.of()));
        final var values = CombatPlanningPreparation.values(f.ai(), ValuationDecision.ATTACK, budget()).orElseThrow();
        final var next = CombatTurnProjectionBuilder.advance(response.world(), projection,
                PublicCombatReadiness.capture(f.ai(), base), values).turn().orElseThrow();
        Assert.assertFalse(next.values().permanents().containsKey(returned.getId()));
        Assert.assertFalse(next.alternatives().creatures().containsKey(vehicle.getId()));
        Assert.assertEquals(next.values().permanents().get(vehicle.getId()), values.permanents().get(vehicle.getId()));
        Assert.assertNotEquals(next.values().permanents().get(pilot.getId()).bodyLossValue(),
                values.permanents().get(pilot.getId()).bodyLossValue());
        Assert.assertEquals(next.alternatives().creatures().get(pilot.getId()).toughness(), 2);
    }

    @Test
    public void scaledCrewWorkCapCanCompleteAnOtherwiseBoundedSearch() {
        final var f = fixture();
        f.combat().addAttacker(creature(f.ai(), 2, 2), f.opponent());
        vehicle(f.opponent(), 4, 4, 1);
        for (int i = 0; i < 3; i++) { creature(f.opponent(), 1, 1); }
        final var low = CombatCrewPreparation.prepare(f.ai(), f.combat(), () -> true, 2).orElseThrow();
        final var medium = CombatCrewPreparation.prepare(f.ai(), f.combat(), () -> true,
                forge.ai.MastermindEffort.MEDIUM.scale(2)).orElseThrow();
        Assert.assertFalse(low.reasons().isEmpty());
        Assert.assertTrue(medium.reasons().isEmpty(), medium.reasons().toString());
        Assert.assertEquals(medium.responses().size(), 3);
    }

    @Test
    public void higherEffortAppliesOnlyToTheResolvedMastermindProfile() {
        final var f = fixture();
        final var original = forge.ai.MastermindEffort.forPlayer(f.ai());
        try {
            forge.ai.MastermindEffort.configure("MAX");
            Assert.assertEquals(CombatPlanningPreparation.budget(f.ai()).scale(100), 800);
            ((LobbyPlayerAi) f.ai().getLobbyPlayer()).setAiProfile("Default");
            Assert.assertEquals(CombatPlanningPreparation.budget(f.ai()).scale(100), 100);
        } finally {
            forge.ai.MastermindEffort.configure(original.name());
        }
    }

    private Fixture fixture() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(0);
        final Player opponent = game.getPlayers().get(1);
        ai.setTeam(0);
        opponent.setTeam(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Mastermind");
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, ai);
        final Combat combat = new Combat(ai);
        game.getPhaseHandler().setCombat(combat);
        for (final Player player : game.getPlayers()) {
            for (int count = 0; count < 15; count++) {
                player.getZone(ZoneType.Library).add(Card.fromPaperCard(new PaperCard(CardRules.fromScript(
                        List.of("Name:Crew Library", "Types:Land")), CardEdition.UNKNOWN_CODE, CardRarity.Special), player));
            }
        }
        return new Fixture(game, ai, opponent, combat);
    }

    private static CombatSearchBudget budget() { return new CombatSearchBudget(200000, 30000, () -> 0L); }

    private static Card creature(final Player owner, final int power, final int toughness, final String... extra) {
        return permanent(owner, "Creature Construct", power, toughness, List.of(extra));
    }

    private static Card vehicle(final Player owner, final int power, final int toughness, final int crew, final String... extra) {
        final List<String> abilities = new ArrayList<>(List.of(extra));
        abilities.add("K:Crew:" + crew);
        return permanent(owner, "Artifact Vehicle", power, toughness, abilities);
    }

    private static Card permanent(final Player owner, final String types, final int power, final int toughness, final List<String> extra) {
        final List<String> script = new ArrayList<>(List.of("Name:Crew Fixture", "ManaCost:4", "Types:" + types, "PT:" + power + "/" + toughness));
        script.addAll(extra);
        final Card card = Card.fromPaperCard(new PaperCard(CardRules.fromScript(script), CardEdition.UNKNOWN_CODE, CardRarity.Special), owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());
        owner.getZone(ZoneType.Battlefield).add(card);
        card.setSickness(false);
        return card;
    }
}
