package forge.ai.effect;

import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.card.CardStateName;
import forge.model.FModel;

/** Other-creature entry populations, ownership/tribal calibration and deployment separation. */
public class IntrinsicCreatureEntryTriggerTest extends AITest {
    private static Map<String, String> parameters(final String filter) {
        return Map.of("Mode", "ChangesZone", "Origin", "Any", "Destination", "Battlefield", "ValidCard", filter);
    }

    @Test
    public void printedTribalExclusionPreservesMatchingHostsAndUnknownConditions() {
        final var forerunner = CardAbilityTraversal.definitionState(FModel.getMagicDb().getCommonCards().getCard("Forerunner of the Empire"),
                CardStateName.Original);
        final var raptor = CardAbilityTraversal.definitionState(FModel.getMagicDb().getCommonCards().getCard("Polyraptor"),
                CardStateName.Original);
        for (final String validity : List.of("Dinosaur.YouCtrl", "Creature.Dinosaur+YouCtrl")) {
            final var original = new CardAbilityTraversal.AbilityDescription("entry", CardAbilityTraversal.Origin.TRIGGER,
                    CardAbilityTraversal.Provenance.PRINTED, parameters(validity), null);
            final var excluded = IntrinsicCreatureEntryTriggerAdapter.excludeNonmatchingSource(original, forerunner.getType());
            Assert.assertEquals(excluded.parameters().get("ValidCard"), validity + "+Other");
            Assert.assertEquals(original.parameters().get("ValidCard"), validity);
            Assert.assertTrue(IntrinsicCreatureEntryTriggerAdapter.describe(excluded.parameters(), IntrinsicReferenceModel.defaults(),
                    new IntrinsicReferenceModel.PermanentProfile(true, IntrinsicReferenceModel.PermanentKind.CREATURE, true,
                            1, 3, java.util.Set.of())).isPresent());
            Assert.assertSame(IntrinsicCreatureEntryTriggerAdapter.excludeNonmatchingSource(original, raptor.getType()), original);
        }
        final var params = new java.util.LinkedHashMap<>(parameters("Dinosaur.YouCtrl"));
        params.put("UnknownEntryCondition", "True");
        final var unknown = new CardAbilityTraversal.AbilityDescription("unknown", CardAbilityTraversal.Origin.TRIGGER,
                CardAbilityTraversal.Provenance.PRINTED, params, null);
        final var normalized = IntrinsicCreatureEntryTriggerAdapter.excludeNonmatchingSource(unknown, forerunner.getType());
        Assert.assertEquals(normalized.parameters().get("UnknownEntryCondition"), "True");
        Assert.assertTrue(IntrinsicCreatureEntryTriggerAdapter.describe(normalized.parameters(), IntrinsicReferenceModel.defaults(), null).isEmpty());
        final var batchParams = Map.of("Mode", "ChangesZoneAll", "Destination", "Battlefield", "ValidCards", "Dinosaur.YouCtrl",
                "ActivationLimit", "1");
        final var batch = new CardAbilityTraversal.AbilityDescription("batch", CardAbilityTraversal.Origin.TRIGGER,
                CardAbilityTraversal.Provenance.PRINTED, batchParams, null);
        Assert.assertEquals(IntrinsicCreatureEntryTriggerAdapter.excludeNonmatchingSource(batch, forerunner.getType())
                .parameters().get("ValidCards"), "Dinosaur.YouCtrl+Other");
    }

    @Test
    public void tribalDamageEntryReusesExistingDamageOutcomeAndIgnoresOnlyDisplayMetadata() {
        final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults());
        final var description = new AbilityOutcomeDescription("damage", "DamageAll", Map.of("ValidCards", "Creature",
                "NumDmg", "1", "ValidDescription", "each creature."), List.of(), null, "");
        Assert.assertTrue(backend.acceptsNode(description));
        final var semanticUnknown = new java.util.LinkedHashMap<>(description.parameters());
        semanticUnknown.put("UnknownDamageCondition", "True");
        Assert.assertFalse(backend.acceptsNode(new AbilityOutcomeDescription("unknown", description.api(), semanticUnknown, List.of(), null, "")));
        final var entry = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard("Forerunner of the Empire"), CardStateName.Original)
                .stream().filter(value -> value.path().endsWith("trigger:1")).findFirst().orElseThrow();
        Assert.assertEquals(entry.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(entry.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(entry.contribution().complete());
        Assert.assertTrue(entry.expectedOccurrences() > 0);
        Assert.assertTrue(entry.contribution().value() >= 0);
    }

    @Test
    public void changelingCannotBeMistakenForANonmatchingPrintedHost() {
        final var card = new forge.item.PaperCard(forge.card.CardRules.fromScript(List.of("Name:Changeling Entry Probe",
                "ManaCost:2 G", "Types:Creature Shapeshifter", "PT:2/2", "K:Changeling",
                "T:Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield | ValidCard$ Dinosaur.YouCtrl | Execute$ Benefit",
                "SVar:Benefit:DB$ GainLife | LifeAmount$ 1", "Oracle:Changeling and a typed entry benefit.")),
                forge.card.CardEdition.UNKNOWN_CODE, forge.card.CardRarity.Special);
        final var state = CardAbilityTraversal.definitionState(card, CardStateName.Original);
        Assert.assertTrue(IntrinsicSourceProfileResolver.definitionTypeFacts(state).hasCreatureType("Dinosaur"));
        final var entry = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(card, CardStateName.Original).stream().filter(value -> value.path().endsWith("trigger:0"))
                .findFirst().orElseThrow();
        Assert.assertEquals(entry.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.UNSUPPORTED);
    }

    @Test
    public void entryScopesAndTribalSupportAreExplicitAndIndependentOfDeathRates() {
        final var model = IntrinsicReferenceModel.defaults();
        final var any = IntrinsicEventTriggerAdapter.describe(parameters("Creature.Other"), model).orElseThrow();
        Assert.assertEquals(any.eventType(), IntrinsicReferenceModel.EventType.CREATURE_ENTERED);
        Assert.assertEquals(any.turnScope(), IntrinsicEventTrigger.TurnScope.ANY_TURN);
        Assert.assertEquals(any.occurrenceMultiplier(), 1.0);
        final var friendly = IntrinsicEventTriggerAdapter.describe(parameters("Creature.Other+YouCtrl"), model).orElseThrow();
        Assert.assertEquals(friendly.turnScope(), IntrinsicEventTrigger.TurnScope.CONTROLLER_TURN);
        final var opposing = IntrinsicEventTriggerAdapter.describe(parameters("Creature.OppCtrl"), model).orElseThrow();
        Assert.assertEquals(opposing.turnScope(), IntrinsicEventTrigger.TurnScope.OPPONENT_TURN);
        final var tribal = IntrinsicEventTriggerAdapter.describe(parameters("Creature.Elf+Other+YouCtrl"), model).orElseThrow();
        Assert.assertEquals(tribal.occurrenceMultiplier(), model.library().tribalCreatureShare());
        final var customLibrary = new IntrinsicLibraryReference(Map.of("Land", 0.0, "Creature", 1.0, "Artifact", 0.0,
                "Enchantment", 0.0, "Planeswalker", 0.0, "Instant", 0.0, "Sorcery", 0.0), .25);
        Assert.assertEquals(IntrinsicEventTriggerAdapter.describe(parameters("Creature.Elf+Other+YouCtrl"),
                model.withLibrary(customLibrary)).orElseThrow().occurrenceMultiplier(), .25);
        Assert.assertNotSame(model.eventRates(IntrinsicReferenceModel.EventType.CREATURE_ENTERED),
                model.eventRates(IntrinsicReferenceModel.EventType.CREATURE_DIED));
        for (final String filter : List.of("Creature.Self", "Creature.YouCtrl", "Creature", "Creature.Other+token",
                "Creature.Elf+Goblin+Other", "Creature.Other+YouCtrl+OppCtrl")) {
            Assert.assertTrue(IntrinsicCreatureEntryTriggerAdapter.describe(parameters(filter), model.library()).isEmpty(), filter);
        }
        final var condition = new java.util.HashMap<>(parameters("Creature.Other"));
        condition.put("ActivationLimit", "2");
        Assert.assertTrue(IntrinsicCreatureEntryTriggerAdapter.describe(condition, model.library()).isEmpty());
        final var origin = new java.util.HashMap<>(parameters("Creature.Other+YouCtrl"));
        origin.put("Origin", "Graveyard");
        Assert.assertEquals(IntrinsicCreatureEntryTriggerAdapter.describe(origin, model.library()).orElseThrow().occurrenceMultiplier(), .15);
    }

    @Test
    public void firstQualifyingEntryCapsIndividualAndBatchTriggersWithoutCappingBeforeEligibility() {
        final var model = IntrinsicReferenceModel.defaults();
        final var single = new java.util.HashMap<>(parameters("Elf.Other+YouCtrl"));
        single.put("ActivationLimit", "1");
        final var batch = new java.util.HashMap<>(single);
        batch.put("Mode", "ChangesZoneAll");
        batch.put("ValidCards", batch.remove("ValidCard"));
        final var singleEvent = IntrinsicEventTriggerAdapter.describe(single, model).orElseThrow();
        final var batchEvent = IntrinsicEventTriggerAdapter.describe(batch, model).orElseThrow();
        Assert.assertEquals(batchEvent, singleEvent);
        Assert.assertTrue(batchEvent.atMostOncePerTurn());
        final double anyEntry = .45 + .17 + .03;
        final double anyElf = .45 * .5 + .17 * .75 + .03 * .875;
        Assert.assertEquals(batchEvent.occurrenceMultiplier(), anyElf / anyEntry, 1e-9);
        batch.remove("ActivationLimit");
        Assert.assertTrue(IntrinsicCreatureEntryTriggerAdapter.describe(batch, model.library()).isEmpty());
        batch.put("ActivationLimit", "X");
        Assert.assertTrue(IntrinsicCreatureEntryTriggerAdapter.describe(batch, model.library()).isEmpty());
        batch.put("ActivationLimit", "1");
        batch.put("ValidAmount", "GE2");
        Assert.assertTrue(IntrinsicCreatureEntryTriggerAdapter.describe(batch, model.library()).isEmpty());
        final var evaluator = new IntrinsicAbilityEvaluator(model, IntrinsicEvaluationSettings.defaults());
        final var value = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard("Elvish Warmaster"),
                CardStateName.Original).stream().filter(entry -> entry.path().endsWith("trigger:0")).findFirst().orElseThrow();
        Assert.assertEquals(value.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(value.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(value.contribution().value() > 0);
    }

    @Test
    public void selfInclusiveFiltersNeedNoDeploymentCreditForNoncreatureSources() {
        final var model = IntrinsicReferenceModel.defaults();
        final var enchantment = new IntrinsicReferenceModel.PermanentProfile(true,
                IntrinsicReferenceModel.PermanentKind.ENCHANTMENT, true, 0, 0, java.util.Set.of());
        final var creature = new IntrinsicReferenceModel.PermanentProfile(true,
                IntrinsicReferenceModel.PermanentKind.CREATURE, true, 2, 3, java.util.Set.of());
        for (final String filter : List.of("Creature", "Creature.YouCtrl", "Creature.Elf+YouCtrl")) {
            Assert.assertEquals(IntrinsicEventTriggerAdapter.describe(parameters(filter), model, enchantment)
                    .orElseThrow().eventType(), IntrinsicReferenceModel.EventType.CREATURE_ENTERED, filter);
            // Generic event recognition is broader than intrinsic support. Check the support adapter,
            // not recognition, when verifying that deployment credit has not been silently omitted.
            Assert.assertTrue(IntrinsicCreatureEntryTriggerAdapter.describe(parameters(filter),
                    model.library(), creature).isEmpty(), filter);
            Assert.assertFalse(IntrinsicEventTriggerAdapter.supportsIntrinsicParameters(parameters(filter)), filter);
        }
        // A noncreature can have a Kindred tribe. Its profile does not prove it cannot match Elf.
        Assert.assertTrue(IntrinsicCreatureEntryTriggerAdapter.describe(parameters("Elf.YouCtrl"),
                model.library(), enchantment).isEmpty());
        final var evaluator = new IntrinsicAbilityEvaluator(model, IntrinsicEvaluationSettings.defaults());
        for (final String name : List.of("Impact Tremors", "Ajani's Welcome")) {
            final var value = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard(name),
                    CardStateName.Original).stream().filter(entry -> entry.path().endsWith("trigger:0")).findFirst().orElseThrow();
            Assert.assertEquals(value.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED, name);
            Assert.assertTrue(value.expectedOccurrences() > 0, name);
            Assert.assertTrue(value.contribution().value() > 0, name);
        }
    }

    @Test
    public void entryCharacteristicsShareProfilesAndExcludeAbsentBoardCases() {
        final var model = IntrinsicReferenceModel.defaults();
        final double present = model.creatureProfiles().entries().stream()
                .filter(entry -> entry.value().present()).mapToDouble(WeightedValue::weight).sum();
        final double small = model.creatureProfiles().entries().stream()
                .filter(entry -> entry.value().present() && entry.value().power() <= 2)
                .mapToDouble(WeightedValue::weight).sum() / present;
        final var smallEvent = IntrinsicEventTriggerAdapter.describe(parameters("Creature.powerLE2+Other+YouCtrl"), model)
                .orElseThrow();
        Assert.assertEquals(smallEvent.occurrenceMultiplier(), small, 1e-9);
        final var duplicate = IntrinsicEventTriggerAdapter.describe(parameters("Creature.powerLE2+powerLE2+Other+YouCtrl"), model)
                .orElseThrow();
        Assert.assertEquals(duplicate, smallEvent);
        Assert.assertEquals(IntrinsicEventTriggerAdapter.describe(parameters("Creature.powerLE2+powerGE3+Other"), model)
                .orElseThrow().occurrenceMultiplier(), 0.0);
        Assert.assertEquals(IntrinsicEventTriggerAdapter.describe(parameters("Creature.withFlying+withoutFlying+Other"), model)
                .orElseThrow().occurrenceMultiplier(), 0.0);
        for (final String filter : List.of("Creature.powerLEX+Other", "Creature.withUnknownKeyword+Other",
                "Creature.counters_GE1_P1P1+Other", "Creature.powerLE2+token+Other")) {
            Assert.assertTrue(IntrinsicCreatureEntryTriggerAdapter.describe(parameters(filter), model, null).isEmpty(), filter);
        }
        final var batch = new java.util.HashMap<>(parameters("Creature.powerLE2+Other+YouCtrl"));
        batch.put("Mode", "ChangesZoneAll");
        batch.put("ValidCards", batch.remove("ValidCard"));
        batch.put("ActivationLimit", "1");
        final double expectedFirstMatch = model.eventRates(IntrinsicReferenceModel.EventType.CREATURE_ENTERED)
                .entries().stream().mapToDouble(entry -> entry.weight() * (1 - Math.pow(1 - small, entry.value()))).sum();
        final double anyEntry = model.eventRates(IntrinsicReferenceModel.EventType.CREATURE_ENTERED)
                .entries().stream().mapToDouble(entry -> entry.weight() * Math.min(1, entry.value())).sum();
        Assert.assertEquals(IntrinsicEventTriggerAdapter.describe(batch, model).orElseThrow().occurrenceMultiplier(),
                expectedFirstMatch / anyEntry, 1e-9);
        final var evaluator = new IntrinsicAbilityEvaluator(model, IntrinsicEvaluationSettings.defaults());
        for (final var card : Map.of("Welcoming Vampire", "trigger:0", "Garruk's Uprising", "trigger:1").entrySet()) {
            final var value = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard(card.getKey()),
                    CardStateName.Original).stream().filter(entry -> entry.path().endsWith(card.getValue())).findFirst().orElseThrow();
            Assert.assertEquals(value.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED, card.getKey());
            Assert.assertEquals(value.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED, card.getKey());
            Assert.assertTrue(value.contribution().value() > 0, card.getKey());
        }
    }

    @Test
    public void actualOtherEntryLifeAndCounterOutcomesReuseExistingUtilities() {
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        for (final String name : List.of("Soul Warden", "Elvish Vanguard", "Corpse Knight")) {
            final var value = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard(name),
                    CardStateName.Original).stream().filter(entry -> entry.path().endsWith("trigger:0")).findFirst().orElseThrow();
            Assert.assertEquals(value.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED, name);
            Assert.assertEquals(value.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED, name);
            Assert.assertTrue(value.expectedOccurrences() > 0, name);
            Assert.assertTrue(value.contribution().value() > 0, name);
        }
        Assert.assertTrue(IntrinsicSelfEntryTriggerAdapter.supports(parameters("Creature.Self")));
        Assert.assertFalse(IntrinsicEventTriggerAdapter.supportsIntrinsicParameters(parameters("Creature.Self")));
    }
}
