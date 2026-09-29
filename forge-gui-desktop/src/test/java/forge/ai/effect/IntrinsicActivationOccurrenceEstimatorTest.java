package forge.ai.effect;

import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;

/** Regression coverage for the bounded intrinsic activation horizon. */
public class IntrinsicActivationOccurrenceEstimatorTest {
    @Test
    public void tapAbilityUsesOnlyControllerTurns() {
        final IntrinsicReferenceModel model = IntrinsicReferenceModel.defaults();
        final IntrinsicEvaluationSettings settings = IntrinsicEvaluationSettings.defaults();
        final PermanentProfile land = new PermanentProfile(true, PermanentKind.LAND, true,
                0, 0, Set.of());

        final IntrinsicActivationOccurrenceEstimate result =
                IntrinsicActivationOccurrenceEstimator.estimate(0, true, land, model, settings,
                        EntryTiming.NORMAL_SPEED);
        final PermanentSurvivalEstimate survival = new PermanentSurvivalEstimator(model)
                .estimate(land, EntryTiming.NORMAL_SPEED);
        final double expected = 1 + futureControllerUses(survival,
                SurvivalCheckpoint.START_OF_THIRD_TURN,
                SurvivalCheckpoint.START_OF_FIFTH_TURN, 2, settings.futureActivationControllerTurns());

        Assert.assertEquals(result.futureTurnsEvaluated(), 6);
        Assert.assertEquals(result.currentTurnUses(), 1.0);
        Assert.assertEquals(result.expectedOccurrences(), expected, .0000001);
        Assert.assertTrue(result.expectedOccurrences() < 7);
    }

    @Test
    public void creatureTapAbilitySkipsCurrentTurnForSummoningSickness() {
        final IntrinsicReferenceModel model = IntrinsicReferenceModel.defaults();
        final IntrinsicEvaluationSettings settings = IntrinsicEvaluationSettings.defaults();
        final PermanentProfile creature = new PermanentProfile(true, PermanentKind.CREATURE, true,
                2, 2, Set.of());

        final IntrinsicActivationOccurrenceEstimate result =
                IntrinsicActivationOccurrenceEstimator.estimate(0, true, creature, model, settings,
                        EntryTiming.NORMAL_SPEED);
        final PermanentSurvivalEstimate survival = new PermanentSurvivalEstimator(model)
                .estimate(creature, EntryTiming.NORMAL_SPEED);
        final double expected = futureControllerUses(survival,
                SurvivalCheckpoint.START_OF_THIRD_TURN,
                SurvivalCheckpoint.START_OF_FIFTH_TURN, 2, settings.futureActivationControllerTurns());

        Assert.assertEquals(result.currentTurnUses(), 0.0);
        Assert.assertEquals(result.futureTurnsEvaluated(), 6);
        Assert.assertEquals(result.expectedOccurrences(), expected, .0000001);
    }

    @Test
    public void flashTapAbilityWaitsForTheNextControllerTurn() {
        final IntrinsicReferenceModel model = IntrinsicReferenceModel.defaults();
        final IntrinsicEvaluationSettings settings = IntrinsicEvaluationSettings.defaults();
        final PermanentProfile land = new PermanentProfile(true, PermanentKind.LAND, true,
                0, 0, Set.of());

        final IntrinsicActivationOccurrenceEstimate result =
                IntrinsicActivationOccurrenceEstimator.estimate(0, true, land, model, settings,
                        EntryTiming.FLASH_LATE_TURN);

        Assert.assertEquals(result.currentTurnUses(), 0.0);
        final PermanentSurvivalEstimate survival = new PermanentSurvivalEstimator(model)
                .estimate(land, EntryTiming.FLASH_LATE_TURN);
        final double expected = futureControllerUses(survival,
                SurvivalCheckpoint.START_OF_SECOND_TURN,
                SurvivalCheckpoint.START_OF_FOURTH_TURN, 1, settings.futureActivationControllerTurns());
        Assert.assertEquals(result.futureTurnsEvaluated(), 6);
        Assert.assertEquals(result.expectedOccurrences(), expected, .0000001);
        Assert.assertTrue(result.expectedOccurrences() <= 6);
    }

    private static double futureControllerUses(final PermanentSurvivalEstimate survival,
            final SurvivalCheckpoint first, final SurvivalCheckpoint second,
            final int firstControllerTurn, final int futureTurns) {
        final double firstSurvival = survival.probability(first);
        final double survivalPerControllerTurn = survival.probability(second) / firstSurvival;
        double expected = 0;
        for (int i = 0; i < futureTurns; i++) {
            expected += firstSurvival * Math.pow(survivalPerControllerTurn, i)
                    * Math.pow(.90, firstControllerTurn + i - 1);
        }
        return expected;
    }
}
