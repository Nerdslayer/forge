package forge.ai.effect;

import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;
import forge.game.card.Card;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;

public class IntrinsicSurvivalEstimatorTest extends AITest {
    @Test
    public void beginningCombatUsesPlayerScopeAndSkipsAlreadyPassedFlashCombat() {
        final var source = new PermanentProfile(true, PermanentKind.CREATURE, true, 3, 3, Set.of());
        for (final var scope : IntrinsicScheduledTrigger.PlayerScope.values()) {
            final var trigger = new IntrinsicScheduledTrigger(IntrinsicScheduledTrigger.Schedule.BEGIN_COMBAT, scope);
            for (final var timing : EntryTiming.values()) {
                final var estimate = IntrinsicScheduledTriggerEstimator.estimate(trigger, source,
                        IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults(), timing);
                Assert.assertTrue(estimate.expectedOccurrences() > 0);
                for (final var opportunity : estimate.opportunities()) {
                    final var checkpoint = opportunity.checkpoint();
                    Assert.assertTrue(checkpoint.isTurnEnd());
                    if (scope != IntrinsicScheduledTrigger.PlayerScope.EACH_PLAYER) {
                        Assert.assertEquals(checkpoint.isControllerTurn(timing),
                                scope == IntrinsicScheduledTrigger.PlayerScope.CONTROLLER);
                    }
                    if (timing == EntryTiming.FLASH_LATE_TURN) {
                        Assert.assertNotEquals(checkpoint, SurvivalCheckpoint.END_OF_FIRST_TURN);
                    }
                }
                if (timing == EntryTiming.NORMAL_SPEED
                        && scope != IntrinsicScheduledTrigger.PlayerScope.OPPONENT) {
                    Assert.assertEquals(estimate.opportunities().get(0).checkpoint(),
                            SurvivalCheckpoint.END_OF_FIRST_TURN);
                }
            }
        }
    }

    @Test
    public void beginningCombatTraversalReusesTokenAndCounterOutcomes() {
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(),
                IntrinsicEvaluationSettings.defaults());
        for (final String name : java.util.List.of("Goblin Rabblemaster", "Siege Veteran")) {
            final var entry = evaluator.evaluateDefinition(forge.model.FModel.getMagicDb()
                    .getCommonCards().getCard(name), forge.card.CardStateName.Original).stream()
                    .filter(value -> value.path().endsWith("trigger:0")).findFirst().orElseThrow();
            Assert.assertEquals(entry.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED, name);
            Assert.assertTrue(entry.contribution().value() > 0, name);
            Assert.assertTrue(entry.expectedOccurrences() > 0, name);
        }
    }

    @Test
    public void selfDeathIsBoundedAndGameEndDoesNotProduceDeath() {
        final var reference = IntrinsicReferenceModel.defaults();
        final var availability = new java.util.EnumMap<PermanentKind, WeightedDistribution<Boolean>>(PermanentKind.class);
        final var events = new java.util.EnumMap<IntrinsicReferenceModel.EventType, WeightedDistribution<Double>>(
                IntrinsicReferenceModel.EventType.class);
        for (final var kind : PermanentKind.values()) {
            if (reference.targetAvailability(kind) != null) { availability.put(kind, reference.targetAvailability(kind)); }
        }
        for (final var type : IntrinsicReferenceModel.EventType.values()) {
            if (reference.eventRates(type) != null) { events.put(type, reference.eventRates(type)); }
        }
        final var gameEndOnly = new IntrinsicReferenceModel(reference.lifeTotals(), reference.handSizes(), reference.availableMana(),
                reference.friendlyCreatureCounts(), reference.opposingCreatureCounts(), reference.creatureProfiles(), reference.permanentProfiles(),
                availability, events, java.util.Map.of(PermanentKind.PERMANENT, new IntrinsicReferenceModel.SurvivalProfile(0, 0, 0)), .80);
        final var creature = new PermanentProfile(true, PermanentKind.CREATURE, true, 2, 2, Set.of());
        final var estimator = new PermanentSurvivalEstimator(gameEndOnly);
        Assert.assertEquals(estimator.expectedSelfDeathOccurrences(creature, EntryTiming.NORMAL_SPEED, 6), 0.0);
        Assert.assertTrue(estimator.estimate(creature, EntryTiming.NORMAL_SPEED)
                .probability(SurvivalCheckpoint.END_OF_THIRD_TURN) < .01);

        final var ordinary = new PermanentSurvivalEstimator(reference);
        final double death = ordinary.expectedSelfDeathOccurrences(creature, EntryTiming.NORMAL_SPEED, 6);
        Assert.assertTrue(death > 0 && death < 1);
        Assert.assertTrue(death < 1 - ordinary.probabilityAtTurnStart(creature, EntryTiming.NORMAL_SPEED, 7));
        final var protectedCreature = new PermanentProfile(true, PermanentKind.CREATURE, true, 2, 2, Set.of("Hexproof", "Indestructible"));
        Assert.assertTrue(ordinary.expectedSelfDeathOccurrences(protectedCreature, EntryTiming.NORMAL_SPEED, 6) < death);
        Assert.assertEquals(ordinary.expectedSelfDeathOccurrences(PermanentProfile.absent(), EntryTiming.NORMAL_SPEED, 6), 0.0);
    }

    @Test
    public void auraIncludesSurvivalOfDefaultAttachedCreature() {
        final PermanentSurvivalEstimator estimator = new PermanentSurvivalEstimator();
        final PermanentProfile aura = new PermanentProfile(true, PermanentKind.AURA,
                true, 0, 0, Set.of());
        final PermanentProfile creature = new PermanentProfile(true, PermanentKind.CREATURE,
                true, 3, 3, Set.of());

        final double auraSurvival = estimator.estimate(aura, EntryTiming.NORMAL_SPEED)
                .probability(SurvivalCheckpoint.END_OF_FIRST_TURN);
        final double creatureSurvival = estimator.estimate(creature, EntryTiming.NORMAL_SPEED)
                .probability(SurvivalCheckpoint.END_OF_FIRST_TURN);

        Assert.assertTrue(auraSurvival < creatureSurvival);
    }

    @Test
    public void flashEntryImprovesEarlySurvival() {
        final PermanentProfile creature = new PermanentProfile(true, PermanentKind.CREATURE,
                true, 3, 3, Set.of());
        final PermanentSurvivalEstimator estimator = new PermanentSurvivalEstimator();

        final PermanentSurvivalEstimate normal = estimator.estimate(creature,
                EntryTiming.NORMAL_SPEED);
        final PermanentSurvivalEstimate flash = estimator.estimate(creature,
                EntryTiming.FLASH_LATE_TURN);

        Assert.assertTrue(flash.probability(SurvivalCheckpoint.END_OF_FIRST_TURN)
                > normal.probability(SurvivalCheckpoint.END_OF_FIRST_TURN));
        Assert.assertTrue(normal.probability(SurvivalCheckpoint.END_OF_FIRST_TURN)
                >= normal.probability(SurvivalCheckpoint.START_OF_SECOND_TURN));
        Assert.assertTrue(normal.probability(SurvivalCheckpoint.START_OF_SECOND_TURN)
                >= normal.probability(SurvivalCheckpoint.END_OF_SECOND_TURN));
    }

    @Test
    public void extendedTurnProjectionMatchesExistingCheckpoints() {
        final PermanentProfile creature = new PermanentProfile(true, PermanentKind.CREATURE,
                true, 0, 1, Set.of());
        final PermanentSurvivalEstimator estimator = new PermanentSurvivalEstimator();
        for (final EntryTiming timing : EntryTiming.values()) {
            final PermanentSurvivalEstimate checkpoints = estimator.estimate(creature, timing);
            for (final SurvivalCheckpoint checkpoint : SurvivalCheckpoint.values()) {
                if (checkpoint.isTurnStart()) {
                    Assert.assertEquals(estimator.probabilityAtTurnStart(creature, timing,
                            checkpoint.turnNumber()), checkpoints.probability(checkpoint),
                            .0000001, timing + " " + checkpoint);
                }
            }
            Assert.assertTrue(estimator.probabilityAtTurnStart(creature, timing, 13)
                    < estimator.probabilityAtTurnStart(creature, timing, 6));
        }
    }

    @Test
    public void protectionAndBasicLandImproveSurvival() {
        final PermanentProfile exposed = new PermanentProfile(true, PermanentKind.CREATURE,
                true, 2, 2, Set.of());
        final PermanentProfile protectedCreature = new PermanentProfile(true, PermanentKind.CREATURE,
                true, 2, 2, Set.of("Hexproof", "Indestructible"));
        final PermanentProfile nonbasic = new PermanentProfile(true, PermanentKind.LAND,
                true, 0, 0, Set.of(), false);
        final PermanentProfile basic = new PermanentProfile(true, PermanentKind.LAND,
                true, 0, 0, Set.of(), true);
        final PermanentSurvivalEstimator estimator = new PermanentSurvivalEstimator();

        Assert.assertTrue(estimator.estimate(protectedCreature, EntryTiming.NORMAL_SPEED)
                .probability(SurvivalCheckpoint.END_OF_THIRD_TURN)
                > estimator.estimate(exposed, EntryTiming.NORMAL_SPEED)
                .probability(SurvivalCheckpoint.END_OF_THIRD_TURN));
        Assert.assertTrue(estimator.estimate(basic, EntryTiming.NORMAL_SPEED)
                .probability(SurvivalCheckpoint.END_OF_THIRD_TURN)
                > estimator.estimate(nonbasic, EntryTiming.NORMAL_SPEED)
                .probability(SurvivalCheckpoint.END_OF_THIRD_TURN));
    }

    @Test
    public void toughnessAffectsDamageRemovalAndStrikeKeywordsDoNotDiffer() {
        final PermanentSurvivalEstimator estimator = new PermanentSurvivalEstimator();
        final PermanentProfile fragile = new PermanentProfile(true, PermanentKind.CREATURE,
                true, 3, 2, Set.of());
        final PermanentProfile durable = new PermanentProfile(true, PermanentKind.CREATURE,
                true, 3, 6, Set.of());
        final PermanentProfile firstStrike = new PermanentProfile(true, PermanentKind.CREATURE,
                true, 3, 3, Set.of("First strike"));
        final PermanentProfile doubleStrike = new PermanentProfile(true, PermanentKind.CREATURE,
                true, 3, 3, Set.of("Double strike"));

        Assert.assertTrue(estimator.estimate(durable, EntryTiming.NORMAL_SPEED)
                .probability(SurvivalCheckpoint.END_OF_THIRD_TURN)
                > estimator.estimate(fragile, EntryTiming.NORMAL_SPEED)
                .probability(SurvivalCheckpoint.END_OF_THIRD_TURN));
        Assert.assertEquals(estimator.estimate(firstStrike, EntryTiming.NORMAL_SPEED),
                estimator.estimate(doubleStrike, EntryTiming.NORMAL_SPEED));
    }

    @Test
    public void scheduledOccurrencesUseMatchingPlayerTurnsAndSurvival() {
        final PermanentProfile creature = new PermanentProfile(true, PermanentKind.CREATURE,
                true, 3, 3, Set.of());
        final IntrinsicScheduledTrigger yourEndStep = new IntrinsicScheduledTrigger(
                IntrinsicScheduledTrigger.Schedule.END_STEP,
                IntrinsicScheduledTrigger.PlayerScope.CONTROLLER);
        final IntrinsicScheduledTrigger eachUpkeep = new IntrinsicScheduledTrigger(
                IntrinsicScheduledTrigger.Schedule.UPKEEP,
                IntrinsicScheduledTrigger.PlayerScope.EACH_PLAYER);

        final IntrinsicScheduledTriggerEstimate endEstimate =
                IntrinsicScheduledTriggerEstimator.estimate(yourEndStep, creature,
                        IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults(),
                        EntryTiming.NORMAL_SPEED);
        final IntrinsicScheduledTriggerEstimate upkeepEstimate =
                IntrinsicScheduledTriggerEstimator.estimate(eachUpkeep, creature,
                        IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults(),
                        EntryTiming.NORMAL_SPEED);

        Assert.assertEquals(endEstimate.opportunitiesEvaluated(), 3);
        Assert.assertEquals(upkeepEstimate.opportunitiesEvaluated(), 3);
        Assert.assertTrue(endEstimate.expectedOccurrences() > 0);
        Assert.assertTrue(endEstimate.expectedOccurrences() < 2);
    }

    @Test
    public void scheduledControllerUpkeepUsesControllerTurnDiscounts() {
        final PermanentProfile artifact = new PermanentProfile(true, PermanentKind.ARTIFACT,
                true, 0, 0, Set.of());
        final IntrinsicScheduledTrigger yourUpkeep = new IntrinsicScheduledTrigger(
                IntrinsicScheduledTrigger.Schedule.UPKEEP,
                IntrinsicScheduledTrigger.PlayerScope.CONTROLLER);

        final IntrinsicScheduledTriggerEstimate estimate =
                IntrinsicScheduledTriggerEstimator.estimate(yourUpkeep, artifact,
                        IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults(),
                        EntryTiming.NORMAL_SPEED);

        Assert.assertEquals(estimate.opportunitiesEvaluated(), 2);
        Assert.assertEquals(estimate.opportunities().get(0).checkpoint(),
                SurvivalCheckpoint.START_OF_THIRD_TURN);
        Assert.assertEquals(estimate.opportunities().get(1).checkpoint(),
                SurvivalCheckpoint.START_OF_FIFTH_TURN);
        Assert.assertEquals(estimate.opportunities().get(0).horizonDiscount(), .90, .0000001);
        Assert.assertEquals(estimate.opportunities().get(1).horizonDiscount(), .90 * .90, .0000001);

        final double expected = estimate.opportunities().stream()
                .mapToDouble(IntrinsicScheduledTriggerEstimate.Opportunity::expectedContribution)
                .sum();
        Assert.assertEquals(estimate.expectedOccurrences(), expected, .0000001);
    }

    @Test
    public void scheduledTriggerAdapterSupportsSimpleForgeTimingForms() {
        final forge.game.Game game = initAndCreateGame();
        final Card card = addCard("Grizzly Bears", game.getPlayers().get(0));
        final Trigger upkeep = TriggerHandler.parseTrigger(
                "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You", card, false);
        final Trigger endStep = TriggerHandler.parseTrigger(
                "Mode$ Phase | Phase$ End of Turn | ValidPlayer$ Opponent", card, false);

        Assert.assertEquals(IntrinsicScheduledTriggerAdapter.describe(upkeep).orElseThrow(),
                new IntrinsicScheduledTrigger(IntrinsicScheduledTrigger.Schedule.UPKEEP,
                        IntrinsicScheduledTrigger.PlayerScope.CONTROLLER));
        Assert.assertEquals(IntrinsicScheduledTriggerAdapter.describe(endStep).orElseThrow(),
                new IntrinsicScheduledTrigger(IntrinsicScheduledTrigger.Schedule.END_STEP,
                        IntrinsicScheduledTrigger.PlayerScope.OPPONENT));
        for (final String player : java.util.List.of("You", "Opponent", "Any")) {
            final Trigger combat = TriggerHandler.parseTrigger(
                    "Mode$ Phase | Phase$ BeginCombat | ValidPlayer$ " + player, card, false);
            final var description = IntrinsicScheduledTriggerAdapter.describe(combat).orElseThrow();
            Assert.assertEquals(description.schedule(), IntrinsicScheduledTrigger.Schedule.BEGIN_COMBAT);
            Assert.assertEquals(description.playerScope(), "You".equals(player)
                    ? IntrinsicScheduledTrigger.PlayerScope.CONTROLLER : "Opponent".equals(player)
                    ? IntrinsicScheduledTrigger.PlayerScope.OPPONENT : IntrinsicScheduledTrigger.PlayerScope.EACH_PLAYER);
        }
        Assert.assertTrue(ScheduledTriggerParser.parse(java.util.Map.of("Mode", "Phase", "Phase", "Main1")).isEmpty());
    }
}
