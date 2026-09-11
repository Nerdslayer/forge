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

        Assert.assertEquals(endEstimate.opportunitiesEvaluated(), 2);
        Assert.assertEquals(upkeepEstimate.opportunitiesEvaluated(), 3);
        Assert.assertTrue(endEstimate.expectedOccurrences() > 0);
        Assert.assertTrue(endEstimate.expectedOccurrences() < 2);
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
    }
}
