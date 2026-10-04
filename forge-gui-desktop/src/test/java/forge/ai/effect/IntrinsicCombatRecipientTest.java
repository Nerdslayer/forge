package forge.ai.effect;

import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.PlayerResourceValueEvaluator;
import forge.card.CardStateName;
import forge.model.FModel;

public class IntrinsicCombatRecipientTest extends AITest {
    private static AbilityOutcomeDescription draw(final String recipient, final AbilityOutcomeDescription next) {
        return new AbilityOutcomeDescription("draw", "Draw", Map.of("Defined", recipient, "NumCards", "2"), List.of(), next, "");
    }

    private static CardAbilityTraversal.AbilityDescription trigger(final String source, final String combat,
            final String target, final AbilityOutcomeDescription outcome) {
        return new CardAbilityTraversal.AbilityDescription("trigger", CardAbilityTraversal.Origin.TRIGGER,
                CardAbilityTraversal.Provenance.PRINTED, Map.of("Mode", "DamageDone", "ValidSource", source,
                        "CombatDamage", combat, "ValidTarget", target), outcome);
    }

    @Test
    public void combatPlayerBindingsRespectOwnerAndNewEventScopes() {
        final var nested = new AbilityOutcomeDescription("newEvent", "DelayedTrigger", Map.of(), List.of(),
                draw("TriggeredTarget", null), "");
        final var friendly = IntrinsicTriggerBindingNormalizer.normalize(trigger("Card.Self", "True", "Player",
                draw("You & TriggeredTarget", nested)));
        Assert.assertEquals(friendly.outcome().parameters().get("Defined"), "You & Opponent");
        Assert.assertEquals(friendly.outcome().next().next().parameters().get("Defined"), "TriggeredTarget");
        final var opposing = IntrinsicTriggerBindingNormalizer.normalize(trigger("Creature.OppCtrl", "True", "Player",
                draw("TriggeredTarget", null)));
        Assert.assertEquals(opposing.outcome().parameters().get("Defined"), "You");
        for (final var unsupported : List.of(trigger("Card.Self", "False", "Player", draw("TriggeredTarget", null)),
                trigger("Creature", "True", "Player", draw("TriggeredTarget", null)),
                trigger("Card.Self", "True", "Player,Battle", draw("TriggeredTarget", null)),
                trigger("Creature.OppCtrl", "True", "Opponent", draw("TriggeredTarget", null)))) {
            Assert.assertEquals(IntrinsicTriggerBindingNormalizer.normalize(unsupported).outcome().parameters().get("Defined"),
                    "TriggeredTarget");
        }
    }

    @Test
    public void fixedUnionDrawsUpdateBothHandsOnceAndFeedContinuations() {
        final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults());
        final var node = draw("You & Opponent & You", draw("Opponent", null));
        final var plan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>().evaluate(
                new OutcomeDescriptionCompiler<>(backend).compile(node), new IntrinsicDrawOutcomeBackend.State(0, 7));
        Assert.assertTrue(plan.complete());
        Assert.assertEquals(plan.state().controllerHand(), 2);
        Assert.assertEquals(plan.state().opponentHand(), 11);
        Assert.assertEquals(plan.value(), (double) PlayerResourceValueEvaluator.evaluateCardDraw(0, 2)
                - PlayerResourceValueEvaluator.evaluateCardDraw(7, 4));
        Assert.assertTrue(DrawOutcomeDescription.parseFixedRecipients("Draw", Map.of("Defined", "You & TriggeredTarget")).isEmpty());
        Assert.assertTrue(DrawOutcomeDescription.parseFixedRecipients("Draw", Map.of("Defined", "You & ")).isEmpty());
        Assert.assertTrue(DrawOutcomeDescription.parse("Draw", Map.of("Defined", "You & Opponent")).isEmpty());
    }

    @Test
    public void definitionCombatDrawAmountAndRecipientAreBothResolved() {
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        final var ability = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard("Xyris, the Writhing Storm"),
                CardStateName.Original).stream().filter(entry -> entry.path().endsWith("trigger:1")).findFirst().orElseThrow();
        Assert.assertEquals(ability.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(ability.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(ability.contribution().complete());
        Assert.assertTrue(ability.expectedOccurrences() > 0);
    }
}
