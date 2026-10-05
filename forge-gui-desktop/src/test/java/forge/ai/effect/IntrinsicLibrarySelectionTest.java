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
    public void manaValueFiltersSeparateLandsAndDeduplicateOverlappingBranches() {
        final var library = IntrinsicLibraryReference.defaults();
        Assert.assertEquals(library.hitProbability("Card.cmcEQ0").orElseThrow(), .4 + .6 * .05, 1e-9);
        Assert.assertEquals(library.hitProbability("Card.cmcGE4").orElseThrow(), .6 * .23, 1e-9);
        Assert.assertEquals(library.hitProbability("Land.cmcGE1").orElseThrow(), 0.0);
        Assert.assertEquals(library.hitProbability("Card.cmcGE4,Creature").orElseThrow(), .30 + .30 * .23, 1e-9);
        Assert.assertEquals(library.hitProbability("Card.cmcGE4,Card.cmcGE4").orElseThrow(), .6 * .23, 1e-9);
        Assert.assertEquals(library.hitProbability("Card.cmcGT2+cmcLE3").orElseThrow(), .6 * .22, 1e-9);
        Assert.assertEquals(library.hitProbability("Card.cmcNE0").orElseThrow(), .6 * .95, 1e-9);
        for (final String unsupported : List.of("Card.cmcLEX", "Card.cmcLE999999999999", "Artifact.Creature.cmcGT999")) {
            Assert.assertTrue(library.hitProbability(unsupported).isEmpty(), unsupported);
        }
        Assert.assertTrue(evaluate(dig("Card.cmcLE3", "3", "1"), 3).complete());
        Assert.assertTrue(evaluate(new AbilityOutcomeDescription("tutor", "ChangeZone", Map.of("Origin", "Library",
                "Destination", "Hand", "ChangeType", "Card.nonLand+cmcLE3"), List.of(), null, ""), 3).complete());
    }

    @Test
    public void filterUnionsAvoidDoubleCountingAndRejectUnknownPredicates() {
        final var reference = IntrinsicLibraryReference.defaults();
        Assert.assertEquals(reference.hitProbability("Dinosaur,Land").orElseThrow(), .55, 1e-9);
        Assert.assertEquals(reference.hitProbability("Creature,Dinosaur,Creature.Elf").orElseThrow(), .30, 1e-9);
        Assert.assertEquals(reference.hitProbability("Land,Land").orElseThrow(), .40, 1e-9);
        Assert.assertEquals(reference.hitProbability("Enchantment,Aura").orElseThrow(), .06, 1e-9);
        Assert.assertTrue(reference.hitProbability("Creature.cmcLEX").isEmpty());
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
    public void wholeLibrarySearchKeepsFailureProbabilityAndCapsCardsFound() {
        final Map<String, Double> composition = Map.of("Land", .4, "Creature", .3, "Artifact", .06,
                "Enchantment", .06, "Planeswalker", .02, "Instant", .08, "Sorcery", .08);
        final var library = new IntrinsicLibraryReference(composition, .5, .6, .8,
                WeightedDistribution.of(new WeightedValue<>(0, .25), new WeightedValue<>(2, .75)));
        final var counts = library.searchCounts("Land", 1).orElseThrow();
        Assert.assertEquals(counts.entries().stream().filter(entry -> entry.value() == 1)
                .mapToDouble(WeightedValue::weight).sum(), .75 * .64, 1e-9);
        final var search = new AbilityOutcomeDescription("search", "ChangeZone",
                Map.of("Origin", "Library", "Destination", "Hand", "ChangeType", "Land", "ChangeNum", "1"),
                List.of(), null, "");
        final var evaluator = new IntrinsicOutcomeEvaluator();
        final var state = new IntrinsicDrawOutcomeBackend.State(2, 2);
        final var plan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>()
                .evaluate(IntrinsicLibrarySearchOutcome.parse(search, library).orElseThrow().outcome("search", evaluator), state);
        Assert.assertTrue(plan.complete());
        Assert.assertEquals(plan.value(), .75 * .64 * PlayerResourceValueEvaluator.evaluateCardDraw(2, 1), 1e-9);
        final var draw = new AbilityOutcomeDescription("after", "Draw", Map.of("Defined", "You", "NumCards", "1"),
                List.of(), null, "");
        final var chain = new AbilityOutcomeDescription(search.path(), search.api(), search.parameters(), List.of(), draw, "");
        final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults(),
                IntrinsicReferenceModel.PermanentProfile.absent(), script -> java.util.Optional.empty(), library);
        final var combined = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>()
                .evaluate(new OutcomeDescriptionCompiler<>(backend).compile(chain), state);
        Assert.assertTrue(combined.complete());
        Assert.assertEquals(combined.value(), .52 * PlayerResourceValueEvaluator.evaluateCardDraw(2, 1)
                + .48 * PlayerResourceValueEvaluator.evaluateCardDraw(2, 2), 1e-9);
        final var opposing = new AbilityOutcomeDescription("opposing", "ChangeZone", Map.of("Origin", "Library",
                "Destination", "Hand", "ChangeType", "Land", "DefinedPlayer", "Opponent"), List.of(), null, "");
        final var opponentPlan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>()
                .evaluate(new OutcomeDescriptionCompiler<>(backend).compile(opposing), state);
        Assert.assertEquals(opponentPlan.value(), -plan.value(), 1e-9);
        Assert.assertTrue(library.searchCounts("Creature.cmcLEX", 1).isEmpty());
        Assert.assertEquals(library.searchCounts("Card", 8).orElseThrow().entries().stream()
                .mapToDouble(entry -> entry.value() * entry.weight()).sum(), 1.5, 1e-9);
    }

    @Test
    public void alternativeSearchModesDoNotConsumeTheLibraryTwice() {
        final var artifact = new AbilityOutcomeDescription("artifact", "ChangeZone",
                Map.of("Origin", "Library", "Destination", "Hand", "ChangeType", "Artifact"), List.of(), null, "");
        final var creature = new AbilityOutcomeDescription("creature", "ChangeZone",
                Map.of("Origin", "Library", "Destination", "Hand", "ChangeType", "Creature"), List.of(), null, "");
        final var options = List.of(artifact, creature);
        final var chooseOne = new AbilityOutcomeDescription("one", "Charm", Map.of("CharmNum", "1"), options, null, "");
        Assert.assertTrue(evaluate(chooseOne, 2).complete());
        Assert.assertEquals(evaluate(chooseOne, 2).value(), Math.max(evaluate(artifact, 2).value(), evaluate(creature, 2).value()), 1e-9);
        for (final var repeated : List.of(
                new AbilityOutcomeDescription("two", "Charm", Map.of("CharmNum", "2"), options, null, ""),
                new AbilityOutcomeDescription("repeat", "Charm", Map.of("CharmNum", "2", "CanRepeatModes", "True"),
                        List.of(artifact), null, ""),
                new AbilityOutcomeDescription("after", "Charm", Map.of(), options, artifact, ""))) {
            Assert.assertFalse(evaluate(repeated, 2).complete());
        }
    }

    @Test
    public void primaryTypePredicatesShareEligibilityAcrossSearchAndGenericCardSubsets() {
        final var library = IntrinsicLibraryReference.defaults();
        Assert.assertEquals(library.hitProbability("Card.nonLand").orElseThrow(), .6, 1e-9);
        Assert.assertEquals(library.hitProbability("Card.nonCreature+nonLand").orElseThrow(), .3, 1e-9);
        Assert.assertEquals(library.hitProbability("Card.Creature,Card.Land").orElseThrow(), .7, 1e-9);
        Assert.assertEquals(library.hitProbability("Artifact,Creature,Artifact").orElseThrow(), .36, 1e-9);
        Assert.assertEquals(library.hitProbability("Creature.nonCreature").orElseThrow(), 0.0);
        Assert.assertTrue(library.hitProbability("Creature.Artifact").isEmpty(), "Secondary-type intersections are not known impossible");
        Assert.assertTrue(library.hitProbability("Card.nonLand+cmcLEX").isEmpty());
        Assert.assertTrue(library.hitProbability("Card.nonLand+YouOwn").isEmpty());
        final var search = new AbilityOutcomeDescription("search", "ChangeZone", Map.of("Origin", "Library",
                "Destination", "Hand", "ChangeType", "Card.nonCreature+nonLand"), List.of(), null, "");
        Assert.assertTrue(evaluate(search, 2).complete());
    }

    @Test
    public void unknownLibrarySemanticsStayUnsupportedAndDefinitionSupportIsStructural() {
        for (final var node : List.of(dig("Creature.cmcLEX", "7", "1"), dig("Card", "99", "All"),
                new AbilityOutcomeDescription("battlefield", "Dig", Map.of("DigNum", "5", "DestinationZone", "Battlefield"),
                        List.of(), null, ""))) {
            Assert.assertFalse(evaluate(node, 2).complete());
        }
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        for (final String name : List.of("Commune with Dinosaurs", "Adventurous Impulse", "Fabricate", "Demonic Tutor", "Eladamri's Call", "Once Upon a Time")) {
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
