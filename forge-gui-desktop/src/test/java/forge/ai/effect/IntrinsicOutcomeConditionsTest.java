package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.PlayerResourceValueEvaluator;
import forge.ai.effect.IntrinsicDrawOutcomeBackend.State;

/** Conditional steps use their resolution state, not a pre-sequence condition snapshot. */
public class IntrinsicOutcomeConditionsTest {
    private static AbilityOutcomeDescription node(final String api, final Map<String, String> parameters,
            final AbilityOutcomeDescription next) {
        return new AbilityOutcomeDescription(api, api, parameters, List.of(), next, "");
    }

    private static OutcomePlan<State> evaluate(final AbilityOutcomeDescription node, final State state) {
        return new OutcomePlanner<State>().evaluate(new OutcomeDescriptionCompiler<>(
                new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults())).compile(node), state);
    }

    @Test
    public void precedingDrawChangesConditionAndFailedStepStillResolvesItsContinuation() {
        final var tail = node("Mana", Map.of("Produced", "Any", "Amount", "1"), null);
        final var conditional = node("Draw", Map.of("NumCards", "1", "ConditionPresent", "Card.YouOwn",
                "ConditionZone", "Hand", "ConditionCompare", "EQ0"), tail);
        final var chain = node("Draw", Map.of("NumCards", "1"), conditional);
        final var plan = evaluate(chain, new State(0, 3));
        Assert.assertTrue(plan.complete());
        Assert.assertEquals(plan.state().controllerHand(), 1);
        Assert.assertEquals(plan.state().controllerMana(), 4);
        Assert.assertEquals(plan.value(), (double) PlayerResourceValueEvaluator.evaluateCardDraw(0, 1)
                + PlayerResourceValueEvaluator.evaluateMana(1));
        Assert.assertEquals(evaluate(conditional, new State(0, 3)).state().controllerHand(), 1);
        Assert.assertEquals(evaluate(conditional, new State(3, 0)).state().controllerHand(), 3);
    }

    @Test
    public void tokenCreationChangesLaterPopulationCondition() {
        final var conditional = node("Draw", Map.of("NumCards", "1", "ConditionPresent", "Creature.YouCtrl",
                "ConditionCompare", "GE1"), null);
        final var chain = node("Token", Map.of("TokenScript", "probe", "TokenAmount", "1"), conditional);
        final var profile = new IntrinsicReferenceModel.PermanentProfile(true, IntrinsicReferenceModel.PermanentKind.TOKEN,
                true, 1, 1, Set.of());
        final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults(),
                IntrinsicReferenceModel.PermanentProfile.absent(), script -> java.util.Optional.of(profile));
        final var state = new State(0, 0).withCreatureCount(true, 0);
        final var plan = new OutcomePlanner<State>().evaluate(new OutcomeDescriptionCompiler<>(backend).compile(chain), state);
        Assert.assertTrue(plan.complete());
        Assert.assertEquals(plan.state().controllerHand(), 1);
        Assert.assertEquals(plan.state().controllerCreatureCount(), 1);
        Assert.assertEquals(evaluate(conditional, state).value(), 0.0);
        Assert.assertTrue(backend.referenceDimensions(chain).contains(IntrinsicDrawOutcomeBackend.CONTROLLER_CREATURE_COUNT));
        Assert.assertTrue(backend.referenceDimensions(chain).contains(IntrinsicDrawOutcomeBackend.CONTROLLER_HAND));
    }

    @Test
    public void lifeConditionsSeeEarlierLifeChangesAndKeepPlayerScopesSeparate() {
        final var conditional = node("Draw", Map.of("NumCards", "1", "ConditionLifeTotal", "You",
                "ConditionLifeAmount", "GE5"), null);
        final var state = new State(0, 0).withLife(true, 4).withLife(false, 20);
        Assert.assertEquals(evaluate(conditional, state).state().controllerHand(), 0);
        final var chain = node("GainLife", Map.of("LifeAmount", "2", "Defined", "You"), conditional);
        final var result = evaluate(chain, state);
        Assert.assertTrue(result.complete());
        Assert.assertEquals(result.state().controllerLife(), 6);
        Assert.assertEquals(result.state().controllerHand(), 1);
        final var opposing = node("Draw", Map.of("NumCards", "1", "ConditionLifeTotal", "Opponent",
                "ConditionLifeAmount", "GE5"), null);
        Assert.assertEquals(evaluate(opposing, state).state().controllerHand(), 1);
        final var unknown = node("Draw", Map.of("NumCards", "1", "ConditionLifeTotal", "You",
                "ConditionLifeAmount", "GEUnknownVariable"), null);
        Assert.assertFalse(evaluate(unknown, state).complete());
    }

    @Test
    public void unknownSemanticsAreOnlySkippedWhenTheConditionIsKnownFalse() {
        final var tail = node("Mana", Map.of("Produced", "Any", "Amount", "1"), null);
        final var conditional = node("Unmodeled", Map.of("ConditionPresent", "Card.YouOwn",
                "ConditionZone", "Hand", "ConditionCompare", "EQ0"), tail);
        Assert.assertTrue(evaluate(conditional, new State(1, 1)).complete());
        Assert.assertEquals(evaluate(conditional, new State(1, 1)).value(), (double) PlayerResourceValueEvaluator.evaluateMana(1));
        Assert.assertFalse(evaluate(conditional, new State(0, 1)).complete());
        for (final var parameters : List.of(Map.of("NumCards", "1", "ConditionPresent", "Card.cmcLE3", "ConditionDefined", "Targeted"),
                Map.of("NumCards", "1", "ConditionCheckSVar", "MutableUnknown", "ConditionSVarCompare", "GE1"),
                Map.of("NumCards", "1", "ConditionCheckSVar", "0", "ConditionSVarCompare", "GE1",
                        "OrConditionCheckSVar", "1", "OrOtherConditionSVarCompare", "GE1"))) {
            Assert.assertFalse(evaluate(node("Draw", parameters, null), new State(1, 1)).complete());
        }
    }

    @Test
    public void immutableCostConditionsBindButMutableAliasesAreNotFrozen() {
        final var profile = IntrinsicReferenceModel.PermanentProfile.absent();
        final var model = IntrinsicReferenceModel.defaults();
        final var conditional = node("Draw", Map.of("NumCards", "1", "ConditionCheckSVar", "X",
                "ConditionSVarCompare", "GE2"), null);
        final var bound = IntrinsicOutcomeQuantityBinder.bind(conditional, Map.of("X", "Count$xPaid"), model, profile);
        Assert.assertTrue(bound.stream().allMatch(reference -> reference.value().parameters().get("ConditionCheckSVar").matches("\\d+")));
        final var mutable = IntrinsicOutcomeQuantityBinder.bind(node("Draw", Map.of("NumCards", "X",
                "ConditionCheckSVar", "X", "ConditionSVarCompare", "GE2"), null),
                Map.of("X", "Count$ValidHand Card.YouOwn"), model, profile);
        Assert.assertTrue(mutable.stream().allMatch(reference -> "X".equals(reference.value().parameters().get("ConditionCheckSVar"))));
    }
}
