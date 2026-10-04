package forge.ai.effect;

import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.card.CardStateName;
import forge.model.FModel;

public class IntrinsicSpellManaValueTest extends AITest {
    private static Map<String, String> trigger(final String validity) {
        return Map.of("Mode", "SpellCast", "ValidCard", validity, "ValidActivatingPlayer", "You");
    }

    @Test
    public void literalBoundsThinCastEventsUsingTheConditionalCurveDistribution() {
        for (final var example : Map.of("Card.cmcGE4", .25, "Creature+cmcGE4", .25,
                "Card.cmcLE2", .53, "Card.cmcGT2", .47, "Card.cmcEQ0", .03,
                "Card.cmcNE4", .88, "Card.cmcLT1", .03, "Card.nonCreature+cmcGE4", .25).entrySet()) {
            Assert.assertTrue(IntrinsicSpellCastTriggerAdapter.supports(trigger(example.getKey())));
            Assert.assertEquals(IntrinsicSpellCastTriggerAdapter.describe(trigger(example.getKey())).orElseThrow()
                    .occurrenceMultiplier(), example.getValue(), 1e-9);
        }
        Assert.assertEquals(IntrinsicSpellCastTriggerAdapter.describe(trigger("Card")).orElseThrow().occurrenceMultiplier(), 1.0);
        Assert.assertEquals(IntrinsicSpellCastTriggerAdapter.describe(trigger("Card.cmcGE99")).orElseThrow().occurrenceMultiplier(), 0.0);
    }

    @Test
    public void customCurveIsUsedByTheSharedAdapterAndUnknownFiltersRemainUnsupported() {
        final var original = IntrinsicReferenceModel.defaults();
        final var model = original.withQuantities(original.quantities().with(IntrinsicReferenceQuantities.Quantity.CAST_SPELL_MANA_VALUE,
                WeightedDistribution.of(new WeightedValue<>(0, .5), new WeightedValue<>(6, .5))));
        Assert.assertEquals(IntrinsicEventTriggerAdapter.describe(trigger("Card.cmcGE4"), model,
                IntrinsicReferenceModel.PermanentProfile.absent()).orElseThrow().occurrenceMultiplier(), .5);
        final var opposing = new java.util.LinkedHashMap<>(trigger("Card.cmcGE4"));
        opposing.put("ValidActivatingPlayer", "Player.Opponent");
        Assert.assertEquals(IntrinsicSpellCastTriggerAdapter.describe(opposing, model).orElseThrow().turnScope(),
                IntrinsicEventTrigger.TurnScope.OPPONENT_TURN);
        for (final String filter : List.of("Card.cmcGEX", "Card.cmcGE4+Red", "Creature.cmcGE4,Instant",
                "Land.cmcGE4", "Card.cmcGE9999999999999999", "Card.cmcGE-1")) {
            Assert.assertFalse(IntrinsicSpellCastTriggerAdapter.supports(trigger(filter)), filter);
        }
        final var history = new java.util.LinkedHashMap<>(trigger("Card.cmcGE4"));
        history.put("ActivatorThisTurnCast", "EQ1");
        Assert.assertFalse(IntrinsicSpellCastTriggerAdapter.supports(history));
    }

    @Test
    public void expensiveSpellCounterTriggerReachesExistingOutcomeValuation() {
        final var value = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard("Ascendant Packleader"), CardStateName.Original)
                .stream().filter(entry -> entry.path().endsWith("trigger:0")).findFirst().orElseThrow();
        Assert.assertEquals(value.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(value.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(value.contribution().complete());
        Assert.assertTrue(value.contribution().value() > 0);
    }
}
