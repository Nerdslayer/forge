package forge.ai.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.PlayerResourceValueEvaluator;
import forge.ai.effect.CombatValuationEvaluator;
import forge.ai.effect.CombatStaticProjectionPreparation;
import forge.ai.effect.ValuationContext;
import forge.ai.effect.ValuationDecision;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

/** Reusable combat-rule fixtures: declarations, damage timing, signed life utility and isolation. */
public class CombatOutcomePredictorTest extends AITest {
    private record Fixture(Game game, Player observer, Player attacker, Combat declaration) { }

    @DataProvider(name = "anthemRecipientScopes")
    public Object[][] anthemRecipientScopes() {
        return new Object[][] {{"Creature.YouCtrl+Other"}, {"Creature.Human+YouCtrl+Other"}};
    }

    @Test(dataProvider = "anthemRecipientScopes")
    public void firstStrikeAnthemLossChangesRegularDamageAndRebasesFutureWorlds(final String scope) {
        final Fixture f = fixture();
        final Card source = creature(f.attacker(), 1, 1, List.of(
                "S:Mode$ Continuous | Affected$ " + scope + " | AddPower$ 1 | AddToughness$ 1"));
        final Card striker = creature(f.attacker(), 2, 2, List.of("K:Double strike"));
        final Card blocker = creature(f.observer(), 2, 2, List.of("K:First strike"));
        f.game().getAction().checkStaticAbilities();
        f.declaration().addAttacker(source, f.observer());
        f.declaration().addAttacker(striker, f.observer());
        final var snapshot = PublicCombatSnapshot.capture(f.observer(), f.declaration());
        final var projection = CombatOutcomePredictor.predict(snapshot, new CombatAssignment(snapshot.attackersToDefenders(),
                Map.of(source.getId(), List.of(blocker.getId()))));
        Assert.assertTrue(projection.supported() && projection.available(), projection.reasons().toString());
        Assert.assertEquals(projection.lostCreatures(), Set.of(source.getId()));
        Assert.assertEquals(projection.playerLifeAfter().get(f.observer().getId()).intValue(), 15);
        final var future = snapshot.staticWorlds().surviving(projection.lostCreatures());
        Assert.assertTrue(future.providers().isEmpty());
        Assert.assertEquals(future.afterLosses(Set.of()).get(striker.getId()).combatDamage(), 2);
        Assert.assertEquals(future.afterLosses(Set.of()).get(striker.getId()).bodyDelta(), 0);
        Assert.assertEquals(striker.getNetPower(), 3, "Forecast does not mutate the live anthem");
        Assert.assertTrue(CombatStaticProjectionPreparation.hasOnlyPersistentBoosts(striker));
        striker.addPTBoost(1, 1, f.game().getNextTimestamp(), 0);
        Assert.assertFalse(CombatStaticProjectionPreparation.hasOnlyPersistentBoosts(striker), "Temporary pump cannot survive cleanup");
        striker.removePTBoost(f.game().getTimestamp(), 0);
        resolveWithEngineAndCompare(f, Map.of(source, blocker), projection);
    }

    @Test
    public void losingAnthemCausesAnAdditionalZeroToughnessDeathEvenWithIndestructible() {
        final Fixture f = fixture();
        final Card source = creature(f.attacker(), 1, 1, List.of(
                "S:Mode$ Continuous | Affected$ Creature.YouCtrl+Other | AddToughness$ 1"));
        final Card dependent = creature(f.attacker(), 0, 0, List.of("K:Indestructible"));
        final Card blocker = creature(f.observer(), 2, 2, List.of("K:First strike"));
        f.game().getAction().checkStaticAbilities();
        f.declaration().addAttacker(source, f.observer());
        final var snapshot = PublicCombatSnapshot.capture(f.observer(), f.declaration());
        final var projection = CombatOutcomePredictor.predict(snapshot, new CombatAssignment(snapshot.attackersToDefenders(),
                Map.of(source.getId(), List.of(blocker.getId()))));
        Assert.assertTrue(projection.supported() && projection.available(), projection.reasons().toString());
        Assert.assertEquals(projection.lostCreatures(), Set.of(source.getId(), dependent.getId()));
        Assert.assertTrue(dependent.isInPlay(), "Projection does not perform live state-based actions");
        resolveWithEngineAndCompare(f, Map.of(source, blocker), projection);
        Assert.assertFalse(dependent.isInPlay());
    }

    @Test
    public void negativeAnthemLossCannotRescueCreaturesDyingInTheSameStateBasedBatch() {
        final Fixture f = fixture();
        final Card source = creature(f.attacker(), 1, 1, List.of(
                "S:Mode$ Continuous | Affected$ Creature.YouCtrl+Other | AddToughness$ -1"));
        final Card recipient = creature(f.attacker(), 2, 2, List.of());
        final Card first = creature(f.observer(), 1, 2, List.of("K:First strike"));
        final Card second = creature(f.observer(), 1, 2, List.of("K:First strike"));
        f.game().getAction().checkStaticAbilities();
        f.declaration().addAttacker(source, f.observer());
        f.declaration().addAttacker(recipient, f.observer());
        final var snapshot = PublicCombatSnapshot.capture(f.observer(), f.declaration());
        final var projection = CombatOutcomePredictor.predict(snapshot, new CombatAssignment(snapshot.attackersToDefenders(),
                Map.of(source.getId(), List.of(first.getId()), recipient.getId(), List.of(second.getId()))));
        Assert.assertTrue(projection.supported() && projection.available(), projection.reasons().toString());
        Assert.assertEquals(projection.lostCreatures(), Set.of(source.getId(), recipient.getId()));
        resolveWithEngineAndCompare(f, Map.of(source, first, recipient, second), projection);
    }

    @Test
    public void overloadedBlocksPreserveUnblockedDamageAndSnapshotsAreFrozen() {
        final Fixture f = fixture();
        final Card first = creature(f.attacker(), 2, 2, List.of());
        final Card second = creature(f.attacker(), 2, 2, List.of());
        final Card blocker = creature(f.observer(), 1, 3, List.of());
        f.declaration().addAttacker(first, f.observer());
        f.declaration().addAttacker(second, f.observer());
        final int sentinel = f.game().nextCardId();
        final PublicCombatSnapshot snapshot = PublicCombatSnapshot.capture(f.observer(), f.declaration());
        Assert.assertTrue(snapshot.unsupportedReasons().isEmpty(), snapshot.unsupportedReasons().toString());
        final CombatAssignment assignment = new CombatAssignment(snapshot.attackersToDefenders(),
                Map.of(first.getId(), List.of(blocker.getId())));
        final CombatProjection blocked = CombatOutcomePredictor.predict(snapshot, assignment);
        final CombatProjection unblocked = CombatOutcomePredictor.predict(snapshot,
                new CombatAssignment(snapshot.attackersToDefenders(), Map.of()));
        Assert.assertTrue(blocked.supported() && blocked.available());
        Assert.assertTrue(blocked.lostCreatures().isEmpty());
        Assert.assertEquals(blocked.playerLifeAfter().get(f.observer().getId()).intValue(), 18);
        Assert.assertEquals(unblocked.playerLifeAfter().get(f.observer().getId()).intValue(), 16);
        Assert.assertEquals(blocked.survivors().get(first.getId()).markedDamage(), 1);
        Assert.assertEquals(blocked.survivors().get(blocker.getId()).markedDamage(), 2);
        Assert.assertTrue(blocked.survivors().get(first.getId()).tapped());
        Assert.assertEquals(f.game().nextCardId(), sentinel + 1);
        Assert.assertEquals(first.getDamage(), 0);
        Assert.assertEquals(blocker.getDamage(), 0);
        Assert.assertFalse(first.isTapped());
        Assert.assertTrue(f.declaration().getBlockers(first).isEmpty());
        blocker.setTapped(true);
        f.observer().setLife(1, null);
        Assert.assertEquals(CombatOutcomePredictor.predict(snapshot, assignment), blocked);
        Assert.expectThrows(UnsupportedOperationException.class, () -> snapshot.legalBlockers().get(first.getId()).clear());
    }

    @DataProvider(name = "strikeMechanics")
    public Object[][] strikeMechanics() {
        return new Object[][] {
            {List.of(), true, 20},
            {List.of("K:First strike"), false, 20},
            {List.of("K:Double strike"), false, 20},
            {List.of("K:Double strike", "K:Trample"), false, 18}
        };
    }

    @Test(dataProvider = "strikeMechanics")
    public void simultaneousTradesAndStrikeSurvival(final List<String> keywords,
            final boolean attackerDies, final int lifeAfter) {
        final Fixture f = fixture();
        final Card attacker = creature(f.attacker(), 2, 2, keywords);
        final Card blocker = creature(f.observer(), 2, 2, List.of());
        f.declaration().addAttacker(attacker, f.observer());
        final PublicCombatSnapshot snapshot = PublicCombatSnapshot.capture(f.observer(), f.declaration());
        final CombatProjection projection = CombatOutcomePredictor.predict(snapshot,
                new CombatAssignment(snapshot.attackersToDefenders(), Map.of(attacker.getId(), List.of(blocker.getId()))));
        Assert.assertTrue(projection.supported() && projection.available(), projection.reasons().toString());
        Assert.assertTrue(projection.lostCreatures().contains(blocker.getId()));
        Assert.assertEquals(projection.lostCreatures().contains(attacker.getId()), attackerDies);
        Assert.assertEquals(projection.playerLifeAfter().get(f.observer().getId()).intValue(), lifeAfter);
        resolveWithEngineAndCompare(f, Map.of(attacker, blocker), projection);
    }

    @Test
    public void markedDamageDeathtouchAndTrampleUseLethalAssignment() {
        final Fixture f = fixture();
        final Card attacker = creature(f.attacker(), 4, 4, List.of("K:Trample", "K:Deathtouch"));
        final Card blocker = creature(f.observer(), 1, 6, List.of());
        blocker.setDamage(2);
        f.declaration().addAttacker(attacker, f.observer());
        final PublicCombatSnapshot snapshot = PublicCombatSnapshot.capture(f.observer(), f.declaration());
        final CombatProjection projection = CombatOutcomePredictor.predict(snapshot,
                new CombatAssignment(snapshot.attackersToDefenders(), Map.of(attacker.getId(), List.of(blocker.getId()))));
        Assert.assertTrue(projection.available(), projection.reasons().toString());
        Assert.assertEquals(projection.lostCreatures(), Set.of(blocker.getId()));
        Assert.assertEquals(projection.playerLifeAfter().get(f.observer().getId()).intValue(), 17);
        Assert.assertEquals(blocker.getDamage(), 2);
        resolveWithEngineAndCompare(f, Map.of(attacker, blocker), projection);
    }

    @Test
    public void indestructibleSurvivesMarkedLethalAndDeathtouchDamageWithoutTrample() {
        final Fixture f = fixture();
        final Card attacker = creature(f.attacker(), 4, 4, List.of("K:Deathtouch"));
        final Card blocker = creature(f.observer(), 1, 6, List.of("K:Indestructible"));
        blocker.setDamage(2);
        f.declaration().addAttacker(attacker, f.observer());
        final PublicCombatSnapshot snapshot = PublicCombatSnapshot.capture(f.observer(), f.declaration());
        final CombatProjection projection = CombatOutcomePredictor.predict(snapshot,
                new CombatAssignment(snapshot.attackersToDefenders(), Map.of(attacker.getId(), List.of(blocker.getId()))));
        Assert.assertTrue(projection.available(), projection.reasons().toString());
        Assert.assertTrue(projection.lostCreatures().isEmpty());
        Assert.assertEquals(projection.survivors().get(blocker.getId()).markedDamage(), 6);
        resolveWithEngineAndCompare(f, Map.of(attacker, blocker), projection);
    }

    @Test
    public void trampleIndestructibleAssignmentMismatchIsExplicitlyUnsupported() {
        final Fixture f = fixture();
        final Card attacker = creature(f.attacker(), 4, 4, List.of("K:Deathtouch", "K:Trample"));
        final Card blocker = creature(f.observer(), 1, 6, List.of("K:Indestructible"));
        f.declaration().addAttacker(attacker, f.observer());
        final PublicCombatSnapshot snapshot = PublicCombatSnapshot.capture(f.observer(), f.declaration());
        final CombatProjection projection = CombatOutcomePredictor.predict(snapshot,
                new CombatAssignment(snapshot.attackersToDefenders(), Map.of(attacker.getId(), List.of(blocker.getId()))));
        Assert.assertFalse(projection.supported());
        Assert.assertTrue(projection.reasons().get(0).contains("execution-consistent"));
    }

    @Test
    public void lifelinkAndAllPlayerHitsAreCombinedBeforeEliminationAndLifeScoring() {
        final Fixture f = fixture();
        f.observer().setLife(1, null);
        final Card traded = creature(f.attacker(), 2, 2, List.of());
        final Card unblocked = creature(f.attacker(), 2, 2, List.of());
        final Card blocker = creature(f.observer(), 2, 2, List.of("K:Lifelink"));
        f.declaration().addAttacker(traded, f.observer());
        f.declaration().addAttacker(unblocked, f.observer());
        final PublicCombatSnapshot snapshot = PublicCombatSnapshot.capture(f.observer(), f.declaration());
        final CombatProjection projection = CombatOutcomePredictor.predict(snapshot,
                new CombatAssignment(snapshot.attackersToDefenders(), Map.of(traded.getId(), List.of(blocker.getId()))));
        Assert.assertTrue(projection.available(), projection.reasons().toString());
        Assert.assertEquals(projection.playerLifeAfter().get(f.observer().getId()).intValue(), 1);
        Assert.assertEquals(projection.terminal(), CombatProjection.Terminal.NONE);
        Assert.assertEquals(projection.lostCreatures(), Set.of(traded.getId(), blocker.getId()));
        final PreparedCombatValuation values = CombatValuationEvaluator.prepare(
                ValuationContext.forCombat(f.observer(), ValuationDecision.BLOCK, 0, 0));
        final CombatTransitionValueEvaluator.Score score = CombatTransitionValueEvaluator.evaluate(snapshot, values, projection);
        Assert.assertEquals(score.lifeUtility(), 0);
        Assert.assertEquals(score.terminalValue(), 0);
        Assert.assertEquals(f.observer().getLife(), 1);
        resolveWithEngineAndCompare(f, Map.of(traded, blocker), projection);
    }

    @Test
    public void scoringComparesPreventedPlayerDamageAndTradeLossOnTheSameScale() {
        final Fixture f = fixture();
        f.observer().setLife(5, null);
        final Card attacker = creature(f.attacker(), 4, 4, List.of("K:Trample"));
        final Card blocker = creature(f.observer(), 2, 2, List.of());
        f.declaration().addAttacker(attacker, f.observer());
        final PublicCombatSnapshot snapshot = PublicCombatSnapshot.capture(f.observer(), f.declaration());
        final PreparedCombatValuation values = CombatValuationEvaluator.prepare(
                ValuationContext.forCombat(f.observer(), ValuationDecision.BLOCK, 0, 0));
        final CombatProjection block = CombatOutcomePredictor.predict(snapshot,
                new CombatAssignment(snapshot.attackersToDefenders(), Map.of(attacker.getId(), List.of(blocker.getId()))));
        final CombatProjection noBlock = CombatOutcomePredictor.predict(snapshot,
                new CombatAssignment(snapshot.attackersToDefenders(), Map.of()));
        final CombatTransitionValueEvaluator.Score blocked = CombatTransitionValueEvaluator.evaluate(snapshot, values, block);
        final CombatTransitionValueEvaluator.Score unblocked = CombatTransitionValueEvaluator.evaluate(snapshot, values, noBlock);
        Assert.assertEquals(blocked.lifeUtility(), PlayerResourceValueEvaluator.evaluateLifeChange(5, 3));
        Assert.assertEquals(unblocked.lifeUtility(), PlayerResourceValueEvaluator.evaluateLifeChange(5, 1));
        Assert.assertTrue(blocked.permanentLoss().body() < 0);
        Assert.assertTrue(blocked.total() > unblocked.total());
        Assert.assertEquals(block.batches().get(0).playerDamage().get(f.observer().getId()).intValue(), 2);
    }

    @Test
    public void lethalStopsLaterDamageAndObserverPerspectiveControlsTerminalValue() {
        final Fixture f = fixture();
        f.observer().setLife(2, null);
        final Card firstStrike = creature(f.attacker(), 2, 2, List.of("K:First strike"));
        final Card normal = creature(f.attacker(), 6, 6, List.of());
        f.declaration().addAttacker(firstStrike, f.observer());
        f.declaration().addAttacker(normal, f.observer());
        final PublicCombatSnapshot defensive = PublicCombatSnapshot.capture(f.observer(), f.declaration());
        final CombatAssignment assignment = new CombatAssignment(defensive.attackersToDefenders(), Map.of());
        final CombatProjection loss = CombatOutcomePredictor.predict(defensive, assignment);
        Assert.assertTrue(loss.available(), loss.reasons().toString());
        Assert.assertEquals(loss.terminal(), CombatProjection.Terminal.LOSS);
        Assert.assertEquals(loss.batches().size(), 1);
        Assert.assertEquals(loss.playerLifeAfter().get(f.observer().getId()).intValue(), 0);
        final PublicCombatSnapshot offensive = PublicCombatSnapshot.capture(f.attacker(), f.declaration());
        final CombatProjection win = CombatOutcomePredictor.predict(offensive, assignment);
        Assert.assertEquals(win.terminal(), CombatProjection.Terminal.WIN);
        final PreparedCombatValuation values = CombatValuationEvaluator.prepare(
                ValuationContext.forCombat(f.attacker(), ValuationDecision.ATTACK, 0, 0));
        Assert.assertEquals(CombatTransitionValueEvaluator.evaluate(offensive, values, win).terminalValue(), 10_000);
    }

    @Test
    public void illegalAndUnmodeledAssignmentsRemainDistinct() {
        final Fixture f = fixture();
        final Card first = creature(f.attacker(), 2, 2, List.of("K:Flying"));
        final Card second = creature(f.attacker(), 2, 2, List.of());
        final Card grounded = creature(f.observer(), 2, 2, List.of());
        final Card reach = creature(f.observer(), 2, 2, List.of("K:Reach"));
        f.declaration().addAttacker(first, f.observer());
        f.declaration().addAttacker(second, f.observer());
        final PublicCombatSnapshot snapshot = PublicCombatSnapshot.capture(f.observer(), f.declaration());
        Assert.assertFalse(snapshot.legalBlockers().get(first.getId()).contains(grounded.getId()));
        Assert.assertTrue(snapshot.legalBlockers().get(first.getId()).contains(reach.getId()));
        final CombatProjection reused = CombatOutcomePredictor.predict(snapshot,
                new CombatAssignment(snapshot.attackersToDefenders(), Map.of(
                        first.getId(), List.of(reach.getId()), second.getId(), List.of(reach.getId()))));
        Assert.assertTrue(reused.supported());
        Assert.assertFalse(reused.available());
        final CombatProjection gang = CombatOutcomePredictor.predict(snapshot,
                new CombatAssignment(snapshot.attackersToDefenders(), Map.of(second.getId(), List.of(grounded.getId(), reach.getId()))));
        Assert.assertFalse(gang.supported());
        Assert.assertFalse(gang.available());
    }

    @Test
    public void publicCombatActivationIsExplicitlyApproximatedAndFutureDrawAloneNeedsNoOmission() {
        final Fixture f = fixture();
        final Card attacker = creature(f.attacker(), 2, 3, List.of(
                "A:AB$ Pump | Cost$ U | NumAtt$ +1 | NumDef$ -1"));
        final Card blocker = creature(f.observer(), 1, 3, List.of());
        f.declaration().addAttacker(attacker, f.observer());
        final PublicCombatSnapshot unknown = PublicCombatSnapshot.capture(f.observer(), f.declaration());
        final CombatProjection result = CombatOutcomePredictor.predict(unknown,
                new CombatAssignment(unknown.attackersToDefenders(), Map.of(attacker.getId(), List.of(blocker.getId()))));
        Assert.assertTrue(result.supported());
        Assert.assertTrue(result.available());
        Assert.assertEquals(result.lostCreatures(), Set.of(), "Do not invent use of the unknown pump");
        Assert.assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("activation")));
        f.attacker().getZone(ZoneType.Battlefield).remove(attacker);
        final Card scheduled = creature(f.attacker(), 2, 2, List.of(
                "T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | Execute$ Benefit | TriggerZones$ Battlefield",
                "SVar:Benefit:DB$ Draw | Defined$ You | NumCards$ 1"));
        final Combat safeDeclaration = new Combat(f.attacker());
        safeDeclaration.addAttacker(scheduled, f.observer());
        final PublicCombatSnapshot safe = PublicCombatSnapshot.capture(f.observer(), safeDeclaration);
        Assert.assertTrue(safe.unsupportedReasons().isEmpty(), safe.unsupportedReasons().toString());
        Assert.assertTrue(CombatOutcomePredictor.predict(safe,
                new CombatAssignment(safe.attackersToDefenders(), Map.of())).available());
    }

    @Test
    public void publicCaptureIgnoresHiddenHandContentsAndRejectsUnavailableAttackDeclarations() {
        final Fixture f = fixture();
        final Card attacker = creature(f.attacker(), 2, 2, List.of());
        f.declaration().addAttacker(attacker, f.observer());
        final Card hidden = addCardToZone("Grizzly Bears", f.attacker(), ZoneType.Hand);
        final PublicCombatSnapshot before = PublicCombatSnapshot.capture(f.observer(), f.declaration());
        f.attacker().getZone(ZoneType.Hand).remove(hidden);
        addCardToZone("Giant Growth", f.attacker(), ZoneType.Hand);
        Assert.assertEquals(PublicCombatSnapshot.capture(f.observer(), f.declaration()), before);
        attacker.setTapped(true);
        final PublicCombatSnapshot illegal = PublicCombatSnapshot.capture(f.observer(), f.declaration());
        Assert.assertTrue(illegal.unavailableReasons().contains("Invalid attack declaration"));
        final CombatProjection result = CombatOutcomePredictor.predict(illegal,
                new CombatAssignment(illegal.attackersToDefenders(), Map.of()));
        Assert.assertFalse(result.available());
        Assert.assertTrue(result.supported());
    }

    @Test
    public void frozenLifeRestrictionsSuppressGainAndPreventFalseElimination() {
        final Fixture f = fixture();
        final Card attacker = creature(f.attacker(), 3, 3, List.of("K:Lifelink"));
        f.declaration().addAttacker(attacker, f.observer());
        final PublicCombatSnapshot original = PublicCombatSnapshot.capture(f.observer(), f.declaration());
        final Map<Integer, PublicCombatSnapshot.LifeState> players = Map.of(
                f.attacker().getId(), new PublicCombatSnapshot.LifeState(20, true, false, false, false),
                f.observer().getId(), new PublicCombatSnapshot.LifeState(1, true, true, true, false));
        final PublicCombatSnapshot restricted = new PublicCombatSnapshot(original.observingPlayerId(),
                original.attackingPlayerId(), original.defendingPlayerId(), original.creatures(), players,
                original.attackersToDefenders(), original.legalBlockers(), original.unavailableReasons(),
                original.unsupportedReasons(), original.legacyDamageOrder());
        final CombatProjection projection = CombatOutcomePredictor.predict(restricted,
                new CombatAssignment(restricted.attackersToDefenders(), Map.of()));
        Assert.assertTrue(projection.available());
        Assert.assertEquals(projection.playerLifeAfter().get(f.observer().getId()).intValue(), -2);
        Assert.assertEquals(projection.playerLifeAfter().get(f.attacker().getId()).intValue(), 20);
        Assert.assertEquals(projection.terminal(), CombatProjection.Terminal.NONE);
        Assert.assertTrue(projection.batches().get(0).lifeGained().isEmpty());
    }

    private Fixture fixture() {
        final Game game = initAndCreateGame();
        final Player observer = game.getPlayers().get(1);
        final Player attacking = game.getPlayers().get(0);
        observer.setTeam(0);
        attacking.setTeam(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, attacking);
        return new Fixture(game, observer, attacking, new Combat(attacking));
    }

    @DataProvider(name = "gangStrikeMechanics")
    public Object[][] gangStrikeMechanics() {
        return new Object[][] {
            {List.of(), 4, true, 20},
            {List.of("K:First strike"), 4, false, 20},
            {List.of("K:Double strike", "K:Trample"), 5, false, 14}
        };
    }

    @Test(dataProvider = "gangStrikeMechanics")
    public void explicitGangAllocationsPreserveSimultaneousAndStrikeTiming(final List<String> keywords,
            final int power, final boolean attackerDies, final int lifeAfter) {
        final Fixture f = fixture();
        final Card attacker = creature(f.attacker(), power, 4, keywords);
        final Card first = creature(f.observer(), 2, 2, List.of());
        final Card second = creature(f.observer(), 2, 2, List.of());
        f.declaration().addAttacker(attacker, f.observer());
        final PublicCombatSnapshot snapshot = PublicCombatSnapshot.capture(f.observer(), f.declaration());
        final CombatAssignment assignment = new CombatAssignment(snapshot.attackersToDefenders(),
                Map.of(attacker.getId(), List.of(first.getId(), second.getId())));
        final boolean strike = !keywords.isEmpty();
        final CombatDamageAllocation.Allocation gang = new CombatDamageAllocation.Allocation(
                Map.of(first.getId(), 2, second.getId(), 2), power - 4);
        final CombatDamagePlan plan = new CombatDamagePlan(strike ? Map.of(attacker.getId(), gang) : Map.of(),
                strike ? Map.of() : Map.of(attacker.getId(), gang));
        final CombatProjection projection = CombatOutcomePredictor.predict(snapshot, assignment, plan);
        Assert.assertTrue(projection.supported() && projection.available(), projection.reasons().toString());
        Assert.assertEquals(projection.lostCreatures().contains(attacker.getId()), attackerDies);
        Assert.assertTrue(projection.lostCreatures().containsAll(Set.of(first.getId(), second.getId())));
        Assert.assertEquals(projection.playerLifeAfter().get(f.observer().getId()).intValue(), lifeAfter);
        resolveGroupsWithEngineAndCompare(f, Map.of(attacker, List.of(first, second)), projection);
    }

    @Test
    public void gangAllocationChecksActualOrderingAndMissingPlansAreNotZeroOutcomes() {
        final Fixture f = fixture();
        final Card attacker = creature(f.attacker(), 3, 3, List.of());
        final Card first = creature(f.observer(), 1, 2, List.of());
        final Card second = creature(f.observer(), 1, 2, List.of());
        f.declaration().addAttacker(attacker, f.observer());
        final PublicCombatSnapshot original = PublicCombatSnapshot.capture(f.observer(), f.declaration());
        final PublicCombatSnapshot legacy = new PublicCombatSnapshot(original.observingPlayerId(), original.attackingPlayerId(),
                original.defendingPlayerId(), original.creatures(), original.players(), original.attackersToDefenders(),
                original.legalBlockers(), original.unavailableReasons(), original.unsupportedReasons(), true);
        final CombatAssignment assignment = new CombatAssignment(legacy.attackersToDefenders(),
                Map.of(attacker.getId(), List.of(first.getId(), second.getId())));
        final CombatDamagePlan illegal = new CombatDamagePlan(Map.of(), Map.of(attacker.getId(),
                new CombatDamageAllocation.Allocation(Map.of(second.getId(), 3), 0)));
        final CombatProjection rejected = CombatOutcomePredictor.predict(legacy, assignment, illegal);
        Assert.assertTrue(rejected.supported());
        Assert.assertFalse(rejected.available());
        Assert.assertFalse(CombatOutcomePredictor.predict(legacy, assignment, new CombatDamagePlan(Map.of(), Map.of())).supported());
        final CombatProjection modern = CombatOutcomePredictor.predict(new PublicCombatSnapshot(original.observingPlayerId(),
                original.attackingPlayerId(), original.defendingPlayerId(), original.creatures(), original.players(),
                original.attackersToDefenders(), original.legalBlockers(), original.unavailableReasons(), original.unsupportedReasons(), false),
                assignment, illegal);
        Assert.assertTrue(modern.available());
        Assert.assertEquals(modern.lostCreatures(), Set.of(second.getId()));
    }

    @Test
    public void explicitDeathtouchTrampleCanAssignLethalToIndestructibleWithoutDestroyingIt() {
        final Fixture f = fixture();
        final Card attacker = creature(f.attacker(), 4, 4, List.of("K:Deathtouch", "K:Trample"));
        final Card blocker = creature(f.observer(), 1, 6, List.of("K:Indestructible"));
        f.declaration().addAttacker(attacker, f.observer());
        final PublicCombatSnapshot snapshot = PublicCombatSnapshot.capture(f.observer(), f.declaration());
        final CombatAssignment assignment = new CombatAssignment(snapshot.attackersToDefenders(),
                Map.of(attacker.getId(), List.of(blocker.getId())));
        final CombatDamagePlan plan = new CombatDamagePlan(Map.of(), Map.of(attacker.getId(),
                new CombatDamageAllocation.Allocation(Map.of(blocker.getId(), 1), 3)));
        final CombatProjection projection = CombatOutcomePredictor.predict(snapshot, assignment, plan);
        Assert.assertTrue(projection.available());
        Assert.assertTrue(projection.lostCreatures().isEmpty());
        Assert.assertEquals(projection.playerLifeAfter().get(f.observer().getId()).intValue(), 17);
        Assert.assertEquals(projection.survivors().get(blocker.getId()).markedDamage(), 1);
        Assert.assertFalse(CombatOutcomePredictor.predict(snapshot, assignment).supported(), "No rollout before the execution bridge");
    }

    /** Only tests execute real combat. Production prediction must never install or resolve it. */
    private static void resolveWithEngineAndCompare(final Fixture f, final Map<Card, Card> blocks,
            final CombatProjection projection) {
        final Map<Card, List<Card>> groups = new java.util.LinkedHashMap<>();
        blocks.forEach((attacker, blocker) -> groups.put(attacker, List.of(blocker)));
        resolveGroupsWithEngineAndCompare(f, groups, projection);
    }

    private static void resolveGroupsWithEngineAndCompare(final Fixture f, final Map<Card, List<Card>> blocks,
            final CombatProjection projection) {
        final List<Card> combatants = new ArrayList<>(f.declaration().getAttackers());
        blocks.forEach((attacker, group) -> {
            for (final Card blocker : group) {
                f.declaration().addBlocker(attacker, blocker);
                combatants.add(blocker);
            }
        });
        for (final Card attacker : f.declaration().getAttackers()) {
            f.declaration().setBlocked(attacker, blocks.containsKey(attacker));
        }
        f.game().getPhaseHandler().setCombat(f.declaration());
        f.declaration().orderBlockersForDamageAssignment();
        f.declaration().orderAttackersForDamageAssignment();
        for (final boolean first : List.of(true, false)) {
            f.game().getPhaseHandler().devModeSet(first ? PhaseType.COMBAT_FIRST_STRIKE_DAMAGE
                    : PhaseType.COMBAT_DAMAGE, f.attacker(), false);
            f.declaration().removeAbsentCombatants();
            if (f.declaration().assignCombatDamage(first)) { f.declaration().dealAssignedDamage(); }
            f.game().getAction().checkStateEffects(true);
        }
        Assert.assertEquals(f.observer().getLife(), projection.playerLifeAfter().get(f.observer().getId()).intValue());
        Assert.assertEquals(f.attacker().getLife(), projection.playerLifeAfter().get(f.attacker().getId()).intValue());
        for (final Card card : combatants) {
            Assert.assertEquals(!card.isInPlay(), projection.lostCreatures().contains(card.getId()));
            if (card.isInPlay()) {
                Assert.assertEquals(card.getDamage(), projection.survivors().get(card.getId()).markedDamage());
            }
        }
    }

    private static Card creature(final Player owner, final int power, final int toughness, final List<String> extra) {
        final List<String> script = new ArrayList<>(List.of("Name:Combat Fixture", "ManaCost:2",
                "Types:Creature Human", "PT:" + power + "/" + toughness));
        script.addAll(extra);
        final Card card = Card.fromPaperCard(new PaperCard(CardRules.fromScript(script),
                CardEdition.UNKNOWN_CODE, CardRarity.Special), owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());
        owner.getZone(ZoneType.Battlefield).add(card);
        card.setSickness(false);
        return card;
    }
}
