package forge.ai.effect;

import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;

/** Remembered scope, continuation order and unsupported-loop coverage. */
public class IntrinsicRepeatedOutcomeTest extends AITest {
    private static AbilityOutcomeDescription draw(final String path, final String recipient) {
        return new AbilityOutcomeDescription(path, "Draw", Map.of("Defined", recipient, "NumCards", "1"),
                List.of(), null, "");
    }

    private static AbilityOutcomeDescription repeat(final String players, final AbilityOutcomeDescription child,
            final AbilityOutcomeDescription continuation) {
        return new AbilityOutcomeDescription("repeat", "RepeatEach", Map.of("DB", "RepeatEach",
                "RepeatPlayers", players, "RepeatSubAbility", "DrawRepeated", "ChangeZoneTable", "True"),
                List.of(child), continuation, "");
    }

    @Test
    public void opponentIterationBindsItsRecipientWithoutLeakingIntoTheContinuation() {
        final var repeatedChild = new AbilityOutcomeDescription("child", "Draw", Map.of("Defined", "RememberedPlayer",
                "NumCards", "1"), List.of(), draw("child-next", "You"), "");
        final var normalized = IntrinsicRepeatedOutcomeNormalizer.normalize(
                repeat("Opponent", repeatedChild, draw("outer-next", "RememberedPlayer")));
        Assert.assertEquals(normalized.api(), "Draw");
        Assert.assertEquals(normalized.parameters().get("Defined"), "Opponent");
        Assert.assertEquals(normalized.next().parameters().get("Defined"), "You");
        Assert.assertEquals(normalized.next().next().parameters().get("Defined"), "RememberedPlayer");
        final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults());
        final var plan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>().evaluate(
                new OutcomeDescriptionCompiler<>(backend).compile(IntrinsicRepeatedOutcomeNormalizer.normalize(
                        repeat("Opponent", draw("child", "RememberedPlayer"), draw("after", "You")))),
                new IntrinsicDrawOutcomeBackend.State(2, 2));
        Assert.assertEquals(plan.completeness(), OutcomePlan.Completeness.COMPLETE);
        Assert.assertEquals(plan.state().controllerHand(), 3);
        Assert.assertEquals(plan.state().opponentHand(), 3);
        Assert.assertEquals(plan.value(), 0.0);
    }

    @Test
    public void unmodeledLoopsAndUnsupportedChildrenStayUnresolved() {
        final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults());
        for (final var node : List.of(repeat("Player", draw("child", "RememberedPlayer"), null),
                repeat("Opponent", AbilityOutcomeDescription.unresolved("child", "Unknown action"), null))) {
            final var plan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>().evaluate(
                    new OutcomeDescriptionCompiler<>(backend).compile(IntrinsicRepeatedOutcomeNormalizer.normalize(node)),
                    new IntrinsicDrawOutcomeBackend.State(2, 2));
            Assert.assertFalse(plan.complete());
        }
        final var delayed = new AbilityOutcomeDescription("delay", "DelayedTrigger",
                Map.of("Defined", "RememberedPlayer"), List.of(), null, "");
        Assert.assertEquals(IntrinsicRepeatedOutcomeNormalizer.normalize(repeat("Opponent", delayed, null))
                .parameters().get("Defined"), "RememberedPlayer");
    }
}
