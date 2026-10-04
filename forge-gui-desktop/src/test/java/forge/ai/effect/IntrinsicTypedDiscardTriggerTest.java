package forge.ai.effect;

import java.util.Map;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.card.CardStateName;
import forge.model.FModel;

/** Card-category eligibility and outcome traversal remain separate from actual hand knowledge. */
public class IntrinsicTypedDiscardTriggerTest extends AITest {
    @Test
    public void discardCategoriesPartitionTheReferenceAndRespectCustomComposition() {
        final var model = IntrinsicReferenceModel.defaults();
        final Map<String, Double> filters = Map.of("Creature.OppOwn", .30, "Land.OppOwn", .40,
                "Card.nonLand+nonCreature+OppOwn", .30);
        double total = 0;
        for (final var filter : filters.entrySet()) {
            final var parameters = Map.of("Mode", "Discarded", "ValidCard", filter.getKey());
            final var event = IntrinsicEventTriggerAdapter.describe(parameters, model).orElseThrow();
            Assert.assertTrue(IntrinsicEventTriggerAdapter.supportsIntrinsicParameters(parameters));
            Assert.assertEquals(event.eventType(), IntrinsicReferenceModel.EventType.CARD_DISCARDED);
            Assert.assertEquals(event.turnScope(), IntrinsicEventTrigger.TurnScope.ANY_TURN);
            Assert.assertEquals(event.occurrenceMultiplier(), filter.getValue(), 1e-9);
            total += event.occurrenceMultiplier();
        }
        Assert.assertEquals(total, 1.0, 1e-9);
        final var library = new IntrinsicLibraryReference(Map.of("Land", .20, "Creature", .60,
                "Artifact", .05, "Enchantment", .05, "Planeswalker", 0.0, "Instant", .05, "Sorcery", .05), .50);
        Assert.assertEquals(IntrinsicEventTriggerAdapter.describe(Map.of("Mode", "Discarded",
                "ValidCard", "Creature.YouOwn"), model.withLibrary(library)).orElseThrow().occurrenceMultiplier(), .60);
        final var explicitPlayer = Map.of("Mode", "Discarded", "ValidCard", "Creature", "ValidPlayer", "You");
        Assert.assertTrue(IntrinsicEventTriggerAdapter.supportsIntrinsicParameters(explicitPlayer));
    }

    @Test
    public void ambiguousFiltersCapsAndMultitypeIntersectionsRemainUnresolved() {
        for (final String filter : List.of("Creature", "Creature.OppOwn+YouOwn", "Creature.Elf+OppOwn",
                "Artifact.Creature+OppOwn", "Creature.OppOwn+Red", "Creature,Land.OppOwn")) {
            Assert.assertTrue(IntrinsicTypedDiscardTriggerAdapter.describe(Map.of("Mode", "Discarded",
                    "ValidCard", filter), IntrinsicLibraryReference.defaults()).isEmpty(), filter);
        }
        for (final var extra : List.of(Map.of("ActivationLimit", "1"), Map.of("ValidPlayer", "You"),
                Map.of("ValidCause", "Spell"), Map.of("Mode", "DiscardedAll"))) {
            final var parameters = new java.util.HashMap<>(Map.of("Mode", "Discarded", "ValidCard", "Creature.OppOwn"));
            parameters.putAll(extra);
            Assert.assertTrue(IntrinsicTypedDiscardTriggerAdapter.describe(parameters, IntrinsicLibraryReference.defaults()).isEmpty());
        }
    }

    @Test
    public void typedDiscardTriggersReuseTokenManaAndDrawOutcomes() {
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(),
                IntrinsicEvaluationSettings.defaults());
        final var entries = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard("Waste Not"),
                CardStateName.Original).stream().filter(entry -> entry.path().contains("/trigger:")).toList();
        Assert.assertEquals(entries.size(), 3);
        for (final var entry : entries) {
            Assert.assertEquals(entry.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED, entry.path());
            Assert.assertTrue(entry.contribution().value() > 0, entry.path());
            Assert.assertTrue(entry.expectedOccurrences() > 0, entry.path());
        }
    }
}
