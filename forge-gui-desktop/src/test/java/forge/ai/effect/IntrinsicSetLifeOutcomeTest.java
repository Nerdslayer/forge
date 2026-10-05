package forge.ai.effect;

import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.effect.IntrinsicDrawOutcomeBackend.State;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;
import forge.card.CardStateName;
import forge.model.FModel;

/** Setting life reuses nonlinear gain/loss utility, current-state projection and optional targets. */
public class IntrinsicSetLifeOutcomeTest extends AITest {
    private static final IntrinsicEvaluationSettings SETTINGS = IntrinsicEvaluationSettings.defaults();

    private static AbilityOutcomeDescription setLife(final String player, final int amount) {
        return new AbilityOutcomeDescription("set-life", "SetLife", Map.of("Defined", player,
                "LifeAmount", Integer.toString(amount)), List.of(), null, "");
    }

    private static OutcomePlan<State> evaluate(final AbilityOutcomeDescription node, final State state) {
        return new OutcomePlanner<State>().evaluate(new OutcomeDescriptionCompiler<>(new IntrinsicDrawOutcomeBackend(SETTINGS))
                .compile(node), state);
    }

    @Test
    public void fixedLifeTotalsReuseTheBeforeAfterMetricIncludingTerminalValues() {
        final var utility = new IntrinsicOutcomeEvaluator(SETTINGS);
        for (final boolean friendly : List.of(true, false)) {
            for (final int before : List.of(1, 5, 10, 20)) {
                for (final int after : List.of(0, 1, 10, 30)) {
                    final var state = new State(3, 3).withLife(friendly, before);
                    final var plan = evaluate(setLife(friendly ? "You" : "Opponent", after), state);
                    Assert.assertTrue(plan.complete());
                    final int expected = after < before ? utility.evaluateLifeLoss(before, before - after, friendly)
                            : utility.evaluateLifeGain(before, after - before, friendly);
                    Assert.assertEquals(plan.value(), (double) expected, 1e-9);
                    Assert.assertEquals(friendly ? plan.state().controllerLife() : plan.state().opponentLife(), after);
                }
            }
        }
    }

    @Test
    public void optionalTargetCanDeclineAndDoesNotSkipIndependentFollowups() {
        final var node = new AbilityOutcomeDescription("optional", "SetLife", Map.of("ValidTgts", "Player.Opponent",
                "TargetMin", "0", "TargetMax", "1", "LifeAmount", "10"), List.of(), null, "");
        final var initial = new State(3, 3).withLife(false, 5);
        final var declined = evaluate(node, initial);
        Assert.assertTrue(declined.complete());
        Assert.assertEquals(declined.value(), 0.0);
        Assert.assertEquals(declined.state(), initial);
        final var draw = new AbilityOutcomeDescription("draw", "Draw", Map.of("NumCards", "1"), List.of(), null, "");
        final var chain = new AbilityOutcomeDescription(node.path(), node.api(), node.parameters(), List.of(), draw, "");
        final var followed = evaluate(chain, initial);
        Assert.assertTrue(followed.complete());
        Assert.assertEquals(followed.state().controllerHand(), 4);
        Assert.assertEquals(followed.state().opponentLife(), 5);
        Assert.assertTrue(followed.value() > 0);
        final var accepted = evaluate(node, initial.withLife(false, 20));
        Assert.assertTrue(accepted.value() > 0);
        Assert.assertEquals(accepted.state().opponentLife(), 10);
    }

    @Test
    public void fixedPlayerSetsAndTargetedChoicesUseTheirOwnCurrentLifeTotals() {
        final var initial = new State(3, 3).withLife(true, 5).withLife(false, 20);
        final var all = evaluate(setLife("Player", 10), initial);
        Assert.assertTrue(all.complete());
        Assert.assertEquals(all.state().controllerLife(), 10);
        Assert.assertEquals(all.state().opponentLife(), 10);
        final var utility = new IntrinsicOutcomeEvaluator(SETTINGS);
        Assert.assertEquals(all.value(), (double) utility.evaluateLifeGain(5, 5, true) + utility.evaluateLifeLoss(20, 10, false), 1e-9);
        final var target = new AbilityOutcomeDescription("target", "SetLife", Map.of("ValidTgts", "Player", "LifeAmount", "10"), List.of(), null, "");
        Assert.assertEquals(evaluate(target, initial).value(), (double) Math.max(utility.evaluateLifeGain(5, 5, true),
                utility.evaluateLifeLoss(20, 10, false)), 1e-9);
    }

    @Test
    public void startingLifeAliasesResolveInTheEqualStartingLifeReferenceButUnknownSemanticsDoNot() {
        final var model = IntrinsicReferenceModel.defaults().withQuantities(IntrinsicReferenceQuantities.defaults()
                .with(IntrinsicReferenceQuantities.Quantity.STARTING_LIFE,
                        WeightedDistribution.of(new WeightedValue<>(21, 1))));
        Assert.assertEquals(IntrinsicQuantityResolver.resolve("TargetedPlayer$StartingLife/HalfDown", Map.of(),
                model, PermanentProfile.absent()).orElseThrow().values().entries().get(0).value().intValue(), 10);
        final var evaluator = new IntrinsicAbilityEvaluator(model, SETTINGS);
        for (final String name : List.of("Torgaar, Famine Incarnate", "Magister Sphinx")) {
            final var trigger = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard(name), CardStateName.Original)
                    .stream().filter(value -> value.path().endsWith("trigger:0")).findFirst().orElseThrow();
            Assert.assertEquals(trigger.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED, name);
            Assert.assertTrue(trigger.contribution().value() > 0, name);
        }
        for (final var extra : List.of(Map.of("Redistribute", "True"), Map.of("LifeAmount", "UnknownLife"),
                Map.of("LifeAmount", "-1"), Map.of("ValidTgts", "Player", "TargetMax", "2"))) {
            final var parameters = new java.util.HashMap<>(setLife("Opponent", 10).parameters());
            parameters.putAll(extra);
            Assert.assertFalse(evaluate(new AbilityOutcomeDescription("unsupported", "SetLife", parameters, List.of(), null, ""),
                    new State(3, 3)).complete());
        }
    }
}
