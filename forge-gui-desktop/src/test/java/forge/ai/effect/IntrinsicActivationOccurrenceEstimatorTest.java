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
        final double expected = 1
                + survival.probability(SurvivalCheckpoint.START_OF_THIRD_TURN)
                        * AbilityOccurrenceEstimator.turnDiscount(2)
                + survival.probability(SurvivalCheckpoint.START_OF_FIFTH_TURN)
                        * AbilityOccurrenceEstimator.turnDiscount(3);

        Assert.assertEquals(result.futureTurnsEvaluated(), 2);
        Assert.assertEquals(result.currentTurnUses(), 1.0);
        Assert.assertEquals(result.expectedOccurrences(), expected, .0000001);
        Assert.assertTrue(result.expectedOccurrences() < 3);
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
        final double expected = survival.probability(SurvivalCheckpoint.START_OF_THIRD_TURN)
                * AbilityOccurrenceEstimator.turnDiscount(2)
                + survival.probability(SurvivalCheckpoint.START_OF_FIFTH_TURN)
                        * AbilityOccurrenceEstimator.turnDiscount(3);

        Assert.assertEquals(result.currentTurnUses(), 0.0);
        Assert.assertEquals(result.futureTurnsEvaluated(), 2);
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
        Assert.assertEquals(result.futureTurnsEvaluated(), 3);
        Assert.assertTrue(result.expectedOccurrences() <= 3);
    }
}
