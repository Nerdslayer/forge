package forge.ai.combat;

import java.util.ArrayList;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.LobbyPlayerAi;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

public class CombatAttackPlannerTest extends AITest {
    private record Fixture(Game game, Player ai, Player opponent, Combat combat) { }

    @Test
    public void staleBlockLegalityCannotProduceAnApplicableExaltedWin() {
        final Fixture f = fixture();
        f.opponent().setLife(3, null);
        final Card attacker = creature(f.ai(), 2, 2, List.of());
        creature(f.ai(), 0, 2, List.of("K:Exalted", "K:Defender"));
        final Card blocker = addCard("Cyclops Tyrant", f.opponent());

        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(100000, 500, () -> 0L));
        Assert.assertFalse(plan.applicable());
        Assert.assertTrue(plan.reasons().contains("Exalted can change frozen block legality"));

        f.game().getAction().moveToGraveyard(blocker, null);
        final var safe = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(100000, 500, () -> 0L));
        Assert.assertTrue(safe.applicable(), safe.reasons().toString());
        Assert.assertEquals(safe.search().orElseThrow().best().orElseThrow().attackers(), List.of(attacker.getId()));
    }

    @Test
    public void exaltedMakesLoneAttackProfitableAgainstLargerBlocker() {
        for (final boolean exalted : List.of(false, true)) {
            final Fixture f = fixture();
            final Card attacker = creature(f.ai(), 2, 2, List.of());
            final Card second = creature(f.ai(), 0, 1, List.of());
            final List<String> keywords = exalted ? List.of("K:Exalted") : List.of();
            creature(f.ai(), 0, 2, keywords).setSickness(true);
            creature(f.ai(), 0, 2, keywords).setTapped(true);
            final var source = Card.fromPaperCard(new PaperCard(CardRules.fromScript(exalted
                    ? List.of("Name:Exalted Fixture", "Types:Enchantment", "K:Exalted")
                    : List.of("Name:Exalted Fixture", "Types:Enchantment")),
                    CardEdition.UNKNOWN_CODE, CardRarity.Special), f.ai());
            f.ai().getZone(ZoneType.Battlefield).add(source);
            creature(f.opponent(), 4, 4, List.of("K:Defender"));

            final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(100000, 500, () -> 0L));
            Assert.assertTrue(plan.applicable(), plan.reasons().toString());
            Assert.assertEquals(plan.search().orElseThrow().best().orElseThrow().attackers(),
                    exalted ? List.of(attacker.getId()) : List.of());
            final var lone = CombatAttackCandidates.select(plan.snapshot().orElseThrow(), List.of(attacker.getId()));
            Assert.assertEquals(lone.combatCreature(attacker.getId()).combatDamage(), exalted ? 5 : 2);
            final var pair = CombatAttackCandidates.assign(plan.snapshot().orElseThrow(), java.util.Map.of(
                    attacker.getId(), f.opponent().getId(), second.getId(), f.opponent().getId()));
            Assert.assertEquals(pair.combatCreature(attacker.getId()).combatDamage(), 2);
            Assert.assertEquals(attacker.getNetPower(), 2);
        }
    }

    @Test
    public void exaltedProjectsSignedPowerAndUsesObservedStatsAfterResolution() {
        final Fixture f = fixture();
        final Card attacker = creature(f.ai(), -1, 2, List.of("K:Exalted"));
        final Combat detached = new Combat(f.ai());
        detached.addAttackerForValidation(attacker, f.opponent());
        final var candidate = PublicCombatSnapshot.capture(f.ai(), detached);
        Assert.assertEquals(candidate.combatCreature(attacker.getId()).combatDamage(), 0);
        Assert.assertEquals(candidate.combatCreature(attacker.getId()).toughness(), 3);

        f.combat().addAttacker(attacker, f.opponent());
        f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, f.ai(), false);
        attacker.addPTBoost(1, 1, f.game().getNextTimestamp(), 0);
        final var observed = PublicCombatSnapshot.capture(f.ai(), f.combat());
        Assert.assertEquals(observed.exalted().bonus(), 0);
        Assert.assertEquals(observed.combatCreature(attacker.getId()).toughness(), 3);
        Assert.assertTrue(PublicCombatReadiness.capture(f.ai(), observed).unsupportedReasons().isEmpty());
    }

    @Test
    public void liveForecastExpiresOnlyTheResolvedExaltedBoost() {
        final Fixture f = fixture();
        final Card attacker = creature(f.ai(), 2, 2, List.of());
        final Card source = creature(f.ai(), 0, 2, List.of("K:Exalted", "K:Exalted"));
        f.combat().addAttacker(attacker, f.opponent());
        f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, f.ai(), false);
        for (final var trigger : source.getTriggers()) {
            final var pump = trigger.ensureAbility();
            pump.setActivatingPlayer(f.ai());
            pump.setTriggeringObject(forge.game.ability.AbilityKey.Attacker, attacker);
            pump.resolve();
        }
        f.game().getAction().moveToGraveyard(source, null);
        Assert.assertEquals(attacker.getExaltedBoost(), 2);
        attacker.addPTBoost(3, 3, f.game().getNextTimestamp(), 0);
        final var snapshot = PublicCombatSnapshot.capture(f.ai(), f.combat());
        Assert.assertTrue(snapshot.exalted().sources().isEmpty());
        Assert.assertEquals(snapshot.combatCreature(attacker.getId()).toughness(), 7);
        final var projection = CombatOutcomePredictor.predict(snapshot,
                new CombatAssignment(snapshot.attackersToDefenders(), java.util.Map.of()));
        final var values = new PreparedCombatValuation(java.util.Map.of(), List.of(),
                forge.ai.effect.ValuationCompleteness.COMPLETE, List.of());
        final var next = CombatTurnProjectionBuilder.advance(snapshot, projection,
                PublicCombatReadiness.capture(f.ai(), snapshot), values).turn().orElseThrow().alternatives();
        Assert.assertEquals(next.creatures().get(attacker.getId()).toughness(), 5);
        Assert.assertEquals(next.creatures().get(attacker.getId()).combatDamage(), 5);
        Assert.assertEquals(forge.game.card.CardCopyService.getLKICopy(attacker).getExaltedBoost(), 2);
        final Game copy = new forge.ai.simulation.GameCopier(f.game()).makeCopy();
        final Card copiedAttacker = copy.getCardsIn(ZoneType.Battlefield).stream()
                .filter(card -> card.getId() == attacker.getId()).findFirst().orElseThrow();
        Assert.assertEquals(copiedAttacker.getNetToughness(), 7);
        Assert.assertEquals(copiedAttacker.getExaltedBoost(), 2);
        final var moved = f.game().getAction().moveToGraveyard(attacker, null);
        Assert.assertEquals(moved.getExaltedBoost(), 0);
        f.game().getEndOfTurn().executeUntil();
        Assert.assertEquals(attacker.getExaltedBoost(), 0);
    }

    @Test
    public void futureExaltedDropsTheGrantButRetainsThePrintedInstance() {
        final Fixture f = fixture();
        final Card printed = creature(f.opponent(), 2, 2, List.of("K:Exalted"));
        final Card grantor = addCard("Sublime Archangel", f.opponent());
        final Card attacker = creature(f.ai(), 5, 5, List.of("K:Flying"));
        f.game().getAction().checkStaticAbilities();
        final Combat detached = new Combat(f.ai());
        detached.addAttackerForValidation(attacker, f.opponent());
        final var snapshot = PublicCombatSnapshot.capture(f.ai(), detached);
        final var readiness = PublicCombatReadiness.capture(f.ai(), snapshot);
        final var values = new PreparedCombatValuation(java.util.Map.of(), List.of(),
                forge.ai.effect.ValuationCompleteness.COMPLETE, List.of());
        final var projection = CombatOutcomePredictor.predict(snapshot, new CombatAssignment(
                snapshot.attackersToDefenders(), java.util.Map.of(attacker.getId(), List.of(grantor.getId()))));
        Assert.assertTrue(projection.available(), projection.reasons().toString());
        Assert.assertTrue(projection.lostCreatures().contains(grantor.getId()));
        Assert.assertEquals(snapshot.exalted().sources().size(), 3);
        final var next = CombatTurnProjectionBuilder.advance(snapshot, projection, readiness, values)
                .turn().orElseThrow().alternatives();
        final var alone = CombatAttackCandidates.select(next, List.of(printed.getId()));
        Assert.assertEquals(alone.exalted().bonus(), 1);
    }

    @Test
    public void opponentForecastRecountsSurvivingSourcesAfterCleanup() {
        final Fixture f = fixture();
        f.ai().setLife(3, null);
        final Card attacker = creature(f.ai(), 2, 2, List.of("K:Exalted"));
        final Card source = creature(f.opponent(), 0, 2, List.of("K:Exalted", "K:Defender"));
        final Card reply = creature(f.opponent(), 2, 2, List.of());
        final Combat detached = new Combat(f.ai());
        detached.addAttackerForValidation(attacker, f.opponent());
        final var snapshot = PublicCombatSnapshot.capture(f.ai(), detached);
        final var readiness = PublicCombatReadiness.capture(f.ai(), snapshot);
        final var values = new PreparedCombatValuation(java.util.Map.of(), List.of(),
                forge.ai.effect.ValuationCompleteness.COMPLETE, List.of());
        for (final boolean blocked : List.of(false, true)) {
            final var blocks = blocked ? java.util.Map.of(attacker.getId(), List.of(source.getId()))
                    : java.util.Map.<Integer, List<Integer>>of();
            final var projection = CombatOutcomePredictor.predict(snapshot,
                    new CombatAssignment(snapshot.attackersToDefenders(), blocks));
            Assert.assertTrue(projection.available(), projection.reasons().toString());
            Assert.assertEquals(projection.lostCreatures().contains(source.getId()), blocked);
            final var next = CombatTurnProjectionBuilder.advance(snapshot, projection, readiness, values)
                    .turn().orElseThrow().alternatives();
            final var candidate = CombatAttackCandidates.select(next, List.of(reply.getId()));
            Assert.assertEquals(candidate.combatCreature(reply.getId()).combatDamage(), blocked ? 2 : 3);
            Assert.assertEquals(candidate.creatures().get(attacker.getId()).toughness(), 2);
            Assert.assertEquals(CombatSafetyEvaluator.nextAttack(snapshot, projection, readiness, values,
                    new CombatSearchBudget(20000)).lethalOpportunity(), !blocked);
        }
    }

    @Test
    public void exaltedCountsInstancesAndAppliesAcrossBothDoubleStrikeSteps() {
        final Fixture f = fixture();
        final Card attacker = creature(f.ai(), 1, 2, List.of("K:Double strike", "K:Exalted", "K:Exalted"));
        final Card second = creature(f.ai(), 1, 2, List.of("K:Exalted"));
        second.getTriggers().getFirst().setSuppressed(true);
        final Card walker = addCard("Jace Beleren", f.opponent());
        walker.setCounters(forge.game.card.CounterEnumType.LOYALTY, 7);
        final Combat detached = new Combat(f.ai());
        detached.addAttackerForValidation(attacker, walker);
        detached.addAttackerForValidation(second, f.opponent());
        final var alternatives = PublicCombatSnapshot.capture(f.ai(), detached);
        final var alone = CombatAttackCandidates.select(alternatives, List.of(attacker.getId()));
        Assert.assertEquals(alone.exalted().bonus(), 2);
        final var projection = CombatOutcomePredictor.predict(alone,
                new CombatAssignment(alone.attackersToDefenders(), java.util.Map.of()));
        Assert.assertTrue(projection.available(), projection.reasons().toString());
        Assert.assertEquals(projection.planeswalkerLoyaltyAfter().get(walker.getId()), Integer.valueOf(1));
        final var split = CombatAttackCandidates.select(alternatives,
                List.of(attacker.getId(), second.getId()));
        Assert.assertEquals(split.exalted().bonus(), 0);
    }

    @Test
    public void redundantLooterStaysHomeButFixedAndUsefulZeroPowerAttackersRemain() {
        for (final boolean fixed : List.of(false, true)) {
            for (final boolean usefulTrigger : List.of(false, true)) {
                final Fixture f = fixture();
                final List<String> extras = usefulTrigger ? List.of(
                        "T:Mode$ Attacks | ValidCard$ Card.Self | TriggerZones$ Battlefield | Execute$ Draw",
                        "SVar:Draw:DB$ Draw | Defined$ You | NumCards$ 1") : List.of(
                        "A:AB$ Draw | Cost$ T | NumCards$ 1 | Defined$ You");
                final Card zero = creature(f.ai(), 0, 2, extras);
                final Card first = creature(f.ai(), 2, 4, List.of("K:Flying"));
                final Card second = creature(f.ai(), 2, 2, List.of());
                creature(f.opponent(), 0, 5, List.of("K:Reach"));
                if (fixed) { f.combat().addAttacker(zero, f.opponent()); }
                final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(200000, 30000, () -> 0L));
                Assert.assertTrue(plan.applicable(), plan.reasons().toString());
                final var selected = plan.search().orElseThrow().best().orElseThrow();
                Assert.assertEquals(selected.attackers().contains(zero.getId()), fixed || usefulTrigger);
                Assert.assertTrue(selected.attackers().containsAll(List.of(first.getId(), second.getId())));
                Assert.assertTrue(CombatAttackPlanner.tryDeclare(f.ai(), f.ai(), f.combat(), new CombatSearchBudget(200000, 30000, () -> 0L)));
                Assert.assertEquals(f.combat().getAttackers().contains(zero), fixed || usefulTrigger);
                Assert.assertFalse(zero.isTapped(), "The planner does not perform rules-engine tapping");
            }
        }
    }

    @Test
    public void positivePowerAttackerAlsoStaysHomeWhenItDoesNotImproveAnUnblockedAttack() {
        final Fixture f = fixture();
        final Card redundant = creature(f.ai(), 2, 2, List.of());
        final Card flyer = creature(f.ai(), 2, 2, List.of("K:Flying"));
        creature(f.opponent(), 0, 4, List.of());
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(100000, 500, () -> 0L));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertEquals(plan.search().orElseThrow().best().orElseThrow().attackers(), List.of(flyer.getId()));
        Assert.assertFalse(redundant.isTapped());
    }

    @Test
    public void holdsInsteadOfTakingPointlessDamageWhenBothContinuationsPredictLethal() {
        for (final boolean fixedAttack : List.of(false, true)) {
            final Fixture f = fixture();
            f.ai().setLife(1, null);
            f.opponent().setLife(35, null);
            final Card optional = creature(f.ai(), 2, 2, List.of("K:Flying"));
            creature(f.opponent(), 8, 8, List.of());
            creature(f.opponent(), 8, 8, List.of());
            final Card fixed = fixedAttack ? creature(f.ai(), 0, 4, List.of("K:Flying")) : null;
            if (fixed != null) { f.combat().addAttacker(fixed, f.opponent()); }
            final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(100000));
            Assert.assertTrue(plan.applicable(), plan.reasons().toString());
            final var result = plan.search().orElseThrow();
            Assert.assertTrue(result.noAttack().orElseThrow().reply().orElseThrow().lethalOpportunity());
            Assert.assertEquals(result.best(), result.noAttack());
            Assert.assertEquals(result.best().orElseThrow().attackers(), fixed == null ? List.of() : List.of(fixed.getId()));
            Assert.assertTrue(CombatAttackPlanner.tryDeclare(f.ai(), f.ai(), f.combat(), new CombatSearchBudget(100000)),
                    "Choosing hold is an applied Mastermind decision, not a legacy fallback");
            Assert.assertEquals(f.combat().getAttackers(), fixed == null ? List.of() : List.of(fixed));
            Assert.assertFalse(optional.getView().isAttacking() || optional.isTapped());
            Assert.assertTrue(CombatUtil.validateAttackers(f.combat()));
        }
    }

    @Test
    public void lifelinkAttackThatEscapesAnOtherwiseLethalReplyStillAttacks() {
        final Fixture f = fixture();
        f.ai().setLife(1, null);
        final Card attacker = creature(f.ai(), 4, 1, List.of("K:Flying", "K:Lifelink"));
        // Blocking dies to first strike before gaining life; attacking gains enough to survive both.
        creature(f.opponent(), 2, 2, List.of("K:First strike"));
        creature(f.opponent(), 2, 2, List.of("K:First strike"));
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(100000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        final var result = plan.search().orElseThrow();
        Assert.assertTrue(result.noAttack().orElseThrow().reply().orElseThrow().lethalOpportunity());
        Assert.assertFalse(result.best().orElseThrow().reply().orElseThrow().lethalOpportunity());
        Assert.assertEquals(result.best().orElseThrow().attackers(), List.of(attacker.getId()));
    }

    @Test
    public void combinedImmediateWinIsStillFoundWhenHoldingAndSingletonsPredictLethal() {
        for (final boolean approximate : List.of(false, true)) {
            final Fixture f = fixture();
            f.ai().setLife(1, null);
            f.opponent().setLife(3, null);
            final var extras = new ArrayList<>(List.of("K:Flying"));
            if (approximate) { extras.add("A:AB$ Pump | Cost$ U | NumAtt$ 1 | NumDef$ 0 | Defined$ Self"); }
            final Card first = creature(f.ai(), 2, 2, extras);
            final Card second = creature(f.ai(), 2, 2, List.of("K:Flying"));
            for (int i = 0; i < 3; i++) { creature(f.opponent(), 1, 1, List.of()); }
            final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(100000));
            Assert.assertTrue(plan.applicable(), plan.reasons().toString());
            final var result = plan.search().orElseThrow();
            Assert.assertTrue(result.noAttack().orElseThrow().reply().orElseThrow().lethalOpportunity());
            Assert.assertEquals(java.util.Set.copyOf(result.best().orElseThrow().attackers()),
                    java.util.Set.of(first.getId(), second.getId()));
            Assert.assertEquals(result.best().orElseThrow().combat().projection().terminal(), CombatProjection.Terminal.WIN);
            Assert.assertEquals(result.best().orElseThrow().certifiedWin(), !approximate);
        }
    }

    @Test
    public void selfSacrificeDrawbackDoesNotInviteAStatReducedAttackerIntoAFreeKill() {
        final Fixture f = fixture();
        f.ai().setLife(7, null);
        f.opponent().setLife(31, null);
        final Card fragile = addCard("Phantom Beast", f.ai());
        fragile.setSickness(false);
        final Card flyer = creature(f.ai(), 6, 7, List.of("K:Flying"));
        final Card reach = creature(f.opponent(), 3, 5, List.of("K:Reach"));
        final Card large = creature(f.opponent(), 3, 7, List.of(
                "S:Mode$ Continuous | Affected$ Creature.OppCtrl | AddPower$ -2 | AddToughness$ -2"));
        f.game().getAction().checkStaticAbilities();
        Assert.assertEquals(fragile.getNetPower(), 2);
        Assert.assertEquals(fragile.getNetToughness(), 3);
        Assert.assertEquals(flyer.getNetPower(), 4);
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(1000000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertTrue(plan.search().orElseThrow().best().orElseThrow().attackers().isEmpty(),
                "Both attacks can be blocked without losing a blocker or taking damage");
        final var snapshot = plan.snapshot().orElseThrow();
        final var values = forge.ai.effect.CombatValuationEvaluator.prepare(
                forge.ai.effect.ValuationContext.forCombat(f.ai(), forge.ai.effect.ValuationDecision.ATTACK, 0, 100));
        final var attack = CombatAttackCandidates.select(snapshot, List.of(fragile.getId(), flyer.getId()));
        final var response = CombatBlockSearch.search(attack, values, new CombatSearchBudget(100000));
        Assert.assertTrue(response.searchExhaustive(), response.reasons().toString());
        Assert.assertEquals(response.best().orElseThrow().assignment().blockersByAttacker(),
                java.util.Map.of(fragile.getId(), List.of(large.getId()), flyer.getId(), List.of(reach.getId())));
        Assert.assertTrue(response.best().orElseThrow().score().total() < 0);
    }

    @Test
    public void menaceAttackIgnoresAnOtherwiseProfitableButIllegalSoloBlock() {
        final Fixture f = fixture();
        final Card attacker = creature(f.ai(), 2, 2, List.of("K:Menace"));
        creature(f.opponent(), 4, 4, List.of());
        f.opponent().setLife(2, null);
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(20000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertTrue(plan.search().orElseThrow().best().orElseThrow().certifiedWin());
        Assert.assertTrue(plan.search().orElseThrow().best().orElseThrow().combat().assignment().blockersByAttacker().isEmpty());
        Assert.assertTrue(CombatAttackPlanner.tryDeclare(f.ai(), f.ai(), f.combat(), new CombatSearchBudget(20000)));
        Assert.assertEquals(f.combat().getAttackers(), List.of(attacker));
    }

    @Test
    public void overloadedAttackIsPlannedReadOnlyThenValidatedAndAppliedThroughGatedController() {
        final Fixture f = fixture();
        final Card first = creature(f.ai(), 2, 2, List.of());
        final Card second = creature(f.ai(), 2, 2, List.of());
        final Card blocker = creature(f.opponent(), 1, 3, List.of());
        final int sentinel = f.game().nextCardId();
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(20000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertEquals(plan.search().orElseThrow().best().orElseThrow().attackers(), List.of(first.getId(), second.getId()));
        Assert.assertTrue(f.combat().getAttackers().isEmpty());
        Assert.assertFalse(first.getView().isAttacking() || second.getView().isAttacking() || blocker.getView().isBlocking());
        Assert.assertEquals(f.game().nextCardId(), sentinel + 1);
        Assert.assertTrue(AiProfileUtil.getBoolProperty(f.ai(), AiProps.ENABLE_COMBAT_ATTACK_PLANNING));
        final AiController controller = new AiController(f.ai(), f.game());
        controller.declareAttackers(f.ai(), f.combat());
        Assert.assertEquals(f.combat().getAttackers().size(), 2);
        Assert.assertTrue(first.getView().isAttacking() && second.getView().isAttacking());
        Assert.assertFalse(first.isTapped() || second.isTapped(), "Rules engine, not the planner, handles attack tapping");
        Assert.assertTrue(CombatUtil.validateAttackers(f.combat()));
        Assert.assertEquals(controller.getAttackAggression(), 4);
    }

    @Test
    public void nextTurnRiskPreservesAnAnchorAndDoesNotDeclareHypotheticalEnemyBlocks() {
        final Fixture f = fixture();
        f.ai().setLife(4, null);
        final Card anchor = creature(f.ai(), 5, 5, List.of());
        creature(f.opponent(), 2, 2, List.of());
        creature(f.opponent(), 2, 2, List.of());
        Assert.assertTrue(CombatAttackPlanner.tryDeclare(f.ai(), f.ai(), f.combat(), new CombatSearchBudget(20000)));
        Assert.assertTrue(f.combat().getAttackers().isEmpty());
        Assert.assertTrue(f.combat().getAllBlockers().isEmpty());
        Assert.assertFalse(anchor.isTapped() || anchor.getView().isAttacking());
    }

    @Test
    public void certifiedImmediateLethalDoesNotRequireAnAvailableFutureReply() {
        final Fixture f = fixture();
        f.opponent().setLife(2, null);
        creature(f.ai(), 2, 2, List.of());
        creature(f.ai(), 2, 2, List.of());
        creature(f.opponent(), 4, 4, List.of());
        f.ai().getZone(ZoneType.Library).removeAllCards(true);
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(20000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertTrue(plan.search().orElseThrow().best().orElseThrow().certifiedWin());
        Assert.assertFalse(plan.search().orElseThrow().outcomeDomainComplete(), "Unneeded future branches remain explicitly incomplete");
        Assert.assertTrue(CombatAttackPlanner.tryDeclare(f.ai(), f.ai(), f.combat(), new CombatSearchBudget(20000)));
        Assert.assertEquals(f.combat().getAttackers().size(), 2);
        Assert.assertTrue(f.combat().getAllBlockers().isEmpty());
    }

    @Test
    public void fixedAttacksArePreservedUnknownActivationsAreApproximatedAndEmptyBudgetRemainsUntouched() {
        final Fixture fixed = fixture();
        final Card already = creature(fixed.ai(), 2, 2, List.of());
        fixed.combat().addAttacker(already, fixed.opponent());
        Assert.assertTrue(CombatAttackPlanner.tryDeclare(fixed.ai(), fixed.ai(), fixed.combat(), new CombatSearchBudget(20000)));
        Assert.assertEquals(fixed.combat().getAttackers(), List.of(already));
        final Fixture unsupported = fixture();
        final Card pump = creature(unsupported.ai(), 2, 2, List.of("A:AB$ Pump | Cost$ U | NumAtt$ 1 | NumDef$ -1 | Defined$ Self"));
        Assert.assertTrue(CombatAttackPlanner.tryDeclare(unsupported.ai(), unsupported.ai(), unsupported.combat(), new CombatSearchBudget(20000)));
        Assert.assertEquals(unsupported.combat().getAttackers(), List.of(pump));
        final Fixture budget = fixture();
        creature(budget.ai(), 2, 2, List.of());
        Assert.assertFalse(CombatAttackPlanner.tryDeclare(budget.ai(), budget.ai(), budget.combat(), new CombatSearchBudget(0)));
        Assert.assertTrue(budget.combat().getAttackers().isEmpty());
        Assert.assertFalse(CombatAttackPlanner.tryDeclare(budget.ai(), budget.opponent(), budget.combat(), new CombatSearchBudget(20000)));
        Assert.assertFalse(CombatAttackPlanner.tryDeclare(budget.ai(), budget.ai(), new Combat(budget.ai()), new CombatSearchBudget(20000)));
    }

    @Test
    public void fixedAttackBaselineCanBeExtendedToLethalWithoutDuplicatingOrMovingAttackers() {
        final Fixture f = fixture();
        final Card fixed = creature(f.ai(), 2, 2, List.of());
        final Card extra = creature(f.ai(), 2, 2, List.of());
        creature(f.opponent(), 4, 4, List.of());
        f.opponent().setLife(2, null);
        f.combat().addAttacker(fixed, f.opponent());
        final int sentinel = f.game().nextCardId();
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(20000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertEquals(plan.search().orElseThrow().noAttack().orElseThrow().attackers(), List.of(fixed.getId()));
        Assert.assertEquals(f.combat().getAttackers(), List.of(fixed), "Planning must not publish optional attacks");
        Assert.assertFalse(extra.getView().isAttacking());
        Assert.assertEquals(f.game().nextCardId(), sentinel + 1);
        Assert.assertTrue(plan.search().orElseThrow().best().orElseThrow().certifiedWin());
        Assert.assertTrue(CombatAttackPlanner.tryDeclare(f.ai(), f.ai(), f.combat(), new CombatSearchBudget(20000)));
        Assert.assertEquals(f.combat().getAttackers().size(), 2);
        Assert.assertTrue(f.combat().getAttackers().containsAll(List.of(fixed, extra)));
        Assert.assertEquals(f.combat().getDefenderByAttacker(fixed), f.opponent());
        Assert.assertTrue(CombatUtil.validateAttackers(f.combat()));
        Assert.assertTrue(f.combat().getAllBlockers().isEmpty());
    }

    @Test
    public void fixedAttackIsNotWithdrawnEvenWhenItsTradeWouldBeUnfavorable() {
        final Fixture f = fixture();
        final Card fixed = creature(f.ai(), 4, 1, List.of());
        creature(f.opponent(), 1, 1, List.of());
        f.combat().addAttacker(fixed, f.opponent());
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(20000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertEquals(plan.search().orElseThrow().best().orElseThrow().attackers(), List.of(fixed.getId()));
        Assert.assertTrue(CombatAttackPlanner.tryDeclare(f.ai(), f.ai(), f.combat(), new CombatSearchBudget(20000)));
        Assert.assertEquals(f.combat().getAttackers(), List.of(fixed));
        Assert.assertEquals(f.combat().getDefenderByAttacker(fixed), f.opponent());
    }

    @Test
    public void currentAttackReadinessExcludesTappedSickAndDefenderButAllowsHaste() {
        final Fixture f = fixture();
        final Card tapped = creature(f.ai(), 2, 2, List.of());
        tapped.setTapped(true);
        final Card sick = creature(f.ai(), 2, 2, List.of());
        sick.setSickness(true);
        final Card wall = creature(f.ai(), 0, 4, List.of("K:Defender"));
        final Card haste = creature(f.ai(), 2, 2, List.of("K:Haste"));
        haste.setSickness(true);
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(20000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertEquals(plan.snapshot().orElseThrow().attackersToDefenders().keySet(), java.util.Set.of(haste.getId()));
        Assert.assertFalse(tapped.getView().isAttacking() || sick.getView().isAttacking() || wall.getView().isAttacking());
    }

    @Test
    public void completedGreedyDeclarationIsAppliedWithoutClaimingAllSubsetsWereSearched() {
        final Fixture f = fixture();
        final Card first = creature(f.ai(), 2, 2, List.of());
        final Card second = creature(f.ai(), 2, 2, List.of());
        final Card third = creature(f.ai(), 2, 2, List.of());
        creature(f.opponent(), 1, 3, List.of());
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(20000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertTrue(plan.search().orElseThrow().candidateSearchComplete());
        Assert.assertFalse(plan.search().orElseThrow().searchExhaustive());
        Assert.assertTrue(CombatAttackPlanner.tryDeclare(f.ai(), f.ai(), f.combat(), new CombatSearchBudget(20000)));
        Assert.assertEquals(java.util.Set.copyOf(f.combat().getAttackers()), java.util.Set.of(first, second, third));
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
        final PaperCard library = new PaperCard(CardRules.fromScript(List.of("Name:Attack Library Fixture", "Types:Land")),
                CardEdition.UNKNOWN_CODE, CardRarity.Special);
        for (final Player player : game.getPlayers()) {
            for (int index = 0; index < 4; index++) { player.getZone(ZoneType.Library).add(Card.fromPaperCard(library, player)); }
        }
        return new Fixture(game, ai, opponent, combat);
    }

    private static Card creature(final Player owner, final int power, final int toughness, final List<String> extra) {
        final List<String> script = new ArrayList<>(List.of("Name:Attack Planner Fixture", "ManaCost:2",
                "Types:Creature Human", "PT:" + power + "/" + toughness));
        script.addAll(extra);
        final Card card = Card.fromPaperCard(new PaperCard(CardRules.fromScript(script), CardEdition.UNKNOWN_CODE, CardRarity.Special), owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());
        owner.getZone(ZoneType.Battlefield).add(card);
        card.setSickness(false);
        return card;
    }
}
