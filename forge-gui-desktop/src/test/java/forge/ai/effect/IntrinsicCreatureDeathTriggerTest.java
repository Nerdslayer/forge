package forge.ai.effect;

import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.effect.IntrinsicReferenceQuantities.Quantity;
import forge.card.CardStateName;
import forge.model.FModel;

/** Typed recurring deaths must not silently absorb the source's own departure event. */
public class IntrinsicCreatureDeathTriggerTest extends AITest {
    private static Map<String, String> parameters(final String validity) {
        return Map.of("Mode", "ChangesZone", "Origin", "Battlefield", "Destination", "Graveyard", "ValidCard", validity);
    }

    @Test
    public void tribalAndTokenDeathEligibilityReuseIndependentConfigurablePriors() {
        final var model = IntrinsicReferenceModel.defaults();
        final var soldier = IntrinsicEventTriggerAdapter.describe(parameters("Soldier.Other+!token+YouCtrl"), model).orElseThrow();
        Assert.assertEquals(soldier.eventType(), IntrinsicReferenceModel.EventType.CREATURE_DIED);
        Assert.assertEquals(soldier.turnScope(), IntrinsicEventTrigger.TurnScope.ANY_TURN);
        Assert.assertEquals(soldier.occurrenceMultiplier(), .5 * .65, 1e-9);
        final var token = IntrinsicEventTriggerAdapter.describe(parameters("Creature.Other+token+OppCtrl"), model).orElseThrow();
        Assert.assertEquals(token.occurrenceMultiplier(), .35, 1e-9);
        final var nonToken = IntrinsicEventTriggerAdapter.describe(parameters("Creature.Other+!token+OppCtrl"), model).orElseThrow();
        Assert.assertEquals(token.occurrenceMultiplier() + nonToken.occurrenceMultiplier(), 1.0, 1e-9);
        final var allTokens = model.withQuantities(model.quantities().with(Quantity.DYING_CREATURE_IS_TOKEN,
                WeightedDistribution.of(new WeightedValue<>(1, 1))));
        Assert.assertEquals(IntrinsicEventTriggerAdapter.describe(parameters("Soldier.Other+!token+YouCtrl"),
                allTokens).orElseThrow().occurrenceMultiplier(), 0.0);
        Assert.assertEquals(IntrinsicEventTriggerAdapter.describe(parameters("Creature.Other+token+OppCtrl"),
                allTokens).orElseThrow().occurrenceMultiplier(), 1.0);
    }

    @Test
    public void uncertainSelfEventsAndRicherPredicatesRemainUnresolved() {
        for (final String validity : List.of("Card.Self", "Soldier.YouCtrl", "Creature.Soldier+YouCtrl",
                "Soldier.Other+Goblin", "Creature.Other+HasCounters", "Creature.Other+YouCtrl+OppCtrl",
                "Creature.Other+token+!token", "Artifact.Other")) {
            Assert.assertTrue(IntrinsicCreatureDeathTriggerAdapter.describe(parameters(validity),
                    IntrinsicReferenceModel.defaults()).isEmpty(), validity);
        }
        for (final var extra : List.of(Map.of("ActivationLimit", "1"), Map.of("Mode", "ChangesZoneAll"),
                Map.of("Destination", "Exile"), Map.of("Origin", "Any"))) {
            final var event = new java.util.HashMap<>(parameters("Soldier.Other"));
            event.putAll(extra);
            Assert.assertTrue(IntrinsicCreatureDeathTriggerAdapter.describe(event, IntrinsicReferenceModel.defaults()).isEmpty());
        }
        Assert.expectThrows(IllegalArgumentException.class, () -> IntrinsicReferenceQuantities.defaults()
                .with(Quantity.DYING_CREATURE_IS_TOKEN, WeightedDistribution.of(new WeightedValue<>(2, 1))));
    }

    @Test
    public void printedTypeExclusionDoesNotEraseADeathOfAMatchingSource() {
        final var slimefoot = CardAbilityTraversal.definitionState(FModel.getMagicDb().getCommonCards().getCard("Slimefoot, the Stowaway"),
                CardStateName.Original);
        final var veteran = CardAbilityTraversal.definitionState(FModel.getMagicDb().getCommonCards().getCard("Siege Veteran"),
                CardStateName.Original);
        final var description = new CardAbilityTraversal.AbilityDescription("test", CardAbilityTraversal.Origin.TRIGGER,
                CardAbilityTraversal.Provenance.PRINTED, parameters("Soldier.YouCtrl"), null);
        Assert.assertSame(IntrinsicCreatureDeathTriggerAdapter.excludeNonmatchingSource(description, veteran.getType()), description);
        Assert.assertEquals(IntrinsicCreatureDeathTriggerAdapter.excludeNonmatchingSource(description, slimefoot.getType())
                .parameters().get("ValidCard"), "Soldier.YouCtrl+Other");
    }

    @Test
    public void typedDeathsReachTokenAndCombinedDamageLifeOutcomes() {
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        for (final var example : Map.of("Siege Veteran", "trigger:1", "Slimefoot, the Stowaway", "trigger:0").entrySet()) {
            final var entry = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard(example.getKey()),
                    CardStateName.Original).stream().filter(value -> value.path().endsWith(example.getValue())).findFirst().orElseThrow();
            Assert.assertEquals(entry.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED, example.getKey());
            Assert.assertTrue(entry.contribution().value() > 0, example.getKey());
            Assert.assertTrue(entry.expectedOccurrences() > 0, example.getKey());
        }
    }
}
