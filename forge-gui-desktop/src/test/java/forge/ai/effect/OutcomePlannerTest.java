package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

import org.testng.Assert;
import org.testng.annotations.Test;

/** Algebra regressions independent of card scripts and live game mutation. */
public class OutcomePlannerTest {
    private static Outcome<Integer> add(final int amount) {
        return new Outcome.Atomic<>(s -> new Outcome.Transition<>((double) amount, s + amount));
    }

    @Test
    public void choiceIncludesContinuationInsteadOfGreedyImmediateValue() {
        final Outcome<Integer> choice = new Outcome.Choice<>("mode", List.of(add(10), add(5)), 1, 1, false, true);
        final Outcome<Integer> tail = new Outcome.Atomic<>(s -> new Outcome.Transition<>(s == 5 ? 100 : 0, s));
        final OutcomePlan<Integer> result = new OutcomePlanner<Integer>().evaluate(
                new Outcome.Sequence<>(List.of(choice, tail)), 0);
        Assert.assertEquals(result.value(), 105.0);
        Assert.assertEquals(result.decisions().get(0).selections(), List.of(1));
    }

    @Test
    public void multipleModesAreEvaluatedTogether() {
        final Outcome<Integer> bonus = new Outcome.Atomic<>(s -> new Outcome.Transition<>(s == 5 ? 100 : 1, s));
        final OutcomePlan<Integer> result = new OutcomePlanner<Integer>().evaluate(new Outcome.Choice<>(
                "two", List.of(add(10), add(5), bonus), 2, 2, false, true), 0);
        Assert.assertEquals(result.value(), 105.0);
        Assert.assertEquals(result.decisions().get(0).selections(), List.of(1, 2));
    }

    @Test
    public void optionalRepeatedAndOpposingChoices() {
        Assert.assertEquals(new OutcomePlanner<Integer>().evaluate(new Outcome.Choice<>(
                "optional", List.of(add(-5)), 0, 1, false, true), 0).value(), 0.0);
        Assert.assertEquals(new OutcomePlanner<Integer>().evaluate(new Outcome.Choice<>(
                "repeat", List.of(add(5), add(3)), 2, 2, true, true), 0).value(), 10.0);
        Assert.assertEquals(new OutcomePlanner<Integer>().evaluate(new Outcome.Choice<>(
                "opponent", List.of(add(5), add(3)), 1, 1, false, false), 0).value(), 3.0);
    }

    @Test
    public void randomPreservesBranchStateAndSubsequentDecisions() {
        final Outcome<Integer> random = new Outcome.Random<>("roll", List.of(
                new Outcome.Weighted<>(add(1), 1), new Outcome.Weighted<>(add(3), 3)));
        final Outcome<Integer> tail = new Outcome.Atomic<>(s -> new Outcome.Transition<>((double) s * s, s));
        final OutcomePlan<Integer> result = new OutcomePlanner<Integer>().evaluate(
                new Outcome.Sequence<>(List.of(random, tail)), 0);
        Assert.assertEquals(result.value(), 9.5);
        Assert.assertEquals(result.branches().get(0).state(), Integer.valueOf(1));
        Assert.assertEquals(result.branches().get(1).state(), Integer.valueOf(3));
    }

    @Test
    public void decisionsBeforeRandomCannotSeeItsResult() {
        final Outcome<Integer> random = new Outcome.Random<>("roll", List.of(
                new Outcome.Weighted<>(new Outcome.Atomic<>(s -> new Outcome.Transition<>(s == 1 ? 10 : 0, s)), 1),
                new Outcome.Weighted<>(new Outcome.Atomic<>(s -> new Outcome.Transition<>(s == 2 ? 10 : 0, s)), 1)));
        final Outcome<Integer> choice = new Outcome.Target<>("target", s -> List.of(1, 2),
                (s, selected) -> selected, random, true);
        Assert.assertEquals(new OutcomePlanner<Integer>().evaluate(choice, 0).value(), 5.0);
    }

    @Test
    public void sharedAndIndependentBindingsProduceDifferentPlans() {
        final Outcome<Map<String, String>> draw = new Outcome.Atomic<>(s ->
                new Outcome.Transition<>("self".equals(s.get("draw")) ? 60 : -60, s));
        final Outcome<Map<String, String>> sacrificeShared = new Outcome.Atomic<>(s ->
                new Outcome.Transition<>("self".equals(s.get("draw")) ? -100 : 100, s));
        final Outcome<Map<String, String>> sacrificeIndependent = new Outcome.Atomic<>(s ->
                new Outcome.Transition<>("self".equals(s.get("sacrifice")) ? -100 : 100, s));
        final Outcome<Map<String, String>> shared = bind("draw",
                new Outcome.Sequence<>(List.of(draw, sacrificeShared)));
        final Outcome<Map<String, String>> independent = bind("draw", bind("sacrifice",
                new Outcome.Sequence<>(List.of(draw, sacrificeIndependent))));
        final OutcomePlanner<Map<String, String>> planner = new OutcomePlanner<>();
        Assert.assertEquals(planner.evaluate(shared, Map.of()).value(), 40.0);
        Assert.assertEquals(planner.evaluate(independent, Map.of()).value(), 160.0);
    }

    private static Outcome<Map<String, String>> bind(final String slot, final Outcome<Map<String, String>> child) {
        return new Outcome.Target<>(slot, s -> List.of("self", "opponent"), (s, selected) -> {
            final Map<String, String> next = new HashMap<>(s);
            next.put(slot, selected);
            return Map.copyOf(next);
        }, child, true);
    }

    @Test
    public void budgetExhaustionIsExplicitAndDoesNotReturnBiasedBest() {
        final OutcomePlan<Integer> result = new OutcomePlanner<Integer>(2).evaluate(new Outcome.Choice<>(
                "modes", List.of(add(5), add(100)), 1, 1, false, true), 0);
        Assert.assertFalse(result.supported());
        Assert.assertTrue(result.reason().contains("budget"));
    }
}
