package forge.ai.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

/** Destination assignment, threshold bundles, damage provenance and read-only projection. */
public class CombatAttackDestinationTest extends AITest {
    private record Fixture(Game game, Player ai, Player opponent, Combat combat) { }

    @Test
    public void walkerAndPlayerAllocationLeavesARedundantOptionalLooterBack() {
        final Fixture f = fixture();
        f.opponent().setLife(20, null);
        final Card looter = creature(f.ai(), 0, List.of("A:AB$ Draw | Cost$ T | Defined$ You | NumCards$ 1"));
        creature(f.ai(), 2, List.of());
        creature(f.ai(), 2, List.of());
        final Card target = walker(f.opponent(), 4, 2, false);
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(100000, 30000, () -> 0L));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        final var chosen = plan.search().orElseThrow().best().orElseThrow();
        Assert.assertFalse(chosen.attackers().contains(looter.getId()));
        Assert.assertEquals(chosen.attackers().size(), 2);
        Assert.assertEquals(chosen.combat().projection().planeswalkerLoyaltyAfter().get(target.getId()).intValue(), 0);
        Assert.assertTrue(chosen.destinations().values().stream().anyMatch(d -> d.kind() == CombatAttackDestination.Kind.PLAYER));
    }

    @Test
    public void walkerKillAndUltimateDenialDoNotJustifyAnAttackThatStillLosesNextTurn() {
        for (final int loyalty : List.of(2, 6)) {
            final Fixture f = fixture();
            f.ai().setLife(1, null);
            f.opponent().setLife(35, null);
            creature(f.ai(), 2, List.of("K:Flying"));
            creature(f.opponent(), 8, List.of());
            creature(f.opponent(), 8, List.of());
            final Card target = walker(f.opponent(), 6, loyalty, true);
            final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(100000));
            Assert.assertTrue(plan.applicable(), plan.reasons().toString());
            final var result = plan.search().orElseThrow();
            Assert.assertTrue(result.noAttack().orElseThrow().reply().orElseThrow().lethalOpportunity());
            Assert.assertEquals(result.best(), result.noAttack());
            Assert.assertTrue(CombatAttackPlanner.tryDeclare(f.ai(), f.ai(), f.combat(), new CombatSearchBudget(100000)));
            Assert.assertTrue(f.combat().getAttackers().isEmpty());
            Assert.assertEquals(target.getCounters(CounterEnumType.LOYALTY), loyalty);
        }
    }

    @Test
    public void namespacesAndUltimateThresholdAreExplicit() {
        final var player = new CombatAttackDestination(CombatAttackDestination.Kind.PLAYER, 1);
        final var walker = new CombatAttackDestination(CombatAttackDestination.Kind.PLANESWALKER, 1);
        Assert.assertNotEquals(player.key(), walker.key());
        Assert.assertEquals(CombatAttackDestination.fromKey(walker.key()), walker);
        Assert.assertEquals(PlaneswalkerUltimateThreatEvaluator.damageRequired(7, 6), 2);
        Assert.assertEquals(PlaneswalkerUltimateThreatEvaluator.damageRequired(6, 6), 1);
        Assert.assertEquals(PlaneswalkerUltimateThreatEvaluator.damageRequired(5, 6), 0);
    }

    @Test
    public void killableCheaperWalkerBeatsScratchingExpensiveWalker() {
        final Fixture f = fixture();
        final Card attacker = creature(f.ai(), 2, List.of());
        final Card cheap = walker(f.opponent(), 2, 2, false);
        walker(f.opponent(), 6, 8, false);
        Assert.assertTrue(CombatAttackPlanner.tryDeclare(f.ai(), f.ai(), f.combat(), new CombatSearchBudget(1000000)));
        Assert.assertEquals(f.combat().getDefenderByAttacker(attacker), cheap);
    }

    @Test
    public void nonlethalDamageDeniesUltimateAndDoesNotMutateLiveLoyalty() {
        final Fixture f = fixture();
        final Card attacker = creature(f.ai(), 2, List.of());
        final Card urgent = walker(f.opponent(), 4, 7, true);
        walker(f.opponent(), 2, 2, false);
        Assert.assertEquals(PlaneswalkerUltimateThreatEvaluator.threshold(urgent), 6,
                "Triggers=" + urgent.getTriggers() + "; statics=" + urgent.getStaticAbilities()
                        + "; replacements=" + urgent.getReplacementEffects() + "; costs="
                        + urgent.getSpellAbilities().stream().map(ability -> ability.getMapParams() + "/" + ability.getPayCosts()).toList());
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(1000000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        final var chosen = plan.search().orElseThrow().best().orElseThrow();
        Assert.assertEquals(chosen.destinations().get(attacker.getId()).id(), urgent.getId());
        Assert.assertEquals(chosen.combat().projection().planeswalkerLoyaltyAfter().get(urgent.getId()).intValue(), 5);
        Assert.assertEquals(urgent.getCounters(CounterEnumType.LOYALTY), 7);
        Assert.assertEquals(CombatTransitionValueEvaluator.planeswalkerUtility(plan.snapshot().orElseThrow(), chosen.combat().projection()), 320);
    }

    @Test
    public void multipleAttackersCanCrossKillThresholdAndSplitAcrossWalkers() {
        final Fixture f = fixture();
        creature(f.ai(), 2, List.of());
        creature(f.ai(), 2, List.of());
        creature(f.ai(), 2, List.of());
        final Card first = walker(f.opponent(), 4, 4, false);
        final Card second = walker(f.opponent(), 2, 2, false);
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(1000000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        final var projection = plan.search().orElseThrow().best().orElseThrow().combat().projection();
        Assert.assertEquals(projection.planeswalkerLoyaltyAfter().get(first.getId()).intValue(), 0);
        Assert.assertEquals(projection.planeswalkerLoyaltyAfter().get(second.getId()).intValue(), 0);
        Assert.assertEquals(projection.playerLifeAfter().get(f.opponent().getId()).intValue(), 100);
    }

    @Test
    public void immediatePlayerLethalBeatsUltimatePrevention() {
        final Fixture f = fixture();
        f.opponent().setLife(2, null);
        final Card attacker = creature(f.ai(), 2, List.of());
        walker(f.opponent(), 4, 7, true);
        Assert.assertTrue(CombatAttackPlanner.tryDeclare(f.ai(), f.ai(), f.combat(), new CombatSearchBudget(1000000)));
        Assert.assertEquals(f.combat().getDefenderByAttacker(attacker), f.opponent());
    }

    @Test
    public void doubleStrikeLifelinkDoesNotRedirectDamageAfterWalkerDies() {
        final Fixture f = fixture();
        f.ai().setLife(10, null);
        final Card attacker = creature(f.ai(), 3, List.of("K:Double strike", "K:Lifelink", "K:Deathtouch"));
        final Card target = walker(f.opponent(), 4, 2, false);
        f.combat().addAttacker(attacker, target);
        final var snapshot = PublicCombatSnapshot.capture(f.ai(), f.combat());
        final var result = CombatOutcomePredictor.predict(snapshot, new CombatAssignment(snapshot.attackersToDefenders(), Map.of()));
        Assert.assertTrue(result.supported() && result.available(), result.reasons().toString());
        Assert.assertEquals(result.planeswalkerLoyaltyAfter().get(target.getId()).intValue(), 0);
        Assert.assertEquals(result.playerLifeAfter().get(f.opponent().getId()).intValue(), 100);
        Assert.assertEquals(result.playerLifeAfter().get(f.ai().getId()).intValue(), 13);
        Assert.assertTrue(result.lostPermanents().contains(target.getId()));
        Assert.assertFalse(result.lostCreatures().contains(target.getId()));
    }

    @Test
    public void partialDamageOnlyEarnsThresholdBonusWhenUltimateIsActuallyDenied() {
        final Fixture f = fixture();
        final Card attacker = creature(f.ai(), 1, List.of());
        final Card target = walker(f.opponent(), 4, 7, true);
        f.combat().addAttacker(attacker, target);
        final var snapshot = PublicCombatSnapshot.capture(f.ai(), f.combat());
        final var projection = CombatOutcomePredictor.predict(snapshot, new CombatAssignment(snapshot.attackersToDefenders(), Map.of()));
        Assert.assertEquals(projection.planeswalkerLoyaltyAfter().get(target.getId()).intValue(), 6);
        Assert.assertEquals(CombatTransitionValueEvaluator.planeswalkerUtility(snapshot, projection), 10);
    }

    @Test
    public void ultimateDeathDoesNotDoubleCountLoyaltyOrPreventionAndDeathtouchDoesNotKillWalkers() {
        final Fixture f = fixture();
        final Card attacker = creature(f.ai(), 6, List.of("K:Deathtouch"));
        final Card target = walker(f.opponent(), 4, 6, true);
        f.combat().addAttacker(attacker, target);
        final var snapshot = PublicCombatSnapshot.capture(f.ai(), f.combat());
        final var projection = CombatOutcomePredictor.predict(snapshot, new CombatAssignment(snapshot.attackersToDefenders(), Map.of()));
        Assert.assertEquals(CombatTransitionValueEvaluator.planeswalkerUtility(snapshot, projection), 300);
        final var values = forge.ai.effect.CombatValuationEvaluator.prepare(forge.ai.effect.ValuationContext.forCombat(
                f.ai(), forge.ai.effect.ValuationDecision.ATTACK, 0, 100));
        final var score = CombatTransitionValueEvaluator.evaluate(snapshot, values, projection);
        Assert.assertEquals(score.permanentLoss().total(), values.evaluateLosses(java.util.Set.of(target.getId())).total());
        Assert.assertEquals(score.outcomeUtility(), 300);
        Assert.assertFalse(values.afterCombat(snapshot, projection).permanents().containsKey(target.getId()));

        target.setCounters(CounterEnumType.LOYALTY, 8);
        final var larger = PublicCombatSnapshot.capture(f.ai(), f.combat());
        final var survivor = CombatOutcomePredictor.predict(larger, new CombatAssignment(larger.attackersToDefenders(), Map.of()));
        Assert.assertEquals(survivor.planeswalkerLoyaltyAfter().get(target.getId()).intValue(), 2);
        Assert.assertFalse(survivor.lostPermanents().contains(target.getId()));
    }

    @Test
    public void plusAbilityDoesNotMakeAnUnavailableUltimateReadyNextTurn() {
        final Fixture f = fixture();
        final Card target = walker(f.opponent(), 4, 5, true);
        final var plus = forge.game.ability.AbilityFactory.getAbility(
                "AB$ Draw | Cost$ PutCounter<1/LOYALTY> | Defined$ You | NumCards$ 1", target);
        target.addSpellAbility(plus);
        Assert.assertEquals(PlaneswalkerUltimateThreatEvaluator.threshold(target), 6);
        Assert.assertEquals(PlaneswalkerUltimateThreatEvaluator.damageRequired(5, 6), 0);
    }

    @Test
    public void blockersAreSharedBetweenDestinationsAndTrampleDoesNotHitController() {
        final Fixture f = fixture();
        final Card first = creature(f.ai(), 5, List.of("K:Trample"));
        final Card second = creature(f.ai(), 2, List.of());
        final Card blocker = creature(f.opponent(), 1, List.of());
        final Card walker = walker(f.opponent(), 4, 6, false);
        f.combat().addAttacker(first, walker);
        f.combat().addAttacker(second, f.opponent());
        final var snapshot = PublicCombatSnapshot.capture(f.ai(), f.combat());
        final var invalid = CombatOutcomePredictor.predict(snapshot, new CombatAssignment(snapshot.attackersToDefenders(),
                Map.of(first.getId(), List.of(blocker.getId()), second.getId(), List.of(blocker.getId()))));
        Assert.assertFalse(invalid.available());
        final var projection = CombatOutcomePredictor.predict(snapshot, new CombatAssignment(snapshot.attackersToDefenders(),
                Map.of(first.getId(), List.of(blocker.getId()))));
        Assert.assertTrue(projection.supported() && projection.available(), projection.reasons().toString());
        Assert.assertEquals(projection.planeswalkerLoyaltyAfter().get(walker.getId()).intValue(), 4);
        Assert.assertEquals(projection.playerLifeAfter().get(f.opponent().getId()).intValue(), 98);
    }

    @Test
    public void disabledProfileAndIncompleteBudgetsDoNotApplyDestinationPolicy() {
        final Fixture f = fixture();
        final Card attacker = creature(f.ai(), 2, List.of());
        walker(f.opponent(), 4, 2, false);
        Assert.assertFalse(CombatAttackPlanner.tryDeclare(f.ai(), f.ai(), f.combat(), new CombatSearchBudget(1)));
        Assert.assertTrue(f.combat().getAttackers().isEmpty());
        ((LobbyPlayerAi) f.ai().getLobbyPlayer()).setAiProfile("Default");
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(1000000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertEquals(plan.search().orElseThrow().best().orElseThrow().destinations().get(attacker.getId()).kind(),
                CombatAttackDestination.Kind.PLAYER);
    }

    @Test
    public void fixedWalkerAttackIsPreservedWhileAdditionalAttackerKillsAnotherWalker() {
        final Fixture f = fixture();
        final Card fixed = creature(f.ai(), 2, List.of());
        final Card extra = creature(f.ai(), 2, List.of());
        final Card first = walker(f.opponent(), 4, 8, false);
        final Card second = walker(f.opponent(), 2, 2, false);
        f.combat().addAttacker(fixed, first);
        Assert.assertTrue(CombatAttackPlanner.tryDeclare(f.ai(), f.ai(), f.combat(), new CombatSearchBudget(1000000)));
        Assert.assertEquals(f.combat().getDefenderByAttacker(fixed), first);
        Assert.assertEquals(f.combat().getDefenderByAttacker(extra), second);
    }

    @Test
    public void urgencyIsPerSourceNotPerUltimateMode() {
        final Fixture f = fixture();
        final Card attacker = creature(f.ai(), 2, List.of());
        final Card target = walker(f.opponent(), 4, 7, true);
        target.addSpellAbility(forge.game.ability.AbilityFactory.getAbility(
                "AB$ Draw | Cost$ SubCounter<7/LOYALTY> | Defined$ You | NumCards$ 2 | Ultimate$ True", target));
        f.combat().addAttacker(attacker, target);
        final var snapshot = PublicCombatSnapshot.capture(f.ai(), f.combat());
        final var projection = CombatOutcomePredictor.predict(snapshot, new CombatAssignment(snapshot.attackersToDefenders(), Map.of()));
        Assert.assertEquals(PlaneswalkerUltimateThreatEvaluator.threshold(target), 6);
        Assert.assertEquals(CombatTransitionValueEvaluator.planeswalkerUtility(snapshot, projection), 320);
    }

    @Test
    public void failedFutureBaselineCannotHideImmediateLethalAndIsNotZeroDanger() {
        final var attacker = new PublicCombatSnapshot.Creature(10, 1, 2, 2, 0, false, false, false,
                false, false, false, false, false, false, 1, Integer.MAX_VALUE);
        final var values = new PreparedCombatValuation(Map.of(
                10, new PreparedCombatValuation.PermanentValue(10, 1, -100, 0, 0, List.of()),
                100, new PreparedCombatValuation.PermanentValue(100, 2, 200, 0, 0, List.of())),
                List.of(), forge.ai.effect.ValuationCompleteness.COMPLETE, List.of());
        final var readiness = new PublicCombatReadiness(2, java.util.Set.of(), Map.of(), List.of("Future-only uncertainty"));
        for (final int life : List.of(2, 20)) {
            final var snapshot = new PublicCombatSnapshot(1, 1, 2, Map.of(10, attacker),
                    Map.of(1, new PublicCombatSnapshot.LifeState(20, true, true, false, false),
                            2, new PublicCombatSnapshot.LifeState(life, true, true, false, false)),
                    Map.of(10, 2), Map.of(10, java.util.Set.of()), List.of(), List.of(), false,
                    Map.of(), List.of(), java.util.Set.of(), java.util.Set.of(), List.of(), PreparedCombatStaticWorlds.empty(), List.of(),
                    Map.of(100, new PublicCombatSnapshot.Planeswalker(100, 2, "Walker", 3, -1, 300)));
            final var result = CombatAttackDestinationSearch.search(snapshot, values, readiness, new CombatSearchBudget(100000),
                    Map.of(), Map.of(10, java.util.Set.of(2, ~100)));
            if (life == 2) {
                Assert.assertTrue(result.best().orElseThrow().certifiedWin(), result.reasons().toString());
                Assert.assertEquals(result.best().orElseThrow().destinations().get(10).kind(), CombatAttackDestination.Kind.PLAYER);
            } else {
                Assert.assertTrue(result.best().isEmpty(), "Missing continuation is not zero reply danger");
                Assert.assertFalse(result.candidateSearchComplete());
            }
        }
    }

    @Test
    public void fourAttackersAreReallocatedIntoTwoTwoCreatureKills() {
        final Fixture f = fixture();
        f.opponent().setLife(20, null);
        for (int i = 0; i < 4; i++) { creature(f.ai(), 2, List.of()); }
        final Card first = walker(f.opponent(), 4, 4, false);
        final Card second = walker(f.opponent(), 4, 4, false);
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(1000000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        final var chosen = plan.search().orElseThrow().best().orElseThrow();
        Assert.assertEquals(chosen.combat().projection().planeswalkerLoyaltyAfter().get(first.getId()).intValue(), 0);
        Assert.assertEquals(chosen.combat().projection().planeswalkerLoyaltyAfter().get(second.getId()).intValue(), 0);
        Assert.assertEquals(chosen.destinations().values().stream().filter(d -> d.id() == first.getId()).count(), 2L);
        Assert.assertEquals(chosen.destinations().values().stream().filter(d -> d.id() == second.getId()).count(), 2L);
    }

    @Test
    public void multiWalkerAllocationsShareOneBlockerPool() {
        final Fixture f = fixture();
        for (int i = 0; i < 6; i++) { creature(f.ai(), 2, List.of()); }
        creature(f.opponent(), 1, List.of());
        final Card first = walker(f.opponent(), 4, 4, false);
        final Card second = walker(f.opponent(), 4, 4, false);
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(1000000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        final var chosen = plan.search().orElseThrow().best().orElseThrow();
        Assert.assertEquals(chosen.combat().projection().planeswalkerLoyaltyAfter().get(first.getId()).intValue(), 0);
        Assert.assertEquals(chosen.combat().projection().planeswalkerLoyaltyAfter().get(second.getId()).intValue(), 0);
        Assert.assertTrue(chosen.combat().assignment().blockersByAttacker().values().stream().mapToInt(List::size).sum() <= 1);
    }

    @Test
    public void unrelatedTriggersAndPlayerTargetedUnsupportedOutcomesDoNotDisableUrgency() {
        final Fixture f = fixture();
        card(f.opponent(), List.of("Name:Unrelated Upkeep", "Types:Enchantment",
                "T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | TriggerZones$ Battlefield | Execute$ Trig",
                "SVar:Trig:DB$ GainLife | LifeAmount$ 1"), ZoneType.Battlefield);
        final Card target = walker(f.opponent(), 4, 7, false, List.of(
                "T:Mode$ ChangesZone | Origin$ Battlefield | Destination$ Graveyard | ValidCard$ Creature.YouCtrl | Execute$ Draw",
                "SVar:Draw:DB$ Draw | NumCards$ 1",
                "A:AB$ Mill | Cost$ SubCounter<6/LOYALTY> | ValidTgts$ Player | NumCards$ 20 | Ultimate$ True"));
        final var threat = PlaneswalkerUltimateThreatEvaluator.describe(target);
        Assert.assertEquals(threat.threshold(), 6);
        Assert.assertTrue(threat.potential());
        Assert.assertEquals(target.getCounters(CounterEnumType.LOYALTY), 7);
    }

    @Test
    public void unknownConditionsAndExtraCostsRetainPotentialButVariableCostsDoNotInventThresholds() {
        final Fixture f = fixture();
        final Card conditional = walker(f.opponent(), 4, 7, false, List.of(
                "A:AB$ Draw | Cost$ SubCounter<6/LOYALTY> 2 | ConditionPresent$ Creature.YouCtrl | NumCards$ 1 | Ultimate$ True"));
        Assert.assertEquals(PlaneswalkerUltimateThreatEvaluator.threshold(conditional), 6);
        Assert.assertTrue(PlaneswalkerUltimateThreatEvaluator.describe(conditional).potential());
        final Card variable = walker(f.opponent(), 4, 7, false, List.of(
                "A:AB$ Draw | Cost$ SubCounter<X/LOYALTY> | NumCards$ 1 | Ultimate$ True", "SVar:X:Count$xPaid"));
        Assert.assertEquals(PlaneswalkerUltimateThreatEvaluator.threshold(variable), -1);
        Assert.assertTrue(PlaneswalkerUltimateThreatEvaluator.describe(variable).potential());
    }

    @Test
    public void priorityUsesPerWalkerCoverageAndConsistentManaFallback() {
        final Fixture f = fixture();
        final Card cheap = walker(f.opponent(), 2, 4, false);
        final Card expensive = walker(f.opponent(), 6, 4, false);
        final var snapshot = PublicCombatSnapshot.capture(f.ai(), f.combat());
        for (final boolean complete : List.of(true, false)) {
            final var values = new PreparedCombatValuation(Map.of(
                    cheap.getId(), new PreparedCombatValuation.PermanentValue(cheap.getId(), f.opponent().getId(), 100, 0, 900, List.of(), 100, true),
                    expensive.getId(), new PreparedCombatValuation.PermanentValue(expensive.getId(), f.opponent().getId(), 400, 0, 0, List.of(), 100, complete)),
                    List.of(), forge.ai.effect.ValuationCompleteness.PARTIAL, List.of());
            Assert.assertEquals(CombatAttackDestinationSearch.allWalkerAbilitiesComplete(snapshot, values), complete);
            Assert.assertEquals(CombatAttackDestinationSearch.priorityWalkers(snapshot, values).get(0).id(), complete ? cheap.getId() : expensive.getId());
        }
    }

    @Test
    public void urgentDenialLeavesAttackersForAnotherKill() {
        final Fixture f = fixture();
        final Card firstAttacker = creature(f.ai(), 2, List.of());
        final Card secondAttacker = creature(f.ai(), 2, List.of());
        final Card urgent = walker(f.opponent(), 4, 7, true);
        final Card other = walker(f.opponent(), 6, 2, false);
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(1000000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        final var chosen = plan.search().orElseThrow().best().orElseThrow();
        Assert.assertEquals(chosen.combat().projection().planeswalkerLoyaltyAfter().get(urgent.getId()).intValue(), 5);
        Assert.assertEquals(chosen.combat().projection().planeswalkerLoyaltyAfter().get(other.getId()).intValue(), 0);
        Assert.assertEquals(java.util.Set.copyOf(chosen.attackers()), java.util.Set.of(firstAttacker.getId(), secondAttacker.getId()));
        Assert.assertEquals(CombatAttackDestinationSearch.priorityWalkers(plan.snapshot().orElseThrow(),
                forge.ai.effect.CombatValuationEvaluator.prepare(forge.ai.effect.ValuationContext.forCombat(f.ai(), forge.ai.effect.ValuationDecision.ATTACK, 0, 100))).get(0).id(), urgent.getId());
    }

    @Test
    public void activationRestrictionsAreRelevantNotBlanketDisablers() {
        final Fixture f = fixture();
        final Card target = walker(f.opponent(), 4, 7, true);
        card(f.opponent(), List.of("Name:Irrelevant Prohibition", "Types:Enchantment",
                "S:Mode$ CantBeActivated | ValidCard$ Creature | ValidSA$ Activated"), ZoneType.Battlefield);
        Assert.assertTrue(PlaneswalkerUltimateThreatEvaluator.describe(target).potential());
        card(f.opponent(), List.of("Name:Relevant Prohibition", "Types:Enchantment",
                "S:Mode$ CantBeActivated | ValidCard$ Planeswalker | ValidSA$ Activated"), ZoneType.Battlefield);
        Assert.assertFalse(PlaneswalkerUltimateThreatEvaluator.describe(target).potential(),
                PlaneswalkerUltimateThreatEvaluator.describe(target) + "; rules=" + f.game().getCardsIn(ZoneType.Battlefield).stream()
                        .flatMap(c -> c.getStaticAbilities().stream()).map(a -> a.getMapParams() + "/" + a.checkConditions(forge.game.staticability.StaticAbilityMode.CantBeActivated)).toList());
    }

    @Test
    public void multipleUltimateModesAndUnknownAlternativesStayHonest() {
        final Fixture f = fixture();
        final Card target = walker(f.opponent(), 4, 7, true);
        target.addSpellAbility(forge.game.ability.AbilityFactory.getAbility(
                "AB$ Draw | Cost$ SubCounter<X/LOYALTY> | NumCards$ 1 | Ultimate$ True", target));
        final var threat = PlaneswalkerUltimateThreatEvaluator.describe(target);
        Assert.assertTrue(threat.potential());
        Assert.assertEquals(threat.threshold(), -1, "Unknown alternative cannot be assigned a fabricated denial threshold");
        final Card attacker = creature(f.ai(), 7, List.of());
        f.combat().addAttacker(attacker, target);
        final var snapshot = PublicCombatSnapshot.capture(f.ai(), f.combat());
        final var projection = CombatOutcomePredictor.predict(snapshot, new CombatAssignment(snapshot.attackersToDefenders(), Map.of()));
        Assert.assertEquals(CombatTransitionValueEvaluator.planeswalkerUtility(snapshot, projection), 300,
                "Removing the source denies its potential ultimate even if nonlethal threshold is unresolved");
    }

    @Test
    public void coreDamagePreventionStillRejectsUnsupportedWalkerCombat() {
        final Fixture f = fixture();
        final Card attacker = creature(f.ai(), 2, List.of());
        final Card target = walker(f.opponent(), 4, 2, false);
        target.setCounters(CounterEnumType.SHIELD, 1);
        f.combat().addAttacker(attacker, target);
        final var snapshot = PublicCombatSnapshot.capture(f.ai(), f.combat());
        final var projection = CombatOutcomePredictor.predict(snapshot, new CombatAssignment(snapshot.attackersToDefenders(), Map.of()));
        Assert.assertFalse(projection.available(), "Permissive strategic policy must not invent shielded damage results");
        Assert.assertEquals(target.getCounters(CounterEnumType.SHIELD), 1);
    }

    private Fixture fixture() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(0);
        final Player opponent = game.getPlayers().get(1);
        ai.setTeam(0); opponent.setTeam(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Mastermind");
        opponent.setLife(100, null);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, ai);
        final Combat combat = new Combat(ai);
        game.getPhaseHandler().setCombat(combat);
        for (final Player player : game.getPlayers()) {
            for (int i = 0; i < 8; i++) { card(player, List.of("Name:Library Fixture", "Types:Land"), ZoneType.Library); }
        }
        return new Fixture(game, ai, opponent, combat);
    }

    @Test
    public void vanillaWalkerAllocationsFitRoutineNodesAndPreserveAmpleBudgetDecisions() {
        for (final int[] counts : List.of(new int[]{4, 0}, new int[]{4, 1}, new int[]{4, 2}, new int[]{6, 1})) {
            final Fixture f = fixture();
            f.opponent().setLife(20, null);
            for (int i = 0; i < counts[0]; i++) { creature(f.ai(), 2, List.of()); }
            for (int i = 0; i < counts[1]; i++) { creature(f.opponent(), 1, List.of()); }
            walker(f.opponent(), 4, 4, false);
            walker(f.opponent(), 4, 4, false);
            final var ample = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(1000000, 30000, () -> 0L));
            final var total = new CombatSearchBudget(50000, 500, () -> 0L);
            final long started = System.nanoTime();
            final var routine = CombatAttackPlanner.plan(f.ai(), f.combat(), total.planningAllowance());
            final long elapsed = (System.nanoTime() - started) / 1000000;
            final var snapshot = ample.snapshot().orElseThrow();
            final var withoutSingletons = new PublicCombatSnapshot(snapshot.observingPlayerId(), snapshot.attackingPlayerId(), snapshot.defendingPlayerId(),
                    snapshot.creatures(), snapshot.players(), snapshot.attackersToDefenders(), snapshot.legalBlockers(), snapshot.unavailableReasons(),
                    snapshot.unsupportedReasons(), snapshot.legacyDamageOrder(), snapshot.resources(), snapshot.triggers(), snapshot.observedAttackers(),
                    snapshot.observedBlockers(), snapshot.preventionRules(), snapshot.staticWorlds(), snapshot.ignoredEffects(), snapshot.planeswalkers(), false);
            final var readiness = ample.readiness().orElseThrow();
            final var identityReadiness = new PublicCombatReadiness(readiness.nextActivePlayerId(), readiness.canAttackNextTurn(),
                    readiness.blockPairsNextTurn(), readiness.unsupportedReasons(), readiness.ignoredEffects(), false);
            final var prepared = CombatPlanningPreparation.values(f.ai(), forge.ai.effect.ValuationDecision.ATTACK,
                    new CombatSearchBudget(1000000, 30000, () -> 0L)).orElseThrow();
            final var cacheOnly = CombatAttackDestinationSearch.search(withoutSingletons, prepared, identityReadiness,
                    new CombatSearchBudget(1000000, 30000, () -> 0L), ample.fixedAttackers(), ample.legalDestinations());
            System.out.println("COMBAT_REUSE_MEASUREMENT attackers=" + counts[0] + " blockers=" + counts[1]
                    + " nodes=" + total.used() + " ampleNodes=" + ample.search().orElseThrow().nodes()
                    + " cacheOnlySearchNodes=" + cacheOnly.nodes() + " elapsedMs=" + elapsed);
            Assert.assertTrue(routine.applicable(), routine.reasons().toString());
            Assert.assertEquals(routine.search().orElseThrow().best(), ample.search().orElseThrow().best());
            Assert.assertEquals(routine.search().orElseThrow().best().orElseThrow().total(), cacheOnly.best().orElseThrow().total());
            Assert.assertEquals(routine.search().orElseThrow().best().orElseThrow().destinations(), cacheOnly.best().orElseThrow().destinations());
            Assert.assertTrue(ample.search().orElseThrow().nodes() < cacheOnly.nodes());
            Assert.assertTrue(total.used() < 40000);
            Assert.assertTrue(total.remaining() >= 10000);
            Assert.assertTrue(routine.snapshot().orElseThrow().singletonPermutationSafe());
            Assert.assertFalse(routine.snapshot().orElseThrow().ignoredEffects().isEmpty(), "Reuse does not clear approximation diagnostics");
        }
    }

    @Test
    public void bookkeepingAdmissionDoesNotAdmitRealUnknownWalkerAbilities() {
        final Fixture f = fixture();
        final Card first = creature(f.ai(), 2, List.of());
        final Card second = creature(f.ai(), 2, List.of());
        final Card target = walker(f.opponent(), 4, 4, false);
        f.combat().addAttacker(first, target);
        f.combat().addAttacker(second, target);
        final var plain = PublicCombatSnapshot.capture(f.ai(), f.combat());
        Assert.assertTrue(plain.singletonPermutationSafe());
        final var values = forge.ai.effect.CombatValuationEvaluator.prepare(forge.ai.effect.ValuationContext.forCombat(
                f.ai(), forge.ai.effect.ValuationDecision.ATTACK, 0, 100));
        final var ready = PublicCombatReadiness.capture(f.ai(), plain);
        final var reused = CombatAttackEquivalence.representatives(plain, values, ready, List.of(first.getId(), second.getId()));
        Assert.assertEquals(reused.get(first.getId()), reused.get(second.getId()));
        target.addSpellAbility(forge.game.ability.AbilityFactory.getAbility(
                "AB$ Draw | Cost$ SubCounter<1/LOYALTY> | Defined$ You | NumCards$ 1", target));
        final var opaque = PublicCombatSnapshot.capture(f.ai(), f.combat());
        Assert.assertFalse(opaque.singletonPermutationSafe());
        final var distinct = CombatAttackEquivalence.representatives(opaque, values, PublicCombatReadiness.capture(f.ai(), opaque),
                List.of(first.getId(), second.getId()));
        Assert.assertNotEquals(distinct.get(first.getId()), distinct.get(second.getId()));
        Assert.assertEquals(target.getCounters(CounterEnumType.LOYALTY), 4);
    }

    private static Card creature(final Player owner, final int power, final List<String> keywords) {
        final List<String> script = new ArrayList<>(List.of("Name:Destination Creature", "ManaCost:2", "Types:Creature Human", "PT:" + power + "/3"));
        script.addAll(keywords);
        final Card result = card(owner, script, ZoneType.Battlefield);
        result.setSickness(false);
        return result;
    }

    private static Card walker(final Player owner, final int mana, final int loyalty, final boolean ultimate) {
        return walker(owner, mana, loyalty, ultimate, List.of());
    }

    private static Card walker(final Player owner, final int mana, final int loyalty, final boolean ultimate, final List<String> extras) {
        final List<String> script = new ArrayList<>(List.of("Name:Destination Walker " + mana + " " + loyalty,
                "ManaCost:" + mana, "Types:Planeswalker", "Loyalty:" + loyalty));
        if (ultimate) { script.add("A:AB$ Draw | Cost$ SubCounter<6/LOYALTY> | Defined$ You | NumCards$ 1 | Ultimate$ True"); }
        script.addAll(extras);
        final Card result = card(owner, script, ZoneType.Battlefield);
        result.setCounters(CounterEnumType.LOYALTY, loyalty);
        return result;
    }

    private static Card card(final Player owner, final List<String> script, final ZoneType zone) {
        final Card card = Card.fromPaperCard(new PaperCard(CardRules.fromScript(script), CardEdition.UNKNOWN_CODE, CardRarity.Special), owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());
        owner.getZone(zone).add(card);
        return card;
    }
}
