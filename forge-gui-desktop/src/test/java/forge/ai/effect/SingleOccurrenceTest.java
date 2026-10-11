package forge.ai.effect;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.CardStateName;
import forge.item.PaperCard;

/** Source-terminal triggers use a likelihood, not repeated imaginary deaths. */
public class SingleOccurrenceTest extends AITest {
    @Test
    public void oneEventRateIsNotCertaintyAndHighRatesStillProduceAtMostOneEvent() {
        Assert.assertEquals(AbilityOccurrenceEstimator.atLeastOneProbability(0), 0.0);
        Assert.assertEquals(AbilityOccurrenceEstimator.atLeastOneProbability(1), 1 - Math.exp(-1), 1e-9);
        final var model = IntrinsicReferenceModel.defaults();
        final var source = model.permanentProfiles().entries().stream().map(WeightedValue::value)
                .filter(p -> p.present() && p.kind() == IntrinsicReferenceModel.PermanentKind.CREATURE).findFirst().orElseThrow();
        final var trigger = new IntrinsicEventTrigger(IntrinsicReferenceModel.EventType.BECAME_TARGET,
                IntrinsicEventTrigger.TurnScope.ANY_TURN, false, 100);
        final var repeated = IntrinsicEventTriggerEstimator.estimate(trigger, source, model,
                IntrinsicEvaluationSettings.defaults(), EntryTiming.NORMAL_SPEED);
        final var once = IntrinsicEventTriggerEstimator.estimate(trigger, source, model,
                IntrinsicEvaluationSettings.defaults(), EntryTiming.NORMAL_SPEED, AbilityOccurrenceMode.SINGLE_OCCURRENCE);
        Assert.assertTrue(repeated.expectedOccurrences() > 1);
        Assert.assertTrue(once.expectedOccurrences() > 0 && once.expectedOccurrences() <= 1);
        Assert.assertTrue(once.expectedOccurrences() < repeated.expectedOccurrences());
    }

    @Test
    public void cardTraversalUsesOneShotLikelihoodForConsumingOutcomesButKeepsRecurringBenefits() {
        initAndCreateGame();
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        final var repeated = evaluate(evaluator, "Recurring", "DB$ Draw | Defined$ You | NumCards$ 1");
        final var once = evaluate(evaluator, "One Shot", "DB$ Draw | Defined$ You | NumCards$ 1 | SubAbility$ Sac");
        Assert.assertTrue(repeated.expectedOccurrences() > 1);
        Assert.assertTrue(once.expectedOccurrences() > 0 && once.expectedOccurrences() <= 1);
        final var expected = IntrinsicScheduledTriggerEstimator.estimate(new IntrinsicScheduledTrigger(
                IntrinsicScheduledTrigger.Schedule.UPKEEP, IntrinsicScheduledTrigger.PlayerScope.CONTROLLER),
                new IntrinsicReferenceModel.PermanentProfile(true, IntrinsicReferenceModel.PermanentKind.CREATURE,
                        true, 4, 5, java.util.Set.of(), false, 0), IntrinsicReferenceModel.defaults(),
                IntrinsicEvaluationSettings.defaults(), EntryTiming.NORMAL_SPEED, AbilityOccurrenceMode.SINGLE_OCCURRENCE);
        Assert.assertEquals(expected.opportunitiesEvaluated(), 1);
        Assert.assertEquals(once.expectedOccurrences(), expected.expectedOccurrences(), 1e-9);
    }

    private static IntrinsicAbilityEvaluator.AbilityValue evaluate(final IntrinsicAbilityEvaluator evaluator,
            final String name, final String outcome) {
        final var rules = CardRules.fromScript(List.of("Name:" + name, "ManaCost:3 U", "Types:Creature Human", "PT:4/5",
                "T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | Execute$ Effect | TriggerZones$ Battlefield",
                "SVar:Effect:" + outcome, "SVar:Sac:DB$ Sacrifice"));
        return evaluator.evaluateDefinition(new PaperCard(rules, CardEdition.UNKNOWN_CODE, CardRarity.Special),
                CardStateName.Original).get(0);
    }
}
