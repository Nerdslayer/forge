package forge.ai.effect;

import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.PlayerResourceValueEvaluator;
import forge.card.CardStateName;
import forge.model.FModel;

/** Hit probabilities, nonlinear hand value, continuations and rejection of unknown semantics. */
public class IntrinsicLibrarySelectionTest extends AITest {
    private static AbilityOutcomeDescription dig(final String validity, final String amount, final String selection) {
        return new AbilityOutcomeDescription("dig", "Dig", Map.of("DigNum", amount, "ChangeNum", selection,
                "ChangeValid", validity, "Defined", "You"), List.of(), null, "");
    }

    private static OutcomePlan<IntrinsicDrawOutcomeBackend.State> evaluate(final AbilityOutcomeDescription node, final int hand) {
        final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults());
        return new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>().evaluate(
                new OutcomeDescriptionCompiler<>(backend).compile(node), new IntrinsicDrawOutcomeBackend.State(hand, hand));
    }

    @Test
    public void cappedHitDistributionIncludesFailuresAndHasTheExpectedMean() {
        final var one = IntrinsicLibraryReference.selectedCounts(5, 1, .4);
        Assert.assertEquals(one.entries().get(0).weight(), Math.pow(.6, 5), 1e-9);
        Assert.assertEquals(one.entries().get(1).weight(), 1 - Math.pow(.6, 5), 1e-9);
        final var all = IntrinsicLibraryReference.selectedCounts(5, 5, .4);
        Assert.assertEquals(all.entries().stream().mapToDouble(entry -> entry.value() * entry.weight()).sum(), 2, 1e-9);
        Assert.assertEquals(all.entries().stream().mapToDouble(WeightedValue::weight).sum(), 1, 1e-9);
        Assert.assertEquals(IntrinsicLibraryReference.selectedCounts(5, 0, .4).entries().get(0).value().intValue(), 0);
    }

    @Test
    public void filterUnionsAvoidDoubleCountingAndRejectUnknownPredicates() {
        final var reference = IntrinsicLibraryReference.defaults();
        Assert.assertEquals(reference.hitProbability("Dinosaur,Land").orElseThrow(), .55, 1e-9);
        Assert.assertEquals(reference.hitProbability("Creature,Dinosaur,Creature.Elf").orElseThrow(), .30, 1e-9);
        Assert.assertEquals(reference.hitProbability("Land,Land").orElseThrow(), .40, 1e-9);
        Assert.assertEquals(reference.hitProbability("Enchantment,Aura").orElseThrow(), .06, 1e-9);
        Assert.assertTrue(reference.hitProbability("Creature.cmcLE3").isEmpty());
        Assert.assertTrue(reference.hitProbability("Card.UnknownPredicate").isEmpty());
        final var model = IntrinsicReferenceModel.defaults();
        Assert.assertSame(model.withQuantities(model.quantities()).library(), model.library());
        Assert.assertSame(model.withQuantityBindings(Map.of("otherFriendlyCreatures", 2)).library(), model.library());
    }

    @Test
    public void selectionReusesDrawValueAndEvaluatesLaterEffectsInEachHitBranch() {
        final var node = dig("Land", "2", "1");
        final var plan = evaluate(node, 2);
        Assert.assertTrue(plan.complete());
        Assert.assertEquals(plan.value(), PlayerResourceValueEvaluator.evaluateCardDraw(2, 1) * .64, 1e-9);
        Assert.assertTrue(evaluate(node, 0).value() > evaluate(node, 7).value());
        final var tail = new AbilityOutcomeDescription("after", "Draw", Map.of("Defined", "You", "NumCards", "1"),
                List.of(), null, "");
        final var chain = new AbilityOutcomeDescription(node.path(), node.api(), node.parameters(), node.choices(), tail, "");
        Assert.assertEquals(evaluate(chain, 2).value(), .36 * PlayerResourceValueEvaluator.evaluateCardDraw(2, 1)
                + .64 * PlayerResourceValueEvaluator.evaluateCardDraw(2, 2), 1e-9);
        Assert.assertEquals(evaluate(dig("Card", "3", "All"), 2).value(),
                (double) PlayerResourceValueEvaluator.evaluateCardDraw(2, 3));
        Assert.assertEquals(evaluate(dig("Creature", "0", "1"), 2).value(), 0.0);
    }

    @Test
    public void filteringIsDiminishingQualityValueAndNeverChangesHandSize() {
        final var library = IntrinsicLibraryReference.defaults();
        Assert.assertEquals(library.filteringCardFraction(0), 0.0);
        Assert.assertEquals(library.filteringCardFraction(1), .192, 1e-9);
        Assert.assertTrue(library.filteringCardFraction(2) > library.filteringCardFraction(1));
        Assert.assertTrue(library.filteringCardFraction(3) - library.filteringCardFraction(2)
                < library.filteringCardFraction(2) - library.filteringCardFraction(1));
        for (final String api : List.of("Scry", "Surveil")) {
            final String amount = "Scry".equals(api) ? "ScryNum" : "Amount";
            final var node = new AbilityOutcomeDescription("filter", api, Map.of("Defined", "You", amount, "2"),
                    List.of(), null, "");
            final var plan = evaluate(node, 2);
            Assert.assertTrue(plan.complete());
            Assert.assertEquals(plan.state().controllerHand(), 2);
            Assert.assertEquals(plan.value(), PlayerResourceValueEvaluator.evaluateCardDraw(2, 1)
                    * library.filteringCardFraction(2), 1e-9);
            Assert.assertTrue(plan.value() < PlayerResourceValueEvaluator.evaluateCardDraw(2, 1));
            final var opposing = new AbilityOutcomeDescription("opposing", api,
                    Map.of("Defined", "Opponent", amount, "2", "Optional", "True"), List.of(), null, "");
            Assert.assertEquals(evaluate(opposing, 2).value(), -plan.value(), 1e-9);
            final var unknown = new AbilityOutcomeDescription("unknown", api,
                    Map.of(amount, "2", "RememberLookedAt", "True"), List.of(), null, "");
            Assert.assertFalse(evaluate(unknown, 2).complete());
        }
    }

    @Test
    public void unknownLibrarySemanticsStayUnsupportedAndDefinitionSupportIsStructural() {
        for (final var node : List.of(dig("Creature.cmcLE3", "7", "1"), dig("Card", "99", "All"),
                new AbilityOutcomeDescription("battlefield", "Dig", Map.of("DigNum", "5", "DestinationZone", "Battlefield"),
                        List.of(), null, ""))) {
            Assert.assertFalse(evaluate(node, 2).complete());
        }
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        for (final String name : List.of("Commune with Dinosaurs", "Adventurous Impulse")) {
            final var spell = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard(name),
                    CardStateName.Original).get(0);
            Assert.assertEquals(spell.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED, name);
            Assert.assertTrue(spell.contribution().value() > 0, name);
        }
        for (final String name : List.of("Prognostic Sphinx", "Hedge Maze", "Temple of Mystery")) {
            final var trigger = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard(name),
                    CardStateName.Original).stream().filter(value -> value.path().endsWith("trigger:0")).findFirst().orElseThrow();
            Assert.assertEquals(trigger.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED, name);
            Assert.assertTrue(trigger.contribution().value() > 0, name);
        }
    }
}
