package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.card.CardStateName;
import forge.model.FModel;

public class IntrinsicEvolveTriggerTest extends AITest {
    private static Map<String, String> parameters() {
        return Map.of("Mode", "ChangesZone", "Destination", "Battlefield", "ValidCard", "Creature.YouCtrl+Other",
                "Condition", "Evolve");
    }

    private static IntrinsicReferenceModel.PermanentProfile source(final int power, final int toughness) {
        return new IntrinsicReferenceModel.PermanentProfile(true, IntrinsicReferenceModel.PermanentKind.CREATURE,
                true, power, toughness, Set.of());
    }

    @Test
    public void eligibilityUsesEitherStrictlyGreaterCharacteristicAndExcludesAbsentProfiles() {
        final var model = IntrinsicReferenceModel.defaults();
        final var host = source(2, 3);
        Assert.assertFalse(IntrinsicCreatureEntryTriggerAdapter.matchesEventCondition(parameters(), host, 2, 3));
        Assert.assertTrue(IntrinsicCreatureEntryTriggerAdapter.matchesEventCondition(parameters(), host, 3, 1));
        Assert.assertTrue(IntrinsicCreatureEntryTriggerAdapter.matchesEventCondition(parameters(), host, 1, 4));
        final double present = model.creatureProfiles().entries().stream().filter(entry -> entry.value().present())
                .mapToDouble(WeightedValue::weight).sum();
        final double eligible = model.creatureProfiles().entries().stream().filter(entry -> entry.value().present()
                && (entry.value().power() > 2 || entry.value().toughness() > 3)).mapToDouble(WeightedValue::weight).sum();
        final var event = IntrinsicCreatureEntryTriggerAdapter.describe(parameters(), model, host).orElseThrow();
        Assert.assertEquals(event.occurrenceMultiplier(), eligible / present, 1e-9);
        final var selfInclusive = new java.util.LinkedHashMap<>(parameters());
        selfInclusive.put("ValidCard", "Creature.YouCtrl");
        Assert.assertEquals(IntrinsicCreatureEntryTriggerAdapter.describe(selfInclusive, model, host).orElseThrow(), event);
        final var capped = new java.util.LinkedHashMap<>(parameters());
        capped.put("ActivationLimit", "1");
        final double share = eligible / present;
        final var rates = model.eventRates(IntrinsicReferenceModel.EventType.CREATURE_ENTERED).entries();
        final double any = rates.stream().mapToDouble(entry -> Math.min(1, entry.value()) * entry.weight()).sum();
        final double anyEligible = rates.stream().mapToDouble(entry -> (1 - Math.pow(1 - share, entry.value())) * entry.weight()).sum();
        Assert.assertEquals(IntrinsicCreatureEntryTriggerAdapter.describe(capped, model, host).orElseThrow()
                .occurrenceMultiplier(), anyEligible / any, 1e-9);
        Assert.assertEquals(IntrinsicCreatureEntryTriggerAdapter.describe(parameters(), model, source(100, 100))
                .orElseThrow().occurrenceMultiplier(), 0.0);
    }

    @Test
    public void watchedOutcomeQuantitiesUseOnlyQualifyingEntryProfilesAndUnknownConditionsReject() {
        final var model = IntrinsicReferenceModel.defaults();
        final var outcome = new AbilityOutcomeDescription("counter", "PutCounter", Map.of("Defined", "Self",
                "CounterType", "P1P1", "CounterNum", "X"), List.of(), null, "");
        final var ability = new CardAbilityTraversal.AbilityDescription("entry", CardAbilityTraversal.Origin.TRIGGER,
                CardAbilityTraversal.Provenance.PRINTED, parameters(), outcome);
        final var cases = IntrinsicWatchedCreatureBinding.cases(ability, model, source(3, 3)).orElseThrow();
        Assert.assertEquals(cases.stream().mapToDouble(WeightedValue::weight).sum(), 1.0, 1e-9);
        Assert.assertTrue(cases.stream().allMatch(entry -> entry.value().creature().power() > 3
                || entry.value().creature().toughness() > 3));
        Assert.assertTrue(IntrinsicWatchedCreatureBinding.cases(ability, model, source(100, 100)).orElseThrow().isEmpty());
        final var unknown = new java.util.LinkedHashMap<>(parameters());
        unknown.put("Condition", "UnrecognizedCondition");
        Assert.assertTrue(IntrinsicCreatureEntryTriggerAdapter.describe(unknown, model, source(1, 1)).isEmpty());
        Assert.assertTrue(IntrinsicCreatureEntryTriggerAdapter.describe(parameters(), model, null).isEmpty());
        unknown.put("Condition", "Evolve");
        unknown.put("Mode", "ChangesZoneAll");
        unknown.put("ValidCards", unknown.remove("ValidCard"));
        unknown.put("ActivationLimit", "1");
        Assert.assertTrue(IntrinsicCreatureEntryTriggerAdapter.describe(unknown, model, source(1, 1)).isEmpty());
    }

    @Test
    public void expandedKeywordRetainsEngineConditionAndReusesCounterOutcomeValuation() {
        final var card = FModel.getMagicDb().getCommonCards().getCard("Experiment One");
        final var details = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults())
                .evaluateDefinitionDetails(card, CardStateName.Original);
        final var ability = details.descriptions().stream().filter(entry -> entry.origin() == CardAbilityTraversal.Origin.TRIGGER)
                .findFirst().orElseThrow();
        Assert.assertEquals(ability.parameters().get("Condition"), "Evolve");
        Assert.assertEquals(ability.provenance(), CardAbilityTraversal.Provenance.KEYWORD);
        final var value = details.values().stream().filter(entry -> entry.path().equals(ability.path())).findFirst().orElseThrow();
        Assert.assertEquals(value.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(value.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(value.contribution().complete());
        Assert.assertTrue(value.contribution().value() > 0);
    }
}
