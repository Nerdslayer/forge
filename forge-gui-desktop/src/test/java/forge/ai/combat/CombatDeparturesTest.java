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
import forge.game.combat.Combat;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

/** Generic lifetime, zone-event, continuation and live-declaration regressions. */
public class CombatDeparturesTest extends AITest {
    private record Fixture(Game game, Player ai, Player opponent, Combat combat) { }

    @Test
    public void expiringMenaceAttackerTakesAProfitableTradeButOrdinaryCounterpartCanHold() {
        for (final boolean expiring : List.of(false, true)) {
            final var f = fixture();
            Card attacker = creature(f.ai(), 10, 3, List.of("K:Menace", "K:Unearth:0"));
            creature(f.opponent(), 2, 3, List.of());
            creature(f.opponent(), 2, 3, List.of());
            if (expiring) { attacker = unearth(attacker); }
            final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), budget());
            Assert.assertTrue(plan.applicable(), plan.reasons().toString());
            final var selected = plan.search().orElseThrow().best().orElseThrow();
            Assert.assertEquals(selected.attackers().contains(attacker.getId()), expiring, plan.reasons().toString());
            Assert.assertEquals(plan.snapshot().orElseThrow().departures().expiringIds().contains(attacker.getId()), expiring);
            Assert.assertTrue(CombatAttackPlanner.tryDeclare(f.ai(), f.ai(), f.combat(), budget()));
            Assert.assertEquals(f.combat().isAttacking(attacker), expiring);
        }
    }

    @Test
    public void exileReplacesDeathInASimultaneousFirstStrikeBatchWithoutInventingADraw() {
        final var f = fixture();
        final var draw = List.of("K:First strike", "K:Unearth:0",
                "T:Mode$ ChangesZone | Origin$ Battlefield | Destination$ Graveyard | ValidCard$ Card.Self | Execute$ Draw",
                "SVar:Draw:DB$ Draw | Defined$ You | NumCards$ 1");
        final Card attacker = unearth(creature(f.ai(), 5, 1, draw));
        final Card blocker = creature(f.opponent(), 5, 1, draw);
        f.combat().addAttacker(attacker, f.opponent());
        final var before = PublicCombatSnapshot.capture(f.ai(), f.combat());
        Assert.assertTrue(before.unsupportedReasons().isEmpty(), before.unsupportedReasons().toString());
        final var projection = CombatOutcomePredictor.predict(before,
                new CombatAssignment(before.attackersToDefenders(), Map.of(attacker.getId(), List.of(blocker.getId()))));
        Assert.assertTrue(projection.supported() && projection.available(), projection.reasons().toString());
        Assert.assertEquals(projection.lostCreatures(), Set.of(attacker.getId(), blocker.getId()));
        final var events = projection.eventBatches().stream().flatMap(batch -> batch.events().stream()).toList();
        Assert.assertTrue(events.contains(new CombatEventBatch.Exiled(attacker.getId())));
        Assert.assertFalse(events.contains(new CombatEventBatch.Dies(attacker.getId())));
        Assert.assertTrue(events.contains(new CombatEventBatch.Dies(blocker.getId())));
        Assert.assertEquals(projection.outcomes().resourcesAfter().get(f.ai().getId()).handSize(), 0);
        Assert.assertEquals(projection.outcomes().resourcesAfter().get(f.opponent().getId()).handSize(), 1);
        final var values = CombatPlanningPreparation.values(f.ai(), ValuationDecision.ATTACK, budget()).orElseThrow();
        final var score = CombatTransitionValueEvaluator.evaluate(before, values, projection);
        Assert.assertNotEquals(values.permanents().get(attacker.getId()).bodyLossValue(), 0,
                "The shared raw card/permanent ledger is not overwritten with disposable combat value");
        Assert.assertEquals(score.permanentLoss().body(), values.permanents().get(blocker.getId()).bodyLossValue());
        // Engine parity for the recognized replacement: a would-be graveyard move actually exiles.
        final Card moved = f.game().getAction().moveToGraveyard(attacker, null);
        Assert.assertTrue(moved.isInZone(ZoneType.Exile));
        Assert.assertFalse(blocker.isUnearthed());
    }

    @Test
    public void knownDepartureRemovesEitherControllersSurvivorAndLedgerBeforeNextCombat() {
        for (final boolean friendly : List.of(false, true)) {
            final var f = fixture();
            final Card expiring = unearth(creature(friendly ? f.ai() : f.opponent(), 9, 9, List.of("K:Unearth:0")));
            final Card attacker = creature(f.ai(), 0, 4, List.of());
            creature(f.opponent(), 2, 2, List.of());
            f.combat().addAttacker(attacker, f.opponent());
            final var before = PublicCombatSnapshot.capture(f.ai(), f.combat());
            final var held = CombatOutcomePredictor.predict(before, new CombatAssignment(before.attackersToDefenders(), Map.of()));
            Assert.assertTrue(held.survivors().containsKey(expiring.getId()));
            final var values = CombatPlanningPreparation.values(f.ai(), ValuationDecision.ATTACK, budget()).orElseThrow();
            final var next = CombatTurnProjectionBuilder.advance(before, held, PublicCombatReadiness.capture(f.ai(), before), values).turn().orElseThrow();
            Assert.assertFalse(next.alternatives().creatures().containsKey(expiring.getId()));
            Assert.assertFalse(next.values().permanents().containsKey(expiring.getId()));
            Assert.assertTrue(next.alternatives().departures().entries().isEmpty());
            Assert.assertTrue(next.alternatives().legalBlockers().values().stream().noneMatch(set -> set.contains(expiring.getId())));
            Assert.assertTrue(expiring.isInPlay(), "Forecasting must not perform the actual delayed exile");
        }
    }

    @Test
    public void realCombatMatchesProjectedExileDeathsAndLifelinkAcrossStrikeSteps() {
        for (final boolean firstStrike : List.of(false, true)) {
            final var f = fixture();
            final List<String> attacking = new ArrayList<>(List.of("K:Unearth:0", "K:Lifelink"));
            if (firstStrike) { attacking.add("K:First strike"); }
            final Card attacker = unearth(creature(f.ai(), 4, 1, attacking));
            final Card blocker = creature(f.opponent(), 2, 2, firstStrike ? List.of("K:First strike") : List.of());
            f.combat().addAttacker(attacker, f.opponent());
            final var before = PublicCombatSnapshot.capture(f.ai(), f.combat());
            final var expected = CombatOutcomePredictor.predict(before,
                    new CombatAssignment(before.attackersToDefenders(), Map.of(attacker.getId(), List.of(blocker.getId()))));
            Assert.assertTrue(expected.available() && expected.supported());
            f.combat().addBlocker(attacker, blocker);
            f.combat().setBlocked(attacker, true);
            f.combat().orderBlockersForDamageAssignment();
            f.combat().orderAttackersForDamageAssignment();
            for (final boolean first : List.of(true, false)) {
                f.game().getPhaseHandler().devModeSet(first ? PhaseType.COMBAT_FIRST_STRIKE_DAMAGE : PhaseType.COMBAT_DAMAGE, f.ai(), false);
                f.combat().removeAbsentCombatants();
                if (f.combat().assignCombatDamage(first)) { f.combat().dealAssignedDamage(); }
                f.game().getAction().checkStateEffects(true);
            }
            Assert.assertTrue(f.game().getCardState(attacker).isInZone(ZoneType.Exile));
            Assert.assertTrue(f.game().getCardState(blocker).isInZone(ZoneType.Graveyard));
            Assert.assertEquals(f.ai().getLife(), expected.playerLifeAfter().get(f.ai().getId()).intValue());
            Assert.assertEquals(f.opponent().getLife(), expected.playerLifeAfter().get(f.opponent().getId()).intValue());
        }
    }

    @Test
    public void currentAttackDrawStillCountsAndNoFutureAbilityPreservationIsCharged() {
        final var f = fixture();
        final Card attacker = unearth(creature(f.ai(), 2, 2, List.of("K:Unearth:0",
                "T:Mode$ Attacks | ValidCard$ Card.Self | TriggerZones$ Battlefield | Execute$ Draw",
                "SVar:Draw:DB$ Draw | Defined$ You | NumCards$ 1")));
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), budget());
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        final var chosen = plan.search().orElseThrow().best().orElseThrow();
        Assert.assertEquals(chosen.attackers(), List.of(attacker.getId()));
        Assert.assertTrue(chosen.combat().score().outcomeUtility() > 0);
        Assert.assertEquals(chosen.combat().projection().outcomes().resourcesAfter().get(f.ai().getId()).handSize(), 1);
        Assert.assertTrue(chosen.reply().orElseThrow().usable());
        Assert.assertEquals(f.ai().getCardsIn(ZoneType.Hand).size(), 0);
    }

    @Test
    public void removedDelayOrChangedObjectOrSuppressionInvalidatesCertainExpiry() {
        final var f = fixture();
        final Card attacker = unearth(creature(f.ai(), 3, 3, List.of("K:Unearth:0")));
        f.combat().addAttacker(attacker, f.opponent());
        final var before = PublicCombatSnapshot.capture(f.ai(), f.combat());
        Assert.assertEquals(before.departures().expiringIds(), Set.of(attacker.getId()));
        f.game().getTriggerHandler().suppressMode(forge.game.trigger.TriggerType.Phase);
        final var suppressed = PublicCombatSnapshot.capture(f.ai(), f.combat());
        Assert.assertNotEquals(suppressed, before);
        Assert.assertTrue(suppressed.departures().expiringIds().isEmpty());
        Assert.assertFalse(suppressed.departures().futureUnsupportedReasons().isEmpty());
        f.game().getTriggerHandler().clearSuppression(forge.game.trigger.TriggerType.Phase);
        f.game().getTriggerHandler().clearDelayedTrigger(attacker);
        Assert.assertFalse(f.game().getTriggerHandler().hasPendingUnearthExile(attacker));
        Assert.assertTrue(PreparedCombatDepartures.prepare(f.ai(), () -> true).orElseThrow().expiringIds().isEmpty());
        final long oldTimestamp = attacker.getGameTimestamp();
        final Card changed = f.game().getAction().moveToGraveyard(attacker, null);
        final Card returned = f.game().getAction().moveToPlay(changed, null, null);
        Assert.assertNotEquals(returned.getGameTimestamp(), oldTimestamp);
        Assert.assertTrue(PreparedCombatDepartures.prepare(f.ai(), () -> true).orElseThrow().entries().isEmpty());
    }

    @Test
    public void afterEndStepDoesNotPretendExpiryPrecedesNextCombatAndCaptureIsInterruptible() {
        final var f = fixture();
        final Card attacker = unearth(creature(f.ai(), 3, 3, List.of("K:Unearth:0")));
        f.game().getPhaseHandler().devModeSet(PhaseType.CLEANUP, f.ai());
        final var departures = PreparedCombatDepartures.prepare(f.ai(), () -> true).orElseThrow();
        Assert.assertTrue(departures.entries().containsKey(attacker.getId()));
        Assert.assertTrue(departures.expiringIds().isEmpty());
        Assert.assertFalse(departures.futureUnsupportedReasons().isEmpty());
        Assert.assertTrue(PreparedCombatDepartures.prepare(f.ai(), () -> false).isEmpty());
        Assert.assertTrue(f.game().getTriggerHandler().hasPendingUnearthExile(attacker, () -> false).isEmpty());
        final var extra = fixture();
        unearth(creature(extra.ai(), 3, 3, List.of("K:Unearth:0")));
        extra.game().getPhaseHandler().addExtraPhase(PhaseType.MAIN2, List.of(PhaseType.COMBAT_BEGIN), PhaseType.END_OF_TURN);
        Assert.assertTrue(PreparedCombatDepartures.prepare(extra.ai(), () -> true).orElseThrow().expiringIds().isEmpty());
    }

    @Test
    public void departureMetadataSurvivesDestinationSelectionAndForbidsUnsafeSingletonReuse() {
        final var f = fixture();
        final Card expiring = unearth(creature(f.ai(), 3, 3, List.of("K:Unearth:0")));
        final Card normal = creature(f.ai(), 3, 3, List.of());
        f.combat().addAttacker(expiring, f.opponent());
        f.combat().addAttacker(normal, f.opponent());
        final var snapshot = PublicCombatSnapshot.capture(f.ai(), f.combat());
        final var assigned = CombatAttackCandidates.assign(snapshot, Map.of(expiring.getId(), f.opponent().getId()));
        Assert.assertEquals(assigned.departures(), snapshot.departures());
        final var values = CombatPlanningPreparation.values(f.ai(), ValuationDecision.ATTACK, budget()).orElseThrow();
        final var representatives = CombatAttackEquivalence.representatives(snapshot, values, PublicCombatReadiness.capture(f.ai(), snapshot),
                List.of(expiring.getId(), normal.getId()));
        Assert.assertEquals(representatives.get(expiring.getId()).intValue(), expiring.getId());
        Assert.assertEquals(representatives.get(normal.getId()).intValue(), normal.getId());
    }

    @Test
    public void expiringAnthemUpdatesRecipientsAndDoesNotApplyItsDepartureTwice() {
        final var f = fixture();
        final Card source = unearth(creature(f.ai(), 1, 1, List.of("K:Unearth:0",
                "S:Mode$ Continuous | Affected$ Creature.YouCtrl+Other | AddPower$ 1 | AddToughness$ 1")));
        final Card recipient = creature(f.ai(), 2, 2, List.of());
        final Card opposing = creature(f.opponent(), 1, 1, List.of());
        f.game().getAction().checkStaticAbilities();
        f.combat().addAttacker(source, f.opponent());
        final var before = PublicCombatSnapshot.capture(f.ai(), f.combat());
        Assert.assertTrue(before.departures().futureUnsupportedReasons().isEmpty(), before.departures().toString());
        Assert.assertEquals(before.creatures().get(recipient.getId()).combatDamage(), 3);
        final var values = CombatPlanningPreparation.values(f.ai(), ValuationDecision.ATTACK, budget()).orElseThrow();
        final var projected = CombatOutcomePredictor.predict(before, new CombatAssignment(before.attackersToDefenders(), Map.of()));
        final var readiness = PublicCombatReadiness.capture(f.ai(), before);
        final var next = CombatTurnProjectionBuilder.advance(before, projected, readiness, values).turn().orElseThrow();
        Assert.assertEquals(next.alternatives().creatures().get(recipient.getId()).combatDamage(), 2);
        Assert.assertEquals(next.alternatives().creatures().get(recipient.getId()).toughness(), 2);
        Assert.assertTrue(next.alternatives().staticWorlds().providers().isEmpty());
        Assert.assertFalse(next.values().permanents().containsKey(source.getId()));
        final var hold = CombatAttackCandidates.select(next.alternatives(), List.of(opposing.getId()));
        final var reply = CombatOutcomePredictor.predict(hold, new CombatAssignment(hold.attackersToDefenders(), Map.of()));
        final var again = CombatTurnProjectionBuilder.advance(hold, reply, readiness.forActivePlayer(f.ai().getId()), next.values()).turn().orElseThrow();
        Assert.assertEquals(again.alternatives().creatures().get(recipient.getId()).combatDamage(), 2);
        Assert.assertEquals(again.values().permanents().get(recipient.getId()), next.values().permanents().get(recipient.getId()));
    }

    @Test
    public void expiringAnthemPreservationUsesTheSameBoundaryForSurvivorsAndCasualties() {
        for (final boolean friendly : List.of(true, false)) {
            final var f = fixture();
            final Card source = unearth(creature(f.ai(), 2, 1, List.of("K:Unearth:0",
                    "S:Mode$ Continuous | Affected$ Creature.Other | AddPower$ 1 | AddToughness$ 1")));
            final Card recipient = creature(friendly ? f.ai() : f.opponent(), 2, 2, List.of());
            final Card blocker = creature(f.opponent(), 1, 1, List.of());
            f.game().getAction().checkStaticAbilities();
            f.combat().addAttacker(source, f.opponent());
            final var snapshot = PublicCombatSnapshot.capture(f.ai(), f.combat());
            final var values = CombatPlanningPreparation.values(f.ai(), ValuationDecision.ATTACK, budget()).orElseThrow();
            final var trade = CombatOutcomePredictor.predict(snapshot,
                    new CombatAssignment(snapshot.attackersToDefenders(), Map.of(source.getId(), List.of(blocker.getId()))));
            Assert.assertTrue(trade.supported() && trade.available(), trade.reasons().toString());
            Assert.assertTrue(trade.lostCreatures().contains(source.getId()));
            Assert.assertTrue(trade.lostCreatures().contains(blocker.getId()));
            Assert.assertFalse(trade.lostCreatures().contains(recipient.getId()));
            final var preservation = values.continuationPreservation(snapshot);
            Assert.assertEquals(CombatTransitionValueEvaluator.evaluate(snapshot, values, trade).permanentLoss().body(),
                    preservation.permanents().get(blocker.getId()).bodyLossValue(),
                    "The disposable source can profitably trade without a spurious anthem-preservation penalty");
            Assert.assertEquals(preservation.survivorBodyUtility(snapshot, trade), 0,
                    "Losing a temporary anthem early must not charge its inevitable end-step loss");
            final int delta = snapshot.staticWorlds().afterLosses(Set.of(source.getId())).get(recipient.getId()).bodyDelta();
            Assert.assertNotEquals(delta, 0);
            final int sign = friendly ? -1 : 1;
            Assert.assertEquals(preservation.permanents().get(recipient.getId()).bodyLossValue(),
                    values.permanents().get(recipient.getId()).bodyLossValue() + sign * delta,
                    "Casualties must also lose only their durable body, not the expiring boost");
            Assert.assertEquals(snapshot.creatures().get(recipient.getId()).toughness(), 3,
                    "Preservation rebasing must not alter actual combat characteristics");
        }
    }

    @Test
    public void onlyApplicableTriggerAndExileRestrictionsPreventKnownExpiry() {
        final List<String> restrictions = List.of(
                "S:Mode$ DisableTriggers | ValidCause$ Creature | ValidMode$ ChangesZone,ChangesZoneAll | Destination$ Battlefield",
                "S:Mode$ DisableTriggers | ValidCause$ Permanent | ValidMode$ ChangesZone,ChangesZoneAll | Destination$ Battlefield | ValidCard$ Permanent.OppCtrl+inZoneBattlefield",
                "S:Mode$ CantExile | ValidCard$ Creature.YouCtrl+token | ValidCause$ Triggered.YouCtrl | ForCost$ False",
                "S:Mode$ CantExile | ValidCard$ Creature | ForCost$ True",
                "S:Mode$ DisableTriggers | ValidMode$ Phase",
                "S:Mode$ CantExile | ValidCard$ Creature | ValidCause$ Triggered | ForCost$ False");
        for (int index = 0; index < restrictions.size(); index++) {
            final var f = fixture();
            final Card attacker = unearth(creature(f.ai(), 3, 3, List.of("K:Unearth:0")));
            creature(f.ai(), 1, 1, List.of(restrictions.get(index)));
            final var metadata = PreparedCombatDepartures.prepare(f.ai(), () -> true).orElseThrow();
            Assert.assertTrue(metadata.unsupportedReasons().isEmpty(), metadata.toString());
            Assert.assertEquals(metadata.expiringIds().contains(attacker.getId()), index < 4, restrictions.get(index));
            Assert.assertEquals(metadata.futureUnsupportedReasons().isEmpty(), index < 4, restrictions.get(index));
            Assert.assertTrue(attacker.isInPlay(), "Restriction queries must not resolve or consume delayed triggers");
        }
    }

    @Test
    public void expiryIsNotAnAlwaysAttackOverrideWhenTheBlockWouldGrantALargeLifeSwing() {
        final var f = fixture();
        final Card attacker = unearth(creature(f.ai(), 1, 1, List.of("K:Unearth:0")));
        creature(f.opponent(), 12, 12, List.of("K:Lifelink"));
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), budget());
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertFalse(plan.search().orElseThrow().best().orElseThrow().attackers().contains(attacker.getId()));
    }

    @Test
    public void executionPlanRejectsChangedDepartureEvenWhenBodyAndAssignmentsAreUnchanged() {
        final var f = fixture();
        final Card attacker = unearth(creature(f.ai(), 4, 4, List.of("K:Unearth:0", "K:Trample")));
        final Card blocker = creature(f.opponent(), 0, 2, List.of());
        f.combat().addAttacker(attacker, f.opponent());
        f.combat().addBlocker(attacker, blocker);
        f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, f.ai(), false);
        final var snapshot = PublicCombatSnapshot.capture(f.ai(), f.combat());
        final var values = CombatPlanningPreparation.values(f.ai(), ValuationDecision.ATTACK, budget()).orElseThrow();
        final var assignment = new CombatAssignment(snapshot.attackersToDefenders(), Map.of(attacker.getId(), List.of(blocker.getId())));
        final var choice = CombatDamageOptimizer.optimizeBlockGroups(snapshot, assignment, values, budget()).best().orElseThrow();
        final var execution = CombatExecutionPlan.create(f.ai(), f.combat(), snapshot, choice.assignment(), choice.damagePlan()).orElseThrow();
        f.game().getTriggerHandler().clearDelayedTrigger(attacker);
        Assert.assertTrue(execution.orderBlockers(attacker, f.combat().getBlockers(attacker)).isEmpty());
        Assert.assertTrue(execution.isInvalid());
    }

    @Test
    public void knownUnmodeledLeaveTriggerMakesTheFutureIncompleteButNotTheImmediateFloor() {
        final var f = fixture();
        final Card expiring = unearth(creature(f.opponent(), 3, 3, List.of("K:Unearth:0",
                "T:Mode$ ChangesZone | Origin$ Battlefield | Destination$ Exile | ValidCard$ Card.Self | Execute$ Draw",
                "SVar:Draw:DB$ Draw | Defined$ You | NumCards$ 1")));
        final Card attacker = creature(f.ai(), 2, 2, List.of());
        f.combat().addAttacker(attacker, f.opponent());
        final var snapshot = PublicCombatSnapshot.capture(f.opponent(), f.combat());
        Assert.assertTrue(snapshot.unsupportedReasons().isEmpty(), snapshot.unsupportedReasons().toString());
        final var values = CombatPlanningPreparation.values(f.opponent(), ValuationDecision.BLOCK, budget()).orElseThrow();
        final var readiness = PublicCombatReadiness.capture(f.opponent(), snapshot);
        Assert.assertFalse(readiness.unsupportedReasons().isEmpty());
        final var floor = CombatDefenseFloorSearch.prepare(snapshot, values, Map.of(), budget());
        Assert.assertTrue(floor.floor().best().isPresent());
        Assert.assertTrue(floor.floor().outcomeDomainComplete());
        Assert.assertTrue(expiring.isInPlay());
        Assert.assertTrue(CombatTurnProjectionBuilder.advance(snapshot, floor.floor().best().orElseThrow().projection(), readiness, values).turn().isEmpty());
    }

    @Test
    public void unrelatedEntryAndOtherObjectsSelfExitTriggersDoNotVetoTheForecast() {
        final var f = fixture();
        final Card attacker = unearth(creature(f.ai(), 3, 3, List.of("K:Unearth:0")));
        creature(f.opponent(), 2, 2, List.of(
                "T:Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield | ValidCard$ Card.Self | Execute$ Draw",
                "T:Mode$ ChangesZone | Origin$ Battlefield | Destination$ Exile | ValidCard$ Card.Self | Execute$ Draw",
                "SVar:Draw:DB$ Draw | Defined$ You | NumCards$ 1"));
        f.combat().addAttacker(attacker, f.opponent());
        final var snapshot = PublicCombatSnapshot.capture(f.ai(), f.combat());
        Assert.assertTrue(snapshot.departures().futureUnsupportedReasons().isEmpty(), snapshot.departures().toString());
        Assert.assertTrue(PublicCombatReadiness.capture(f.ai(), snapshot).unsupportedReasons().isEmpty());
    }

    @Test
    public void publicExileRestrictionsAreNotSilentlyOverriddenByUnearthAdmission() {
        for (final String restriction : List.of("S:Mode$ CantExile | ValidCard$ Creature",
                "S:Mode$ CantExile | ValidCard$ Creature | ValidCause$ SpellAbility.YouCtrl | ForCost$ False")) {
            final var f = fixture();
            final Card attacker = unearth(creature(f.ai(), 3, 3, List.of("K:Unearth:0")));
            creature(f.ai(), 2, 2, List.of(restriction));
            f.combat().addAttacker(attacker, f.opponent());
            final var snapshot = PublicCombatSnapshot.capture(f.ai(), f.combat());
            Assert.assertFalse(snapshot.unsupportedReasons().isEmpty());
            final var projection = CombatOutcomePredictor.predict(snapshot, new CombatAssignment(snapshot.attackersToDefenders(), Map.of()));
            Assert.assertFalse(projection.supported());
        }
    }

    @Test
    public void expiredPreventionAndProtectionSourcesDisappearButEquipmentIsRetained() {
        final var f = fixture();
        final Card source = unearth(creature(f.ai(), 3, 3, List.of("K:Unearth:0", "K:Protection:Creature:creatures",
                "S:Mode$ CantPreventDamage | ValidSource$ Creature.YouCtrl | IsCombat$ True")));
        final var equipment = Card.fromPaperCard(new PaperCard(CardRules.fromScript(List.of("Name:Departure Equipment",
                "ManaCost:2", "Types:Artifact Equipment", "K:Equip:2")), CardEdition.UNKNOWN_CODE, CardRarity.Special), f.ai());
        equipment.setGameTimestamp(f.game().getNextTimestamp());
        f.ai().getZone(ZoneType.Battlefield).add(equipment);
        equipment.attachToEntity(source, null);
        f.combat().addAttacker(source, f.opponent());
        final var before = PublicCombatSnapshot.capture(f.ai(), f.combat());
        Assert.assertTrue(before.departures().futureUnsupportedReasons().isEmpty(), before.departures().toString());
        Assert.assertFalse(before.preventionRules().isEmpty());
        Assert.assertFalse(before.protection().creatures().isEmpty());
        final var projection = CombatOutcomePredictor.predict(before, new CombatAssignment(before.attackersToDefenders(), Map.of()));
        final var values = CombatPlanningPreparation.values(f.ai(), ValuationDecision.ATTACK, budget()).orElseThrow();
        final var next = CombatTurnProjectionBuilder.advance(before, projection, PublicCombatReadiness.capture(f.ai(), before), values).turn().orElseThrow();
        Assert.assertTrue(next.alternatives().preventionRules().isEmpty());
        Assert.assertTrue(next.alternatives().protection().creatures().isEmpty());
        Assert.assertTrue(next.values().permanents().containsKey(equipment.getId()));
        Assert.assertFalse(next.values().permanents().containsKey(source.getId()));
        Assert.assertTrue(equipment.isInPlay() && equipment.getAttachedTo() == source);
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
        final PaperCard land = new PaperCard(CardRules.fromScript(List.of("Name:Departure Library", "Types:Land")),
                CardEdition.UNKNOWN_CODE, CardRarity.Special);
        for (final Player player : game.getPlayers()) {
            for (int count = 0; count < 10; count++) { player.getZone(ZoneType.Library).add(Card.fromPaperCard(land, player)); }
        }
        return new Fixture(game, ai, opponent, combat);
    }

    private static CombatSearchBudget budget() { return new CombatSearchBudget(50000, 500, () -> 0L); }

    private static Card creature(final Player owner, final int power, final int toughness, final List<String> extra) {
        final List<String> script = new ArrayList<>(List.of("Name:Departure Fixture", "ManaCost:7", "Types:Creature Construct", "PT:" + power + "/" + toughness));
        script.addAll(extra);
        final var card = Card.fromPaperCard(new PaperCard(CardRules.fromScript(script), CardEdition.UNKNOWN_CODE, CardRarity.Special), owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());
        owner.getZone(ZoneType.Battlefield).add(card);
        card.setSickness(false);
        return card;
    }

    private static Card unearth(final Card card) {
        final var owner = card.getOwner();
        owner.getZone(ZoneType.Battlefield).remove(card);
        owner.getZone(ZoneType.Graveyard).add(card);
        final var ability = card.getSpellAbilities().stream().filter(sa -> sa.isKeyword(Keyword.UNEARTH)).findFirst().orElseThrow();
        ability.setActivatingPlayer(owner);
        AbilityUtils.resolve(ability);
        final var returned = owner.getGame().getCardState(card);
        Assert.assertTrue(returned.isInPlay() && returned.isUnearthed());
        return returned;
    }
}
