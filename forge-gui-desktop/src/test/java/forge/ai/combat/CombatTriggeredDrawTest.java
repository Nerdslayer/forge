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
import forge.ai.effect.DrawOutcomeDescription;
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

/** Concrete resource outcomes, opportunity ownership, timing and hidden-information boundaries. */
public class CombatTriggeredDrawTest extends AITest {
    private record Fixture(Game game, Player ai, Player opponent, Combat live) { }

    @Test
    public void attackAndBlockDrawBeforeSimultaneousDeathsAndRetireOnlyTheirOwnUses() {
        final var f = fixture();
        final Card attacker = creature(f.ai(), 2, 2, "Attacks", 1, "You");
        final Card blocker = creature(f.opponent(), 2, 2, "Blocks", 2, "You");
        for (int index = 0; index < 7; index++) { addCard(f.opponent(), ZoneType.Hand, "Hidden Hand " + index); }
        final var before = snapshot(f, attacker);
        Assert.assertTrue(before.unsupportedReasons().isEmpty(), before.unsupportedReasons().toString());
        Assert.assertEquals(before.triggers().size(), 2);
        final var values = CombatValuationEvaluator.prepare(ValuationContext.forCombat(f.ai(), ValuationDecision.ATTACK, 100, 100));
        Assert.assertEquals(values.opportunities().size(), 2);
        final int sentinel = f.game().nextCardId();
        final var projection = CombatOutcomePredictor.predict(before,
                new CombatAssignment(before.attackersToDefenders(), Map.of(attacker.getId(), List.of(blocker.getId()))));
        Assert.assertTrue(projection.available(), projection.reasons().toString());
        Assert.assertEquals(projection.lostCreatures(), Set.of(attacker.getId(), blocker.getId()));
        Assert.assertEquals(projection.outcomes().resourcesAfter().get(f.ai().getId()), new CombatPlayerResources(1, 11));
        Assert.assertEquals(projection.outcomes().resourcesAfter().get(f.opponent().getId()), new CombatPlayerResources(9, 10));
        Assert.assertEquals(projection.outcomes().utility(), PlayerResourceValueEvaluator.evaluateCardDraw(0, 1)
                - PlayerResourceValueEvaluator.evaluateCardDraw(7, 2));
        final var score = CombatTransitionValueEvaluator.evaluate(before, values, projection);
        final var retired = values.retireOpportunities(projection.outcomes().resolutions());
        Assert.assertEquals(score.permanentLoss(), retired.evaluateLosses(projection.lostCreatures()));
        Assert.assertEquals(score.outcomeUtility(), projection.outcomes().utility());
        for (final var entry : values.opportunities().entrySet()) {
            final var old = entry.getValue();
            final var now = retired.opportunities().get(entry.getKey());
            Assert.assertTrue(Math.abs((long) now.remainingLossValue()) < Math.abs((long) old.remainingLossValue()));
        }
        Assert.assertTrue(retired.surviving(projection.lostCreatures()).opportunities().isEmpty());
        Assert.assertEquals(f.game().nextCardId(), sentinel + 1);
        Assert.assertEquals(f.ai().getCardsIn(ZoneType.Hand).size(), 0);
        Assert.assertEquals(f.opponent().getCardsIn(ZoneType.Hand).size(), 7);
        Assert.assertEquals(f.ai().getCardsIn(ZoneType.Library).size(), 12);
        Assert.assertEquals(attacker.getDamage(), 0);
        Assert.assertTrue(attacker.isUntapped() && blocker.isUntapped() && f.game().getStack().isEmpty());
    }

    @Test
    public void observedDeclarationsDoNotRepeatDrawsOrRetainTheirCurrentUseAllowance() {
        final var f = fixture();
        final Card attacker = creature(f.ai(), 2, 3, "Attacks", 1, "You");
        final var context = ValuationContext.forCombat(f.ai(), ValuationDecision.ATTACK, 0, 100);
        final var unobserved = CombatValuationEvaluator.prepare(context);
        f.live().addAttacker(attacker, f.opponent());
        final var before = PublicCombatSnapshot.capture(f.ai(), f.live());
        final var projection = CombatOutcomePredictor.predict(before, new CombatAssignment(before.attackersToDefenders(), Map.of()));
        Assert.assertTrue(projection.available(), projection.reasons().toString());
        Assert.assertTrue(projection.outcomes().resolutions().isEmpty());
        Assert.assertEquals(projection.outcomes().resourcesAfter(), before.resources());
        Assert.assertEquals(projection.outcomes().utility(), 0);
        final var observed = CombatValuationEvaluator.prepare(context);
        final var key = before.triggers().get(0).ability();
        Assert.assertEquals(observed, unobserved.retireOpportunities(Map.of(key, 1)));
    }

    @Test
    public void multipleDrawAbilitiesShareDiminishingResourcesButKeepSeparateOwnershipAndNoHiddenIdentities() {
        final var f = fixture();
        final Card attacker = scriptedCreature(f.ai(), List.of(
                "T:Mode$ Attacks | ValidCard$ Card.Self | TriggerZones$ Battlefield | Execute$ First",
                "T:Mode$ Attacks | ValidCard$ Card.Self | TriggerZones$ Battlefield | Execute$ Second",
                "SVar:First:DB$ Draw | Defined$ You | NumCards$ 1",
                "SVar:Second:DB$ Draw | Defined$ You | NumCards$ 1"), 2, 3);
        addCard(f.opponent(), ZoneType.Hand, "Unknown A");
        final var before = snapshot(f, attacker);
        final Card hidden = f.opponent().getCardsIn(ZoneType.Hand).get(0);
        f.opponent().getZone(ZoneType.Hand).remove(hidden);
        addCard(f.opponent(), ZoneType.Hand, "Unknown B");
        Assert.assertEquals(snapshot(f, attacker), before, "Equal public counts must not disclose card identity or quality");
        final var result = CombatOutcomePredictor.predict(before, new CombatAssignment(before.attackersToDefenders(), Map.of()));
        Assert.assertEquals(result.outcomes().utility(), PlayerResourceValueEvaluator.evaluateCardDraw(0, 2));
        Assert.assertEquals(result.outcomes().resolutions().size(), 2);
        Assert.assertTrue(result.outcomes().resolutions().values().stream().allMatch(count -> count == 1));
        Assert.assertEquals(result.outcomes().resourcesAfter().get(f.ai().getId()), new CombatPlayerResources(2, 10));
        Assert.expectThrows(UnsupportedOperationException.class, () -> result.outcomes().resourcesAfter().clear());
        Assert.expectThrows(UnsupportedOperationException.class, () -> result.outcomes().resolutions().clear());
    }

    @DataProvider(name = "unsupportedOutcomes")
    public Object[][] unsupportedOutcomes() {
        return new Object[][] {{"DB$ Draw | Defined$ You | NumCards$ X"},
                {"DB$ Draw | ValidTgts$ Player | NumCards$ 1"},
                {"DB$ Draw | Defined$ You | NumCards$ 1 | SubAbility$ Other"},
                {"DB$ Draw | Defined$ You | NumCards$ 1 | RememberDrawn$ True"}};
    }

    @Test(dataProvider = "unsupportedOutcomes")
    public void richerOutcomesAreNotSilentlyTreatedAsFixedDraw(final String outcome) {
        final var f = fixture();
        final Card attacker = scriptedCreature(f.ai(), List.of(
                "T:Mode$ Attacks | ValidCard$ Card.Self | TriggerZones$ Battlefield | Execute$ Draw",
                "SVar:Draw:" + outcome, "SVar:Other:DB$ GainLife | Defined$ You | LifeAmount$ 2",
                "SVar:X:Count$YourHand"), 2, 2);
        final var before = snapshot(f, attacker);
        Assert.assertTrue(before.triggers().isEmpty());
        Assert.assertTrue(before.unsupportedReasons().isEmpty());
        Assert.assertFalse(before.ignoredEffects().isEmpty());
    }

    @Test
    public void opponentRecipientDrawIsADrawbackAndInsufficientLibraryNeverCertifiesLethal() {
        final var f = fixture();
        final Card attacker = creature(f.ai(), 2, 2, "Attacks", 1, "Opponent");
        final var before = snapshot(f, attacker);
        final var result = CombatOutcomePredictor.predict(before, new CombatAssignment(before.attackersToDefenders(), Map.of()));
        Assert.assertEquals(result.outcomes().utility(), -PlayerResourceValueEvaluator.evaluateCardDraw(0, 1));
        final var resources = new java.util.LinkedHashMap<>(before.resources());
        resources.put(f.opponent().getId(), new CombatPlayerResources(0, 0));
        final var players = new java.util.LinkedHashMap<>(before.players());
        players.put(f.opponent().getId(), new PublicCombatSnapshot.LifeState(1, true, true, false, false));
        final var exhausted = new PublicCombatSnapshot(before.observingPlayerId(), before.attackingPlayerId(), before.defendingPlayerId(),
                before.creatures(), players, before.attackersToDefenders(), before.legalBlockers(), List.of(), List.of(), false,
                resources, before.triggers(), Set.of(), Set.of());
        final var rejected = CombatOutcomePredictor.predict(exhausted, new CombatAssignment(exhausted.attackersToDefenders(), Map.of()));
        Assert.assertFalse(rejected.supported());
        Assert.assertFalse(rejected.available());
        Assert.assertEquals(rejected.terminal(), CombatProjection.Terminal.NONE);
        Assert.assertTrue(rejected.reasons().toString().contains("exhaust"));
    }

    @DataProvider(name = "damageStrikeSteps")
    public Object[][] damageStrikeSteps() {
        return new Object[][] {{List.of(), 2, 1}, {List.of("K:First strike"), 2, 1},
                {List.of("K:Double strike"), 2, 2}, {List.of("K:Double strike"), 0, 0}};
    }

    @Test(dataProvider = "damageStrikeSteps")
    public void playerDamageDrawsOncePerPositiveInstanceAcrossStrikeSteps(final List<String> keywords,
            final int power, final int expectedDraws) {
        final var f = fixture();
        final Card attacker = damageCreature(f.ai(), power, 3, keywords, "You");
        final var before = snapshot(f, attacker);
        Assert.assertTrue(before.unsupportedReasons().isEmpty(), before.unsupportedReasons().toString());
        final var result = CombatOutcomePredictor.predict(before, new CombatAssignment(before.attackersToDefenders(), Map.of()));
        Assert.assertTrue(result.available(), result.reasons().toString());
        Assert.assertEquals(result.outcomes().utility(), PlayerResourceValueEvaluator.evaluateCardDraw(0, expectedDraws));
        Assert.assertEquals(result.outcomes().resourcesAfter().get(f.ai().getId()), new CombatPlayerResources(expectedDraws, 12 - expectedDraws));
        Assert.assertEquals(result.outcomes().resolutions().getOrDefault(before.triggers().get(0).ability(), 0).intValue(), expectedDraws);
        final var first = CombatOutcomePredictor.predictFirstStrike(before, new CombatAssignment(before.attackersToDefenders(), Map.of()), null);
        final int firstDraws = keywords.isEmpty() || power == 0 ? 0 : 1;
        Assert.assertEquals(first.outcomes().utility(), PlayerResourceValueEvaluator.evaluateCardDraw(0, firstDraws));
        Assert.assertEquals(f.ai().getCardsIn(ZoneType.Hand).size(), 0);
        Assert.assertEquals(f.ai().getCardsIn(ZoneType.Library).size(), 12);
    }

    @Test
    public void creatureDamageDoesNotTriggerPlayerDrawButTrampleFromADyingSourceDoes() {
        for (final boolean trample : List.of(false, true)) {
            final var f = fixture();
            final Card attacker = damageCreature(f.ai(), 2, 1, trample ? List.of("K:Trample") : List.of(), "You");
            final Card blocker = creature(f.opponent(), 3, 1, null, 0, "You");
            final var before = snapshot(f, attacker);
            final var result = CombatOutcomePredictor.predict(before,
                    new CombatAssignment(before.attackersToDefenders(), Map.of(attacker.getId(), List.of(blocker.getId()))));
            Assert.assertTrue(result.available(), result.reasons().toString());
            Assert.assertEquals(result.lostCreatures(), Set.of(attacker.getId(), blocker.getId()));
            Assert.assertEquals(result.outcomes().utility(), trample ? PlayerResourceValueEvaluator.evaluateCardDraw(0, 1) : 0);
            Assert.assertEquals(result.outcomes().resolutions().size(), trample ? 1 : 0,
                    "The trigger uses the damage batch's last-known source, not only survivors");
        }
    }

    @Test
    public void declarationObservationDoesNotRetireAFutureDamageOpportunityAndRecipientPolarityIsPreserved() {
        final var f = fixture();
        final Card attacker = damageCreature(f.ai(), 2, 3, List.of(), "Opponent");
        final var context = ValuationContext.forCombat(f.ai(), ValuationDecision.ATTACK, 0, 100);
        final var original = CombatValuationEvaluator.prepare(context);
        Assert.assertFalse(original.opportunities().isEmpty());
        f.live().addAttacker(attacker, f.opponent());
        Assert.assertEquals(CombatValuationEvaluator.prepare(context), original,
                "Declaring an attacker does not mean its damage trigger already happened");
        final var before = PublicCombatSnapshot.capture(f.ai(), f.live());
        final var result = CombatOutcomePredictor.predict(before, new CombatAssignment(before.attackersToDefenders(), Map.of()));
        Assert.assertEquals(result.outcomes().utility(), -PlayerResourceValueEvaluator.evaluateCardDraw(0, 1));
        Assert.assertEquals(result.outcomes().resolutions().values().iterator().next().intValue(), 1);
        Assert.assertEquals(CombatTransitionValueEvaluator.evaluate(before, original, result).outcomeUtility(), result.outcomes().utility());
    }

    @Test
    public void frozenDamageMatchingRetainsPlayerOwnershipFiltersAndCombatStepScope() {
        for (final String validTarget : List.of("Player", "Player.Opponent")) {
            final var f = fixture();
            final Card source = scriptedCreature(f.ai(), List.of(
                    "T:Mode$ DamageDone | ValidSource$ Card.Self | ValidTarget$ " + validTarget
                            + " | CombatDamage$ True | TriggerZones$ Battlefield | Execute$ Draw",
                    "SVar:Draw:DB$ Draw | Defined$ You | NumCards$ 1"), 2, 3);
            final var before = snapshot(f, source);
            final var state = Map.of(source.getId(), new CombatEventBatch.CardState(before.creatures().get(source.getId()), 0, false, true));
            for (final Player target : List.of(f.ai(), f.opponent())) {
                final var events = List.<CombatEventBatch.Event>of(new CombatEventBatch.Damage(source.getId(), target.getId(),
                        CombatEventBatch.Recipient.PLAYER, 1));
                final var damage = new CombatEventBatch(CombatEventBatch.Stage.REGULAR_DAMAGE, state, Map.of(), events);
                final var result = forge.ai.effect.CombatEventOutcomeResolver.resolve(before, List.of(damage));
                final boolean matches = "Player".equals(validTarget) || target == f.opponent();
                Assert.assertEquals(result.resolutions().size(), matches ? 1 : 0);
                Assert.assertEquals(result.utility(), matches ? PlayerResourceValueEvaluator.evaluateCardDraw(0, 1) : 0);
                final var wrongStep = new CombatEventBatch(CombatEventBatch.Stage.ATTACK_DECLARATION, state, Map.of(), events);
                Assert.assertTrue(forge.ai.effect.CombatEventOutcomeResolver.resolve(before, List.of(wrongStep)).resolutions().isEmpty());
            }
            Assert.expectThrows(UnsupportedOperationException.class, () -> before.triggers().get(0).parameters().clear());
        }
    }

    @Test
    public void terminalDamageEndsBeforeDrawResolutionButFirstStrikeOverdrawRejectsTheProjection() {
        final var f = fixture();
        final Card attacker = damageCreature(f.ai(), 2, 3, List.of("K:Double strike"), "You");
        final var base = snapshot(f, attacker);
        final var resources = new java.util.LinkedHashMap<>(base.resources());
        resources.put(f.ai().getId(), new CombatPlayerResources(0, 0));
        for (final int opponentLife : List.of(1, 3)) {
            final var players = new java.util.LinkedHashMap<>(base.players());
            players.put(f.opponent().getId(), new PublicCombatSnapshot.LifeState(opponentLife, true, true, false, false));
            final var before = new PublicCombatSnapshot(base.observingPlayerId(), base.attackingPlayerId(), base.defendingPlayerId(),
                    base.creatures(), players, base.attackersToDefenders(), base.legalBlockers(), List.of(), List.of(), false,
                    resources, base.triggers(), Set.of(), Set.of());
            final var result = CombatOutcomePredictor.predict(before, new CombatAssignment(before.attackersToDefenders(), Map.of()));
            if (opponentLife == 1) {
                Assert.assertTrue(result.available(), result.reasons().toString());
                Assert.assertEquals(result.terminal(), CombatProjection.Terminal.WIN);
                Assert.assertTrue(result.outcomes().resolutions().isEmpty());
                Assert.assertEquals(result.outcomes().utility(), 0);
            } else {
                Assert.assertFalse(result.available(), "An unprojected draw loss precedes regular-step lethal");
                Assert.assertFalse(result.supported());
                Assert.assertEquals(result.terminal(), CombatProjection.Terminal.NONE);
                Assert.assertTrue(result.reasons().toString().contains("exhaust"));
            }
        }
    }

    @Test
    public void damageExecutionAcceptsTheExpectedFirstStrikeDrawButRejectsUnexpectedResourceChanges() {
        for (final int extraDraws : List.of(0, 1)) {
            final var f = fixture();
            final Card attacker = damageCreature(f.ai(), 3, 3,
                    List.of("K:Double strike", "K:Trample", "K:Deathtouch"), "You");
            final Card blocker = scriptedCreature(f.opponent(), List.of("K:Indestructible"), 1, 6);
            f.live().addAttacker(attacker, f.opponent());
            f.live().addBlocker(attacker, blocker);
            f.live().setBlocked(attacker, true);
            f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, f.ai(), false);
            final var before = PublicCombatSnapshot.captureForExecution(f.ai(), f.live());
            Assert.assertTrue(before.unsupportedReasons().isEmpty(), before.unsupportedReasons().toString());
            final var assignment = new CombatAssignment(before.attackersToDefenders(), Map.of(attacker.getId(), List.of(blocker.getId())));
            final var allocations = Map.of(attacker.getId(), new CombatDamageAllocation.Allocation(Map.of(blocker.getId(), 1), 2));
            final var damagePlan = new CombatDamagePlan(allocations, allocations);
            final var execution = CombatExecutionPlan.create(f.ai(), f.live(), before, assignment, damagePlan).orElseThrow();
            final var first = CombatOutcomePredictor.predictFirstStrike(before, assignment, damagePlan);
            Assert.assertEquals(first.outcomes().resourcesAfter().get(f.ai().getId()), new CombatPlayerResources(1, 11));
            // Establish the projected public regular-step boundary with normal engine setters
            // and real card drawing. The private execution bridge must validate these changes.
            f.opponent().setLife(first.playerLifeAfter().get(f.opponent().getId()), null);
            blocker.setDamage(first.survivors().get(blocker.getId()).markedDamage());
            blocker.setHasBeenDealtDeathtouchDamage(first.survivors().get(blocker.getId()).markedDeathtouch());
            f.ai().drawCards(1 + extraDraws);
            f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE, f.ai(), false);
            final var actual = execution.assignDamage(attacker, new forge.game.card.CardCollection(List.of(blocker)), 3,
                    f.opponent(), !before.legacyDamageOrder());
            Assert.assertEquals(actual.isPresent(), extraDraws == 0);
            if (extraDraws == 0) {
                Assert.assertEquals(actual.orElseThrow().get(null).intValue(), 2);
                Assert.assertEquals(actual.orElseThrow().get(blocker).intValue(), 1);
                Assert.assertFalse(execution.isInvalid());
            } else { Assert.assertTrue(execution.isInvalid()); }
        }
    }

    @Test
    public void simultaneousSelfDeathDrawsUseLastKnownControllersAndRetireAnExplicitlyOwnedAllowance() {
        final var f = fixture();
        final List<String> unknownActivation = List.of("A:AB$ Pump | Cost$ U | NumAtt$ 1 | NumDef$ -1 | Defined$ Self");
        final Card attacker = deathCreature(f.ai(), 2, 2, unknownActivation, "You");
        final Card blocker = deathCreature(f.opponent(), 2, 2, unknownActivation, "You");
        for (int index = 0; index < 7; index++) { addCard(f.opponent(), ZoneType.Hand, "Unknown Death Hand " + index); }
        final var before = snapshot(f, attacker);
        Assert.assertTrue(before.unsupportedReasons().isEmpty(), before.unsupportedReasons().toString());
        final var actualValues = CombatValuationEvaluator.prepare(ValuationContext.forCombat(f.ai(), ValuationDecision.ATTACK, 0, 100));
        Assert.assertTrue(actualValues.opportunities().isEmpty(), "Intrinsic self-death occurrence is not implemented; do not invent a survival-weighted share");
        // Exercise generic ownership replacement independently of that deliberately unsupported
        // intrinsic estimator, using explicit per-ability reference allowances on both sides.
        final var permanents = new java.util.LinkedHashMap<>(actualValues.permanents());
        final Map<CombatAbilityKey, PreparedCombatValuation.OpportunityValue> opportunities = new java.util.LinkedHashMap<>();
        for (final var trigger : before.triggers()) {
            final var old = permanents.get(trigger.ability().sourceId());
            final int sign = old.controllerId() == f.ai().getId() ? -1 : 1;
            permanents.put(old.cardId(), new PreparedCombatValuation.PermanentValue(old.cardId(), old.controllerId(), old.bodyLossValue(),
                    old.unknownAbilityLossValue(), sign * 150, old.reasons()));
            opportunities.put(trigger.ability(), new PreparedCombatValuation.OpportunityValue(sign * 150, sign * 150));
        }
        final var values = new PreparedCombatValuation(permanents, actualValues.relationships(), actualValues.completeness(), actualValues.reasons(), opportunities);
        final var result = CombatOutcomePredictor.predict(before,
                new CombatAssignment(before.attackersToDefenders(), Map.of(attacker.getId(), List.of(blocker.getId()))));
        Assert.assertTrue(result.available(), result.reasons().toString());
        Assert.assertEquals(result.lostCreatures(), Set.of(attacker.getId(), blocker.getId()));
        Assert.assertEquals(result.outcomes().resolutions().size(), 2);
        Assert.assertFalse(before.ignoredEffects().isEmpty(), "Unknown activations must not suppress known self-death draws");
        Assert.assertEquals(result.outcomes().utility(), PlayerResourceValueEvaluator.evaluateCardDraw(0, 1)
                - PlayerResourceValueEvaluator.evaluateCardDraw(7, 1));
        Assert.assertEquals(result.outcomes().resourcesAfter().get(f.ai().getId()), new CombatPlayerResources(1, 11));
        Assert.assertEquals(result.outcomes().resourcesAfter().get(f.opponent().getId()), new CombatPlayerResources(8, 11));
        final var retired = values.retireOpportunities(result.outcomes().resolutions());
        Assert.assertTrue(retired.opportunities().values().stream().allMatch(value -> value.remainingLossValue() == 0));
        Assert.assertEquals(CombatTransitionValueEvaluator.evaluate(before, values, result).permanentLoss().futureAbility(), 0,
                "The death benefit was realized, not lost together with the creature");
        Assert.assertTrue(retired.surviving(result.lostCreatures()).opportunities().isEmpty());
        Assert.assertEquals(f.ai().getCardsIn(ZoneType.Hand).size(), 0);
        Assert.assertEquals(f.opponent().getCardsIn(ZoneType.Hand).size(), 7);
        Assert.assertEquals(attacker.getDamage(), 0);
        Assert.assertTrue(attacker.isInPlay() && blocker.isInPlay());
    }

    @Test
    public void firstStrikeDeathDrawAndDamageDrawShareResourcesWithoutReplayingTheDeadSource() {
        final var f = fixture();
        final Card attacker = damageCreature(f.ai(), 3, 3, List.of("K:Double strike", "K:Trample"), "You");
        final Card blocker = deathCreature(f.opponent(), 2, 2, List.of(), "You");
        final var before = snapshot(f, attacker);
        final var assignment = new CombatAssignment(before.attackersToDefenders(), Map.of(attacker.getId(), List.of(blocker.getId())));
        final var first = CombatOutcomePredictor.predictFirstStrike(before, assignment, null);
        final var complete = CombatOutcomePredictor.predict(before, assignment);
        Assert.assertTrue(complete.available(), complete.reasons().toString());
        Assert.assertEquals(first.outcomes().resourcesAfter().get(f.ai().getId()), new CombatPlayerResources(1, 11));
        Assert.assertEquals(first.outcomes().resourcesAfter().get(f.opponent().getId()), new CombatPlayerResources(1, 11));
        Assert.assertEquals(complete.outcomes().resourcesAfter().get(f.ai().getId()), new CombatPlayerResources(2, 10));
        Assert.assertEquals(complete.outcomes().resourcesAfter().get(f.opponent().getId()), new CombatPlayerResources(1, 11));
        final var deathKey = before.triggers().stream().filter(trigger -> trigger.ability().sourceId() == blocker.getId())
                .findFirst().orElseThrow().ability();
        Assert.assertEquals(complete.outcomes().resolutions().get(deathKey).intValue(), 1);
        Assert.assertEquals(complete.outcomes().utility(), PlayerResourceValueEvaluator.evaluateCardDraw(0, 2)
                - PlayerResourceValueEvaluator.evaluateCardDraw(0, 1));
    }

    @Test
    public void defaultZoneSelfDeathBindsTriggeredCardControllerToLastKnownControlRatherThanOwnership() {
        final var f = fixture();
        final Card stolen = scriptedCreature(f.opponent(), List.of(
                "T:Mode$ ChangesZone | ValidCard$ Card.Self | Origin$ Battlefield | Destination$ Graveyard | Execute$ Draw",
                "SVar:Draw:DB$ Draw | Defined$ TriggeredCardController | NumCards$ 1"), 2, 2);
        f.opponent().getZone(ZoneType.Battlefield).remove(stolen);
        stolen.setController(f.ai(), f.game().getNextTimestamp());
        f.ai().getZone(ZoneType.Battlefield).add(stolen);
        stolen.setSickness(false);
        Assert.assertEquals(stolen.getOwner(), f.opponent());
        Assert.assertEquals(stolen.getController(), f.ai());
        Assert.assertFalse(stolen.getTriggers().get(0).hasParam("TriggerZones"));
        final Card blocker = creature(f.opponent(), 2, 2, null, 0, "You");
        final var before = snapshot(f, stolen);
        Assert.assertTrue(before.unsupportedReasons().isEmpty(), before.unsupportedReasons().toString());
        Assert.assertEquals(before.triggers().size(), 1);
        final int sentinel = f.game().nextCardId();
        final var result = CombatOutcomePredictor.predict(before,
                new CombatAssignment(before.attackersToDefenders(), Map.of(stolen.getId(), List.of(blocker.getId()))));
        Assert.assertTrue(result.available(), result.reasons().toString());
        Assert.assertEquals(result.outcomes().utility(), PlayerResourceValueEvaluator.evaluateCardDraw(0, 1));
        Assert.assertEquals(result.outcomes().resourcesAfter().get(f.ai().getId()), new CombatPlayerResources(1, 11));
        Assert.assertEquals(result.outcomes().resourcesAfter().get(f.opponent().getId()), new CombatPlayerResources(0, 12));
        Assert.assertEquals(f.game().nextCardId(), sentinel + 1);
        Assert.assertTrue(stolen.getSVar("Draw").contains("TriggeredCardController"));
        Assert.assertTrue(stolen.isInPlay());
    }

    @Test
    public void survivingIndestructibleCreatureDoesNotDrawOrRetireItsDeathOpportunity() {
        final var f = fixture();
        final Card attacker = deathCreature(f.ai(), 2, 2, List.of("K:Indestructible"), "You");
        final Card blocker = creature(f.opponent(), 3, 3, null, 0, "You");
        final var before = snapshot(f, attacker);
        final var original = CombatValuationEvaluator.prepare(ValuationContext.forCombat(f.ai(), ValuationDecision.ATTACK, 0, 100));
        f.live().addAttacker(attacker, f.opponent());
        Assert.assertEquals(CombatValuationEvaluator.prepare(ValuationContext.forCombat(f.ai(), ValuationDecision.ATTACK, 0, 100)), original,
                "Declaring combat does not mean a self-death opportunity has already occurred");
        final var result = CombatOutcomePredictor.predict(before,
                new CombatAssignment(before.attackersToDefenders(), Map.of(attacker.getId(), List.of(blocker.getId()))));
        Assert.assertTrue(result.lostCreatures().isEmpty());
        Assert.assertTrue(result.outcomes().resolutions().isEmpty());
        Assert.assertEquals(result.outcomes().resourcesAfter(), before.resources());
        Assert.assertEquals(original.retireOpportunities(result.outcomes().resolutions()), original);
    }

    @Test
    public void deathDrawOverdrawBeforeRegularLethalIsUnsupportedRatherThanClipped() {
        final var f = fixture();
        final Card attacker = scriptedCreature(f.ai(), List.of("K:Double strike", "K:Trample"), 3, 3);
        final Card blocker = deathCreature(f.opponent(), 1, 1, List.of(), "Opponent");
        f.opponent().setLife(4, null);
        final var base = snapshot(f, attacker);
        final var resources = new java.util.LinkedHashMap<>(base.resources());
        resources.put(f.ai().getId(), new CombatPlayerResources(0, 0));
        final var before = new PublicCombatSnapshot(base.observingPlayerId(), base.attackingPlayerId(), base.defendingPlayerId(),
                base.creatures(), base.players(), base.attackersToDefenders(), base.legalBlockers(), List.of(), List.of(), false,
                resources, base.triggers(), Set.of(), Set.of());
        final var result = CombatOutcomePredictor.predict(before,
                new CombatAssignment(before.attackersToDefenders(), Map.of(attacker.getId(), List.of(blocker.getId()))));
        Assert.assertFalse(result.available());
        Assert.assertFalse(result.supported());
        Assert.assertEquals(result.terminal(), CombatProjection.Terminal.NONE);
        Assert.assertTrue(result.reasons().toString().contains("exhaust"));
    }

    @Test
    public void terminalDamageEndsBeforeSimultaneousDeathDrawsResolve() {
        final var f = fixture();
        final Card traded = deathCreature(f.ai(), 2, 2, List.of(), "You");
        final Card finisher = creature(f.ai(), 5, 5, null, 0, "You");
        final Card blocker = creature(f.opponent(), 2, 2, null, 0, "You");
        f.opponent().setLife(1, null);
        final Combat detached = new Combat(f.ai());
        detached.addAttackerForValidation(traded, f.opponent());
        detached.addAttackerForValidation(finisher, f.opponent());
        final var base = PublicCombatSnapshot.capture(f.ai(), detached);
        final var resources = new java.util.LinkedHashMap<>(base.resources());
        resources.put(f.ai().getId(), new CombatPlayerResources(0, 0));
        final var before = new PublicCombatSnapshot(base.observingPlayerId(), base.attackingPlayerId(), base.defendingPlayerId(),
                base.creatures(), base.players(), base.attackersToDefenders(), base.legalBlockers(), List.of(), List.of(), false,
                resources, base.triggers(), Set.of(), Set.of());
        final var result = CombatOutcomePredictor.predict(before,
                new CombatAssignment(before.attackersToDefenders(), Map.of(traded.getId(), List.of(blocker.getId()))));
        Assert.assertTrue(result.available(), result.reasons().toString());
        Assert.assertEquals(result.terminal(), CombatProjection.Terminal.WIN);
        Assert.assertEquals(result.lostCreatures(), Set.of(traded.getId(), blocker.getId()));
        Assert.assertTrue(result.outcomes().resolutions().isEmpty());
        Assert.assertEquals(result.outcomes().utility(), 0);
    }

    @DataProvider(name = "unsupportedDeathTriggers")
    public Object[][] unsupportedDeathTriggers() {
        return new Object[][] {{"ChangesZone", "Card.Self", "Battlefield", "Exile", ""},
                {"ChangesZone", "Card.Self", "Hand", "Graveyard", ""},
                {"ChangesZone", "Creature.YouCtrl", "Battlefield", "Graveyard", ""},
                {"ChangesZone", "Card.Self", "Battlefield", "Graveyard", " | OptionalDecider$ You"},
                {"ChangesZoneAll", "Card.Self", "Battlefield", "Graveyard", ""}};
    }

    @Test(dataProvider = "unsupportedDeathTriggers")
    public void broaderZoneChangesAndOptionalOrAggregateDeathsRemainExplicitlyIgnored(final String mode, final String valid,
            final String origin, final String destination, final String extra) {
        final var f = fixture();
        final Card source = scriptedCreature(f.ai(), List.of(
                "T:Mode$ " + mode + " | ValidCard$ " + valid + " | Origin$ " + origin + " | Destination$ " + destination
                        + " | TriggerZones$ Battlefield | Execute$ Draw" + extra,
                "SVar:Draw:DB$ Draw | Defined$ You | NumCards$ 1"), 2, 3);
        final var before = snapshot(f, source);
        Assert.assertTrue(before.triggers().isEmpty());
        Assert.assertTrue(before.unsupportedReasons().isEmpty());
        Assert.assertFalse(before.ignoredEffects().isEmpty());
    }

    @DataProvider(name = "unsupportedDamageTriggers")
    public Object[][] unsupportedDamageTriggers() {
        return new Object[][] {{"DamageDoneOnce", "Card.Self", "Player", "True", ""},
                {"DamageDone", "Creature.YouCtrl", "Player", "True", ""},
                {"DamageDone", "Card.Self", "Creature", "True", ""},
                {"DamageDone", "Card.Self", "Player", "False", ""},
                {"DamageDone", "Card.Self", "Player", "True", " | DamageAmount$ GE2"}};
    }

    @Test(dataProvider = "unsupportedDamageTriggers")
    public void broaderDamageFiltersAndBatchModesRemainExplicitlyIgnored(final String mode, final String source,
            final String target, final String combat, final String extra) {
        final var f = fixture();
        final Card attacker = scriptedCreature(f.ai(), List.of(
                "T:Mode$ " + mode + " | ValidSource$ " + source + " | ValidTarget$ " + target + " | CombatDamage$ " + combat
                        + " | TriggerZones$ Battlefield | Execute$ Draw" + extra,
                "SVar:Draw:DB$ Draw | Defined$ You | NumCards$ 1"), 2, 3);
        final var before = snapshot(f, attacker);
        Assert.assertTrue(before.triggers().isEmpty());
        Assert.assertTrue(before.unsupportedReasons().isEmpty());
        Assert.assertFalse(before.ignoredEffects().isEmpty());
    }

    @Test
    public void blockingDrawIsIncludedInTheSameSearchObjectiveAsTradesAndPlayerDamage() {
        final var f = fixture();
        final Card attacker = creature(f.ai(), 2, 2, null, 0, "You");
        final Card blocker = creature(f.opponent(), 2, 2, "Blocks", 1, "You");
        final Combat declaration = new Combat(f.ai());
        declaration.addAttackerForValidation(attacker, f.opponent());
        final var before = PublicCombatSnapshot.capture(f.opponent(), declaration);
        final var values = CombatValuationEvaluator.prepare(ValuationContext.forCombat(f.opponent(), ValuationDecision.BLOCK, 0, 0));
        final var result = CombatBlockSearch.search(before, values, new CombatSearchBudget(10000));
        Assert.assertTrue(result.outcomeDomainComplete() && result.searchExhaustive(), result.reasons().toString());
        Assert.assertEquals(result.best().orElseThrow().assignment().blockersByAttacker(), Map.of(attacker.getId(), List.of(blocker.getId())));
        Assert.assertEquals(result.best().orElseThrow().score().outcomeUtility(), PlayerResourceValueEvaluator.evaluateCardDraw(0, 1));
    }

    @Test
    public void replyUsesProjectedResourcesAndRetainsResolvedOpportunityOwnership() {
        final var f = fixture();
        final Card attacker = creature(f.ai(), 1, 3, "Attacks", 1, "You");
        final Card opponent = creature(f.opponent(), 1, 3, "Attacks", 2, "You");
        final var before = snapshot(f, attacker);
        final var current = CombatOutcomePredictor.predict(before, new CombatAssignment(before.attackersToDefenders(), Map.of()));
        final var values = CombatValuationEvaluator.prepare(ValuationContext.forCombat(f.ai(), ValuationDecision.ATTACK, 0, 100));
        final var ready = new PublicCombatReadiness(f.opponent().getId(), Set.of(attacker.getId(), opponent.getId()),
                Map.of(attacker.getId(), Set.of(opponent.getId()), opponent.getId(), Set.of(attacker.getId())), List.of());
        final var forecast = CombatSafetyEvaluator.forecastNextAttack(before, current, ready, values, new CombatSearchBudget(10000));
        Assert.assertTrue(forecast.evaluation().supported(), forecast.evaluation().reasons().toString());
        final var continuation = forecast.continuation().orElseThrow();
        Assert.assertEquals(continuation.snapshot().resources().get(f.ai().getId()), new CombatPlayerResources(1, 11));
        Assert.assertEquals(continuation.snapshot().resources().get(f.opponent().getId()), new CombatPlayerResources(1, 11));
        Assert.assertEquals(continuation.projection().outcomes().resourcesAfter().get(f.opponent().getId()), new CombatPlayerResources(3, 9));
        Assert.assertEquals(continuation.values().opportunities().get(before.triggers().stream()
                .filter(trigger -> trigger.ability().sourceId() == attacker.getId()).findFirst().orElseThrow().ability()),
                values.retireOpportunities(current.outcomes().resolutions()).opportunities().get(before.triggers().stream()
                        .filter(trigger -> trigger.ability().sourceId() == attacker.getId()).findFirst().orElseThrow().ability()));
    }

    @DataProvider(name = "unsupportedTriggers")
    public Object[][] unsupportedTriggers() {
        return new Object[][] {{" | Alone$ True"}, {" | OptionalDecider$ You"},
                {" | FirstAttack$ True"}, {" | CheckSVar$ X | SVarCompare$ GE1"}};
    }

    @Test(dataProvider = "unsupportedTriggers")
    public void unsupportedTriggerConditionsRemainExplicitlyIgnored(final String extra) {
        final var f = fixture();
        final Card attacker = scriptedCreature(f.ai(), List.of(
                "T:Mode$ Attacks | ValidCard$ Card.Self | TriggerZones$ Battlefield | Execute$ Draw" + extra,
                "SVar:Draw:DB$ Draw | Defined$ You | NumCards$ 1"), 2, 2);
        final var before = snapshot(f, attacker);
        Assert.assertTrue(before.triggers().isEmpty());
        Assert.assertTrue(before.unsupportedReasons().isEmpty());
        Assert.assertFalse(before.ignoredEffects().isEmpty());
    }

    @Test
    public void opportunityRetirementIsSignedCappedBranchLocalAndDoesNotRetireAnotherAbility() {
        final var attack = new CombatAbilityKey(10, "Original/trigger:0");
        final var block = new CombatAbilityKey(10, "Original/trigger:1");
        for (final int sign : List.of(-1, 1)) {
            final var original = new PreparedCombatValuation(Map.of(10, new PreparedCombatValuation.PermanentValue(
                    10, 1, 100 * sign, 10 * sign, 250 * sign, List.of())), List.of(), forge.ai.effect.ValuationCompleteness.PARTIAL,
                    List.of(), Map.of(attack, new PreparedCombatValuation.OpportunityValue(150 * sign, 100 * sign),
                            block, new PreparedCombatValuation.OpportunityValue(100 * sign, 60 * sign)));
            final var once = original.retireOpportunities(Map.of(attack, 1));
            Assert.assertEquals(once.permanents().get(10).futureAbilityLossValue(), 150 * sign);
            Assert.assertEquals(once.opportunities().get(block), original.opportunities().get(block));
            final var repeated = once.retireOpportunities(Map.of(attack, Integer.MAX_VALUE));
            Assert.assertEquals(repeated.permanents().get(10).futureAbilityLossValue(), 100 * sign);
            Assert.assertEquals(original.permanents().get(10).futureAbilityLossValue(), 250 * sign);
            Assert.assertEquals(repeated, repeated.retireOpportunities(Map.of(attack, 1)));
            Assert.assertEquals(repeated.permanents().get(10).bodyLossValue(), 100 * sign);
        }
        final var draw = DrawOutcomeDescription.evaluateResources(7, 1, 3, 2);
        Assert.assertEquals(draw.amount(), 1);
        Assert.assertTrue(draw.overdraw());
        Assert.assertEquals(draw.value(), PlayerResourceValueEvaluator.evaluateCardDraw(7, 1));
    }

    private Fixture fixture() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(0);
        final Player opponent = game.getPlayers().get(1);
        ai.setTeam(0);
        opponent.setTeam(1);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, ai);
        final Combat live = new Combat(ai);
        game.getPhaseHandler().setCombat(live);
        for (final Player player : game.getPlayers()) {
            for (int index = 0; index < 12; index++) { addCard(player, ZoneType.Library, "Unknown Library " + index); }
        }
        return new Fixture(game, ai, opponent, live);
    }

    private static PublicCombatSnapshot snapshot(final Fixture f, final Card attacker) {
        final Combat detached = new Combat(f.ai());
        detached.addAttackerForValidation(attacker, f.opponent());
        return PublicCombatSnapshot.capture(f.ai(), detached);
    }

    private static Card creature(final Player player, final int power, final int toughness,
            final String mode, final int amount, final String recipient) {
        return scriptedCreature(player, mode == null ? List.of() : List.of(
                "T:Mode$ " + mode + " | ValidCard$ Card.Self | TriggerZones$ Battlefield | Execute$ Draw",
                "SVar:Draw:DB$ Draw | Defined$ " + recipient + " | NumCards$ " + amount), power, toughness);
    }

    private static Card damageCreature(final Player player, final int power, final int toughness,
            final List<String> keywords, final String recipient) {
        final List<String> extra = new ArrayList<>(keywords);
        extra.add("T:Mode$ DamageDone | ValidSource$ Card.Self | ValidTarget$ Player | CombatDamage$ True"
                + " | TriggerZones$ Battlefield | Execute$ Draw");
        extra.add("SVar:Draw:DB$ Draw | Defined$ " + recipient + " | NumCards$ 1");
        return scriptedCreature(player, extra, power, toughness);
    }

    private static Card deathCreature(final Player player, final int power, final int toughness,
            final List<String> keywords, final String recipient) {
        final List<String> extra = new ArrayList<>(keywords);
        extra.add("T:Mode$ ChangesZone | ValidCard$ Card.Self | Origin$ Battlefield | Destination$ Graveyard"
                + " | TriggerZones$ Battlefield | Execute$ Draw");
        extra.add("SVar:Draw:DB$ Draw | Defined$ " + recipient + " | NumCards$ 1");
        return scriptedCreature(player, extra, power, toughness);
    }

    private static Card scriptedCreature(final Player owner, final List<String> extra, final int power, final int toughness) {
        final List<String> script = new ArrayList<>(List.of("Name:Combat Draw Fixture", "ManaCost:2",
                "Types:Creature Human", "PT:" + power + "/" + toughness));
        script.addAll(extra);
        final Card card = Card.fromPaperCard(new PaperCard(CardRules.fromScript(script), CardEdition.UNKNOWN_CODE, CardRarity.Special), owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());
        owner.getZone(ZoneType.Battlefield).add(card);
        card.setSickness(false);
        return card;
    }

    private static void addCard(final Player player, final ZoneType zone, final String name) {
        final PaperCard definition = new PaperCard(CardRules.fromScript(List.of("Name:" + name, "Types:Land")),
                CardEdition.UNKNOWN_CODE, CardRarity.Special);
        player.getZone(zone).add(Card.fromPaperCard(definition, player));
    }
}
