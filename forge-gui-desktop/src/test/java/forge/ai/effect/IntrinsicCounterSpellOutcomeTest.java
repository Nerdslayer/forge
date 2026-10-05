package forge.ai.effect;

import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.PlayerResourceValueEvaluator;
import forge.card.CardStateName;
import forge.model.FModel;

/** Countering prevents an average spell outcome, never refunds its already-spent resources. */
public class IntrinsicCounterSpellOutcomeTest extends AITest {
    private static AbilityOutcomeDescription counter(final String targets) {
        return new AbilityOutcomeDescription("counter", "Counter",
                Map.of("TargetType", "Spell", "ValidTgts", targets), List.of(), null, "");
    }

    private static IntrinsicDrawOutcomeBackend backend() {
        return new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults(), IntrinsicReferenceModel.PermanentProfile.absent(),
                script -> java.util.Optional.empty(), IntrinsicLibraryReference.defaults(), null,
                WeightedDistribution.of(new WeightedValue<>(2, .25), new WeightedValue<>(4, .75)));
    }

    @Test
    public void averageSpellValueUsesSharedMetricAndPreservesResources() {
        final var state = new IntrinsicDrawOutcomeBackend.State(2, 5).withMana(true, 3).withMana(false, 1);
        final var plan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>()
                .evaluate(new OutcomeDescriptionCompiler<>(backend()).compile(counter("Card")), state);
        Assert.assertTrue(plan.complete());
        Assert.assertEquals(plan.value(), .25 * PlayerResourceValueEvaluator.evaluateAverageCardPlay(2)
                + .75 * PlayerResourceValueEvaluator.evaluateAverageCardPlay(4), 1e-9);
        Assert.assertEquals(plan.state(), state);
        final var own = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>()
                .evaluate(new OutcomeDescriptionCompiler<>(backend()).compile(counter("Card.YouCtrl")), state);
        Assert.assertTrue(own.complete());
        Assert.assertEquals(own.value(), -plan.value(), 1e-9);
        final var draw = new AbilityOutcomeDescription("draw", "Draw", Map.of("NumCards", "1"), List.of(), null, "");
        final var chain = new AbilityOutcomeDescription("counter", "Counter", counter("Card").parameters(), List.of(), draw, "");
        final var combined = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>()
                .evaluate(new OutcomeDescriptionCompiler<>(backend()).compile(chain), state);
        Assert.assertTrue(combined.complete());
        Assert.assertEquals(combined.value(), plan.value() + PlayerResourceValueEvaluator.evaluateCardDraw(2, 1), 1e-9);
    }

    @Test
    public void typedSpellAvailabilityExcludesLandsAndAvoidsUnionDoubleCounting() {
        final var library = IntrinsicLibraryReference.defaults();
        Assert.assertEquals(IntrinsicCounterSpellOutcome.parse(counter("Card"), library).orElseThrow().eligibleProbability(), 1.0, 1e-9);
        Assert.assertEquals(IntrinsicCounterSpellOutcome.parse(counter("Creature"), library).orElseThrow().eligibleProbability(), .5, 1e-9);
        Assert.assertEquals(IntrinsicCounterSpellOutcome.parse(counter("Card.nonCreature"), library).orElseThrow().eligibleProbability(), .5, 1e-9);
        Assert.assertEquals(IntrinsicCounterSpellOutcome.parse(counter("Instant,Sorcery,Instant"), library).orElseThrow().eligibleProbability(),
                .16 / .60, 1e-9);
        final var state = new IntrinsicDrawOutcomeBackend.State(3, 3);
        final var compiler = new OutcomeDescriptionCompiler<>(backend());
        final double unrestricted = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>().evaluate(compiler.compile(counter("Card")), state).value();
        final var restricted = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>().evaluate(compiler.compile(counter("Creature")), state);
        Assert.assertTrue(restricted.complete());
        Assert.assertEquals(restricted.value(), unrestricted * .5, 1e-9);
    }

    @Test
    public void unsupportedCounterSemanticsRemainUnresolved() {
        for (final var extra : List.of(Map.of("UnlessCost", "1"), Map.of("TargetType", "SpellAbility"),
                Map.of("RememberCountered", "True"), Map.of("Destination", "Hand"), Map.of("TargetMax", "2"))) {
            final var parameters = new java.util.HashMap<>(counter("Card").parameters());
            parameters.putAll(extra);
            Assert.assertFalse(IntrinsicCounterSpellOutcome.parse(new AbilityOutcomeDescription("unsupported", "Counter",
                    parameters, List.of(), null, ""), IntrinsicLibraryReference.defaults()).isPresent());
        }
        Assert.assertTrue(IntrinsicCounterSpellOutcome.parse(counter("Creature.cmcGEX"), IntrinsicLibraryReference.defaults()).isEmpty());
        final var first = new AbilityOutcomeDescription("first", "Counter", counter("Card").parameters(), List.of(), counter("Card"), "");
        Assert.assertFalse(new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>().evaluate(
                new OutcomeDescriptionCompiler<>(backend()).compile(first), new IntrinsicDrawOutcomeBackend.State(3, 3)).complete());
    }

    @Test
    public void mutuallyExclusiveCountersDoNotInventMultipleStackTargets() {
        final var options = List.of(counter("Creature"), counter("Card.nonCreature"));
        final var compiler = new OutcomeDescriptionCompiler<>(backend());
        final var state = new IntrinsicDrawOutcomeBackend.State(3, 3);
        for (final String api : List.of("Charm", "GenericChoice")) {
            final var one = new AbilityOutcomeDescription("one", api, Map.of(), options, null, "");
            final var plan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>().evaluate(compiler.compile(one), state);
            Assert.assertFalse(plan.complete(), "Modes require one shared spell sample before choosing");
            Assert.assertTrue(plan.reason().contains("shared spell context"));
            final String amount = "Charm".equals(api) ? "CharmNum" : "ChoiceAmount";
            final var two = new AbilityOutcomeDescription("two", api, Map.of(amount, "2"), options, null, "");
            Assert.assertFalse(new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>().evaluate(compiler.compile(two), state).complete());
            final var repeat = new AbilityOutcomeDescription("repeat", api,
                    Map.of(amount, "2", "CanRepeatModes", "True"), options, null, "");
            Assert.assertFalse(new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>().evaluate(compiler.compile(repeat), state).complete());
            final var followup = new AbilityOutcomeDescription("after-choice", api, Map.of(), options, counter("Card"), "");
            Assert.assertFalse(new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>().evaluate(compiler.compile(followup), state).complete());
        }
    }

    @Test
    public void definitionTraversalSupportsHardCountersButNotUnlessPayment() {
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        for (final String name : List.of("Counterspell", "Negate", "Essence Scatter", "Force of Will", "Flare of Denial", "Disdainful Stroke")) {
            final var value = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard(name), CardStateName.Original).get(0);
            Assert.assertEquals(value.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED, name);
            Assert.assertTrue(value.contribution().value() > 0, name);
        }
        final var daze = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard("Daze"), CardStateName.Original).get(0);
        Assert.assertEquals(daze.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.UNSUPPORTED);
    }

    @Test
    public void manaValueEligibilityAndPreventedValueUseTheSameSpellSample() {
        final var state = new IntrinsicDrawOutcomeBackend.State(2, 5);
        final var compiler = new OutcomeDescriptionCompiler<>(backend());
        final var planner = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>();
        final var high = planner.evaluate(compiler.compile(counter("Card.cmcGE4")), state);
        Assert.assertTrue(high.complete());
        Assert.assertEquals(high.value(), .75 * PlayerResourceValueEvaluator.evaluateAverageCardPlay(4), 1e-9);
        final var creature = planner.evaluate(compiler.compile(counter("Creature.OppCtrl+cmcGE4")), state);
        Assert.assertEquals(creature.value(), high.value() * .5, 1e-9);
        final var overlapping = planner.evaluate(compiler.compile(counter("Card.cmcGE4,Creature")), state);
        Assert.assertEquals(overlapping.value(), high.value() + .125 * PlayerResourceValueEvaluator.evaluateAverageCardPlay(2), 1e-9);
        final var impossible = planner.evaluate(compiler.compile(counter("Card.cmcLT0")), state);
        Assert.assertTrue(impossible.complete());
        Assert.assertEquals(impossible.value(), 0.0);
        final var draw = new AbilityOutcomeDescription("draw", "Draw", Map.of("NumCards", "1"), List.of(), null, "");
        final var sequence = new AbilityOutcomeDescription("restricted", "Counter", counter("Card.cmcGE4").parameters(), List.of(), draw, "");
        Assert.assertEquals(planner.evaluate(compiler.compile(sequence), state).value(), high.value()
                + .75 * PlayerResourceValueEvaluator.evaluateCardDraw(2, 1), 1e-9);
    }
}
