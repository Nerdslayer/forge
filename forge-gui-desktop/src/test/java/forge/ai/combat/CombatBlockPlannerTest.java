package forge.ai.combat;

import java.util.ArrayList;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
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

public class CombatBlockPlannerTest extends AITest {
    private record Fixture(Game game, Player attacker, Player defender, Combat combat) { }

    @Test
    public void combatRolloutIsEnabledOnlyInMastermind() {
        final Fixture f = fixture();
        Assert.assertTrue(AiProfileUtil.getBoolProperty(f.defender(), AiProps.ENABLE_COMBAT_BLOCK_PLANNING));
        Assert.assertTrue(AiProfileUtil.getBoolProperty(f.defender(), AiProps.ENABLE_COMBAT_ATTACK_PLANNING));
        Assert.assertEquals(AiProfileUtil.getIntProperty(f.defender(), AiProps.COMBAT_PLANNING_MAX_NODES), 50000);
        Assert.assertEquals(AiProfileUtil.getIntProperty(f.defender(), AiProps.COMBAT_PLANNING_TIMEOUT_MS), 500);
        ((LobbyPlayerAi) f.defender().getLobbyPlayer()).setAiProfile("Default");
        Assert.assertFalse(AiProfileUtil.getBoolProperty(f.defender(), AiProps.ENABLE_COMBAT_BLOCK_PLANNING));
        Assert.assertFalse(AiProfileUtil.getBoolProperty(f.defender(), AiProps.ENABLE_COMBAT_ATTACK_PLANNING));
        Assert.assertEquals(AiProfileUtil.getIntProperty(f.defender(), AiProps.COMBAT_PLANNING_MAX_NODES), 5000);
        Assert.assertEquals(AiProfileUtil.getIntProperty(f.defender(), AiProps.COMBAT_PLANNING_TIMEOUT_MS), 100);
    }

    @Test
    public void menaceAndFixedMinimumsRetainPairEligibilityAndRequireAWholeLegalGang() {
        for (final int minimum : List.of(2, 3, 6)) {
            final Fixture f = fixture();
            final List<String> ability = minimum == 2 ? List.of("K:Menace")
                    : List.of("S:Mode$ MinMaxBlocker | ValidCard$ Creature.Self | Min$ " + minimum);
            final Card attacker = creature(f.attacker(), 1, 1, ability);
            final List<Card> blockers = new ArrayList<>();
            for (int count = 0; count < minimum; count++) { blockers.add(creature(f.defender(), 1, 1, List.of())); }
            f.defender().setLife(1, null);
            f.combat().addAttacker(attacker, f.defender());
            final var snapshot = PublicCombatSnapshot.capture(f.defender(), f.combat());
            Assert.assertTrue(snapshot.unsupportedReasons().isEmpty(), snapshot.unsupportedReasons().toString());
            Assert.assertEquals(snapshot.creatures().get(attacker.getId()).minimumBlockers(), minimum);
            Assert.assertEquals(snapshot.legalBlockers().get(attacker.getId()).size(), minimum);
            final var solo = CombatOutcomePredictor.predict(snapshot,
                    new CombatAssignment(snapshot.attackersToDefenders(), java.util.Map.of(attacker.getId(), List.of(blockers.get(0).getId()))));
            Assert.assertTrue(solo.supported(), "Illegal solo block is understood, not an unsupported mechanic");
            Assert.assertFalse(solo.available());
            Assert.assertTrue(CombatBlockPlanner.tryDeclare(f.defender(), f.defender(), f.combat(), new CombatSearchBudget(20000)));
            Assert.assertEquals(f.combat().getBlockers(attacker).size(), minimum);
            Assert.assertNull(CombatUtil.validateBlocks(f.combat(), f.defender()));
        }
    }

    @Test
    public void oneBlockerCannotBlockMenaceAndMaximumOneDoesNotPermitAGang() {
        final Fixture menace = fixture();
        final Card attacker = creature(menace.attacker(), 2, 2, List.of("K:Menace"));
        final Card lone = creature(menace.defender(), 3, 3, List.of());
        menace.combat().addAttacker(attacker, menace.defender());
        final var plan = CombatBlockPlanner.plan(menace.defender(), menace.combat(), new CombatSearchBudget(20000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertTrue(plan.snapshot().orElseThrow().legalBlockers().get(attacker.getId()).contains(lone.getId()));
        Assert.assertTrue(plan.search().orElseThrow().best().orElseThrow().assignment().blockersByAttacker().isEmpty());
        final Fixture maximum = fixture();
        final Card limited = creature(maximum.attacker(), 4, 4,
                List.of("S:Mode$ MinMaxBlocker | ValidCard$ Card.Self | Max$ 1"));
        creature(maximum.defender(), 2, 2, List.of());
        creature(maximum.defender(), 2, 2, List.of());
        maximum.defender().setLife(4, null);
        maximum.combat().addAttacker(limited, maximum.defender());
        Assert.assertTrue(CombatBlockPlanner.tryDeclare(maximum.defender(), maximum.defender(), maximum.combat(), new CombatSearchBudget(20000)));
        Assert.assertEquals(maximum.combat().getBlockers(limited).size(), 1);
        Assert.assertNull(CombatUtil.validateBlocks(maximum.combat(), maximum.defender()));
    }

    @Test
    public void scheduledStateChangesAndPossibleDeckingAreNotSilentlyCarriedIntoForecasts() {
        for (final String effect : List.of("DB$ GainLife | Defined$ You | LifeAmount$ 3",
                "DB$ Draw | Defined$ You | NumCards$ 1000")) {
            final Fixture f = fixture();
            final Card source = creature(f.defender(), 2, 2, List.of(
                    "T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | Execute$ Outcome | TriggerZones$ Battlefield",
                    "SVar:Outcome:" + effect));
            final Card attacker = creature(f.attacker(), 2, 2, List.of());
            f.combat().addAttacker(attacker, f.defender());
            final PublicCombatSnapshot snapshot = PublicCombatSnapshot.capture(f.defender(), f.combat());
            Assert.assertTrue(snapshot.unsupportedReasons().isEmpty(), snapshot.unsupportedReasons().toString());
            final var future = PublicCombatReadiness.capture(f.defender(), snapshot);
            if (effect.contains("Draw")) {
                Assert.assertFalse(future.unsupportedReasons().isEmpty(), "Known possible decking remains a hard boundary");
            } else {
                Assert.assertTrue(future.unsupportedReasons().isEmpty());
                Assert.assertFalse(future.ignoredEffects().isEmpty());
            }
            Assert.assertTrue(source.isInPlay());
        }
    }

    @Test
    public void nextTurnReadinessUsesPublicRestrictionsWithoutChangingStateOrAllocatingIds() {
        final Fixture f = fixture();
        final Card flyer = creature(f.defender(), 2, 2, List.of("K:Flying"));
        flyer.setTapped(true);
        flyer.setSickness(true);
        final Card wall = creature(f.defender(), 0, 4, List.of("K:Defender"));
        final Card ground = creature(f.attacker(), 2, 2, List.of());
        final Card reach = creature(f.attacker(), 2, 2, List.of("K:Reach"));
        f.combat().addAttacker(ground, f.defender());
        final PublicCombatSnapshot snapshot = PublicCombatSnapshot.capture(f.defender(), f.combat());
        final int sentinel = f.game().nextCardId();
        final PublicCombatReadiness ready = PublicCombatReadiness.capture(f.defender(), snapshot);
        Assert.assertTrue(ready.unsupportedReasons().isEmpty(), ready.unsupportedReasons().toString());
        Assert.assertEquals(ready.nextActivePlayerId(), f.defender().getId());
        Assert.assertTrue(ready.canAttackNextTurn().contains(flyer.getId()), "Sickness and tap clear before the next ordinary attack");
        Assert.assertFalse(ready.canAttackNextTurn().contains(wall.getId()));
        Assert.assertFalse(ready.blockPairsNextTurn().get(flyer.getId()).contains(ground.getId()));
        Assert.assertTrue(ready.blockPairsNextTurn().get(flyer.getId()).contains(reach.getId()));
        Assert.assertEquals(f.game().nextCardId(), sentinel + 1);
        Assert.assertTrue(flyer.isTapped() && flyer.isSick());
        Assert.assertTrue(f.combat().getAllBlockers().isEmpty());
    }

    @Test
    public void readOnlyPlanningThenValidatedDeclarationHandlesOverloadedAttack() {
        final Fixture f = fixture();
        final Card first = creature(f.attacker(), 2, 2, List.of());
        final Card second = creature(f.attacker(), 2, 2, List.of());
        final Card blocker = creature(f.defender(), 1, 3, List.of());
        f.combat().addAttacker(first, f.defender());
        f.combat().addAttacker(second, f.defender());
        final boolean attackingView = first.getView().isAttacking();
        final boolean blockingView = blocker.getView().isBlocking();
        final int sentinel = f.game().nextCardId();
        final CombatBlockPlanner.Plan plan = CombatBlockPlanner.plan(f.defender(), f.combat(), new CombatSearchBudget(10000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertTrue(f.combat().getAllBlockers().isEmpty());
        Assert.assertEquals(first.getView().isAttacking(), attackingView);
        Assert.assertEquals(blocker.getView().isBlocking(), blockingView);
        Assert.assertEquals(f.game().nextCardId(), sentinel + 1);
        Assert.assertTrue(CombatBlockPlanner.tryDeclare(f.defender(), f.defender(), f.combat(), new CombatSearchBudget(10000)));
        Assert.assertEquals(f.combat().getAllBlockers().size(), 1);
        Assert.assertNull(CombatUtil.validateBlocks(f.combat(), f.defender()));
        final PublicCombatSnapshot snapshot = plan.snapshot().orElseThrow();
        final CombatProjection expected = plan.search().orElseThrow().best().orElseThrow().projection();
        Assert.assertEquals(expected.playerLifeAfter().get(f.defender().getId()).intValue(), 18);
        Assert.assertEquals(snapshot.creatures().get(blocker.getId()).markedDamage(), 0);
    }

    @Test
    public void friendlyScheduledEngineIsProtectedUntilTakingDamageWouldBeLethal() {
        for (final int life : List.of(20, 2)) {
            final Fixture f = fixture();
            f.defender().setLife(life, null);
            final Card attacker = creature(f.attacker(), 2, 2, List.of());
            final Card engine = creature(f.defender(), 2, 2, List.of(
                    "T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | Execute$ Draw | TriggerZones$ Battlefield",
                    "SVar:Draw:DB$ Draw | Defined$ You | NumCards$ 1"));
            f.combat().addAttacker(attacker, f.defender());
            Assert.assertTrue(CombatBlockPlanner.tryDeclare(f.defender(), f.defender(), f.combat(), new CombatSearchBudget(10000)));
            Assert.assertEquals(f.combat().getBlockers(attacker).contains(engine), life == 2);
        }
    }

    @Test
    public void unknownActivationsDoNotVetoBlocksAndLegalPreassignedBlocksArePreserved() {
        final Fixture unknown = fixture();
        final Card attacker = creature(unknown.attacker(), 3, 3,
                List.of("A:AB$ Pump | Cost$ U | NumAtt$ 1 | NumDef$ -1 | Defined$ Self"));
        final Card blocker = creature(unknown.defender(), 1, 3, List.of());
        unknown.combat().addAttacker(attacker, unknown.defender());
        final int sentinel = unknown.game().nextCardId();
        final var approximate = PublicCombatSnapshot.capture(unknown.defender(), unknown.combat());
        Assert.assertTrue(approximate.unsupportedReasons().isEmpty());
        Assert.assertFalse(approximate.ignoredEffects().isEmpty());
        Assert.assertTrue(CombatBlockPlanner.tryDeclare(unknown.defender(), unknown.defender(), unknown.combat(), new CombatSearchBudget(10000)));
        Assert.assertTrue(unknown.combat().getAllBlockers().isEmpty());
        Assert.assertEquals(unknown.game().nextCardId(), sentinel + 1);
        Assert.assertFalse(blocker.isTapped());
        final Fixture fixed = fixture();
        final Card other = creature(fixed.attacker(), 2, 2, List.of());
        final Card assigned = creature(fixed.defender(), 2, 2, List.of());
        fixed.combat().addAttacker(other, fixed.defender());
        fixed.combat().addBlocker(other, assigned);
        Assert.assertTrue(CombatBlockPlanner.tryDeclare(fixed.defender(), fixed.defender(), fixed.combat(), new CombatSearchBudget(10000)));
        Assert.assertEquals(fixed.combat().getBlockers(other), List.of(assigned));
    }

    @Test
    public void structuralDamageMechanicsStillFallBackWithoutChangingBlocks() {
        for (final String keyword : List.of("Protection:Red", "Infect", "Wither", "Banding", "Absorb:1")) {
            final Fixture f = fixture();
            final Card attacker = creature(f.attacker(), 3, 3, List.of("K:" + keyword));
            creature(f.defender(), 2, 2, List.of());
            f.combat().addAttacker(attacker, f.defender());
            Assert.assertFalse(CombatBlockPlanner.tryDeclare(f.defender(), f.defender(), f.combat(), new CombatSearchBudget(10000)), keyword);
            Assert.assertTrue(f.combat().getAllBlockers().isEmpty());
        }
    }

    @Test
    public void departedPrintedAbilitiesDoNotVetoPublicCombat() {
        final Fixture f = fixture();
        final Card departed = creature(f.attacker(), 2, 2, List.of("K:Infect",
                "A:AB$ Pump | Cost$ U | NumAtt$ 1 | Defined$ Self"));
        f.attacker().getZone(ZoneType.Battlefield).remove(departed);
        f.attacker().getZone(ZoneType.Graveyard).add(departed);
        final Card attacker = creature(f.attacker(), 2, 2, List.of());
        creature(f.defender(), 2, 2, List.of());
        f.combat().addAttacker(attacker, f.defender());
        final var snapshot = PublicCombatSnapshot.capture(f.defender(), f.combat());
        Assert.assertTrue(snapshot.unsupportedReasons().isEmpty(), snapshot.unsupportedReasons().toString());
        Assert.assertTrue(snapshot.ignoredEffects().stream().noneMatch(reason -> reason.startsWith("Unprojected public activation")
                || reason.startsWith("Unprojected public keyword")));
        Assert.assertTrue(CombatBlockPlanner.tryDeclare(f.defender(), f.defender(), f.combat(), new CombatSearchBudget(10000)));
    }

    @Test
    public void fixedBlockersCannotBeReassignedAndPlanningAddsOnlyTheMissingBlocks() {
        final Fixture f = fixture();
        final Card first = creature(f.attacker(), 2, 2, List.of());
        final Card second = creature(f.attacker(), 2, 2, List.of());
        final Card fixed = creature(f.defender(), 1, 3, List.of());
        final Card free = creature(f.defender(), 1, 3, List.of());
        f.defender().setLife(2, null);
        f.combat().addAttacker(first, f.defender());
        f.combat().addAttacker(second, f.defender());
        f.combat().addBlocker(first, fixed);
        final var plan = CombatBlockPlanner.plan(f.defender(), f.combat(), new CombatSearchBudget(20000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertEquals(f.combat().getAllBlockers().size(), 1, "Planning remains read-only");
        Assert.assertEquals(plan.search().orElseThrow().best().orElseThrow().assignment().blockersByAttacker()
                .get(first.getId()), List.of(fixed.getId()));
        Assert.assertTrue(CombatBlockPlanner.tryDeclare(f.defender(), f.defender(), f.combat(), new CombatSearchBudget(20000)));
        Assert.assertEquals(f.combat().getBlockers(first), List.of(fixed));
        Assert.assertEquals(f.combat().getBlockers(second), List.of(free));
        Assert.assertNull(CombatUtil.validateBlocks(f.combat(), f.defender()));
    }

    @Test
    public void fixedGangCanBeExtendedAndIncompleteFixedMenaceIsCompletedWithoutMovingItsBlocker() {
        final Fixture f = fixture();
        final Card attacker = creature(f.attacker(), 3, 3, List.of());
        final Card fixed = creature(f.defender(), 1, 4, List.of());
        final Card free = creature(f.defender(), 2, 4, List.of());
        f.combat().addAttacker(attacker, f.defender());
        f.combat().addBlocker(attacker, fixed);
        Assert.assertTrue(CombatBlockPlanner.tryDeclare(f.defender(), f.defender(), f.combat(), new CombatSearchBudget(20000)));
        Assert.assertTrue(f.combat().getBlockers(attacker).containsAll(List.of(fixed, free)));
        Assert.assertEquals(f.combat().getBlockers(attacker).size(), 2);
        Assert.assertNull(CombatUtil.validateBlocks(f.combat(), f.defender()));

        final Fixture incomplete = fixture();
        final Card menace = creature(incomplete.attacker(), 2, 2, List.of("K:Menace"));
        final Card assigned = creature(incomplete.defender(), 2, 2, List.of());
        final Card additional = creature(incomplete.defender(), 2, 2, List.of());
        incomplete.combat().addAttacker(menace, incomplete.defender());
        incomplete.combat().addBlocker(menace, assigned);
        Assert.assertTrue(CombatBlockPlanner.tryDeclare(incomplete.defender(), incomplete.defender(), incomplete.combat(),
                new CombatSearchBudget(20000)));
        Assert.assertTrue(incomplete.combat().getBlockers(menace).containsAll(List.of(assigned, additional)));
        Assert.assertEquals(incomplete.combat().getBlockers(menace).size(), 2);
        Assert.assertNull(CombatUtil.validateBlocks(incomplete.combat(), incomplete.defender()));
    }

    @Test
    public void competingFixedGroupsAreCompletedJointlyAndImpossibleGroupsAreLeftUntouched() {
        for (final int freeCount : List.of(1, 2)) {
            final Fixture f = fixture();
            final Card first = creature(f.attacker(), 2, 2, List.of("K:Menace"));
            final Card second = creature(f.attacker(), 2, 2, List.of("K:Menace"));
            final Card fixedFirst = creature(f.defender(), 1, 3, List.of());
            final Card fixedSecond = creature(f.defender(), 1, 3, List.of());
            for (int count = 0; count < freeCount; count++) { creature(f.defender(), 1, 3, List.of()); }
            f.combat().addAttacker(first, f.defender());
            f.combat().addAttacker(second, f.defender());
            f.combat().addBlocker(first, fixedFirst);
            f.combat().addBlocker(second, fixedSecond);
            final var plan = CombatBlockPlanner.plan(f.defender(), f.combat(), new CombatSearchBudget(20000));
            Assert.assertEquals(plan.applicable(), freeCount == 2, plan.reasons().toString());
            Assert.assertEquals(f.combat().getAllBlockers().size(), 2, "Read-only completion planning");
            Assert.assertEquals(CombatBlockPlanner.tryDeclare(f.defender(), f.defender(), f.combat(), new CombatSearchBudget(20000)),
                    freeCount == 2);
            Assert.assertTrue(f.combat().getBlockers(first).contains(fixedFirst));
            Assert.assertTrue(f.combat().getBlockers(second).contains(fixedSecond));
            Assert.assertFalse(f.combat().getBlockers(second).contains(fixedFirst));
            Assert.assertFalse(f.combat().getBlockers(first).contains(fixedSecond));
            Assert.assertEquals(f.combat().getAllBlockers().size(), freeCount == 2 ? 4 : 2);
            if (freeCount == 2) { Assert.assertNull(CombatUtil.validateBlocks(f.combat(), f.defender())); }
        }
    }

    @Test
    public void detachedValidationCannotPublishAssignmentsIntoLiveCombat() {
        final Fixture f = fixture();
        final Card attacker = creature(f.attacker(), 2, 2, List.of());
        final Card blocker = creature(f.defender(), 2, 2, List.of());
        f.combat().addAttacker(attacker, f.defender());
        final Combat detached = new Combat(f.attacker());
        detached.addAttackerForValidation(attacker, f.defender());
        detached.addBlockerForValidation(attacker, blocker);
        Assert.assertNull(CombatUtil.validateBlocks(detached, f.defender()));
        Assert.assertTrue(f.combat().getAllBlockers().isEmpty());
        Assert.assertFalse(blocker.getView().isBlocking());
        Assert.expectThrows(IllegalStateException.class, () -> f.combat().addAttackerForValidation(attacker, f.defender()));
        Assert.expectThrows(IllegalStateException.class, () -> f.combat().addBlockerForValidation(attacker, blocker));
    }

    @Test
    public void emptyBudgetAndForeignOrDetachedExecutionDoNotApplyAnAssignment() {
        final Fixture f = fixture();
        final Card attacker = creature(f.attacker(), 2, 2, List.of());
        creature(f.defender(), 2, 2, List.of());
        f.combat().addAttacker(attacker, f.defender());
        Assert.assertFalse(CombatBlockPlanner.tryDeclare(f.defender(), f.defender(), f.combat(), new CombatSearchBudget(0)));
        Assert.assertTrue(f.combat().getAllBlockers().isEmpty());
        final var interrupted = CombatBlockPlanner.plan(f.defender(), f.combat(), new CombatSearchBudget(1));
        Assert.assertFalse(interrupted.applicable());
        Assert.assertTrue(interrupted.search().isEmpty(), "Interrupted preparation must not enter search with missing card values");
        Assert.assertTrue(interrupted.snapshot().isEmpty(), "Snapshot preparation now shares the same allowance");
        Assert.assertTrue(interrupted.reasons().stream().anyMatch(reason -> reason.contains("snapshot preparation")));
        Assert.assertTrue(f.combat().getAllBlockers().isEmpty());
        Assert.assertFalse(CombatBlockPlanner.tryDeclare(f.attacker(), f.defender(), f.combat(), new CombatSearchBudget(100)));
        Assert.assertFalse(CombatBlockPlanner.tryDeclare(f.defender(), f.defender(), new Combat(f.attacker()), new CombatSearchBudget(100)));
    }

    @Test
    public void unavoidableLethalStillDeclaresDamageMinimizingBlocksAndSurvivalAlwaysWins() {
        for (final int life : List.of(1, 4)) {
            final Fixture f = fixture();
            final Card large = creature(f.attacker(), 5, 5, List.of());
            final Card small = creature(f.attacker(), 3, 3, List.of());
            final Card blocker = creature(f.defender(), 1, 1, List.of());
            f.defender().setLife(life, null);
            f.combat().addAttacker(large, f.defender());
            f.combat().addAttacker(small, f.defender());
            final var plan = CombatBlockPlanner.plan(f.defender(), f.combat(), new CombatSearchBudget(20000));
            Assert.assertTrue(plan.applicable(), plan.reasons().toString());
            final var selected = plan.search().orElseThrow().best().orElseThrow();
            Assert.assertEquals(selected.projection().playerLifeAfter().get(f.defender().getId()).intValue(), life - 3);
            Assert.assertEquals(selected.projection().terminal(), life == 1 ? CombatProjection.Terminal.LOSS : CombatProjection.Terminal.NONE);
            Assert.assertTrue(CombatBlockPlanner.tryDeclare(f.defender(), f.defender(), f.combat(), new CombatSearchBudget(20000)));
            Assert.assertEquals(f.combat().getBlockers(large), List.of(blocker));
            Assert.assertTrue(f.combat().getBlockers(small).isEmpty());
            Assert.assertNull(CombatUtil.validateBlocks(f.combat(), f.defender()));
        }
    }

    private Fixture fixture() {
        final Game game = initAndCreateGame();
        final Player attacker = game.getPlayers().get(0);
        final Player defender = game.getPlayers().get(1);
        attacker.setTeam(1);
        defender.setTeam(0);
        ((LobbyPlayerAi) defender.getLobbyPlayer()).setAiProfile("Mastermind");
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, attacker);
        final Combat combat = new Combat(attacker);
        game.getPhaseHandler().setCombat(combat);
        final PaperCard libraryCard = new PaperCard(CardRules.fromScript(List.of("Name:Library Fixture", "Types:Land")),
                CardEdition.UNKNOWN_CODE, CardRarity.Special);
        for (final Player player : game.getPlayers()) {
            for (int index = 0; index < 4; index++) {
                player.getZone(ZoneType.Library).add(Card.fromPaperCard(libraryCard, player));
            }
        }
        return new Fixture(game, attacker, defender, combat);
    }

    private static Card creature(final Player owner, final int power, final int toughness, final List<String> extra) {
        final List<String> script = new ArrayList<>(List.of("Name:Block Planner Fixture", "ManaCost:2",
                "Types:Creature Human", "PT:" + power + "/" + toughness));
        script.addAll(extra);
        final Card card = Card.fromPaperCard(new PaperCard(CardRules.fromScript(script), CardEdition.UNKNOWN_CODE, CardRarity.Special), owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());
        owner.getZone(ZoneType.Battlefield).add(card);
        card.setSickness(false);
        return card;
    }
}
