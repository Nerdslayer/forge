package forge.ai.effect;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.CardStateName;
import forge.item.PaperCard;

/** Condition/activity coverage and shared quantity sampling, not card-name dispatch. */
public class IntrinsicConditionalAbilityTest extends AITest {
    private static PaperCard definition(final String condition, final String outcome) {
        return new PaperCard(CardRules.fromScript(List.of("Name:Conditional Ability Probe", "ManaCost:2 G", "Types:Creature Elf",
                "PT:1/3", "T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | Execute$ Benefit" + condition,
                "SVar:Benefit:" + outcome, "SVar:X:Count$CardCounters.P1P1", "Oracle:Conditional benefit.")),
                CardEdition.UNKNOWN_CODE, CardRarity.Special);
    }

    private static IntrinsicAbilityEvaluator.AbilityValue evaluate(final IntrinsicReferenceModel model,
            final String condition, final String outcome) {
        return new IntrinsicAbilityEvaluator(model, IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(definition(condition, outcome), CardStateName.Original).get(0);
    }

    @Test
    public void counterConditionAndScaledOutcomeUseOneSample() {
        final var original = IntrinsicReferenceModel.defaults();
        final var model = original.withQuantities(original.quantities().with(IntrinsicReferenceQuantities.Quantity.P1P1_COUNTERS,
                WeightedDistribution.of(new WeightedValue<>(0, .5), new WeightedValue<>(4, .5))));
        final String token = "DB$ Token | TokenScript$ w_1_1_soldier | TokenAmount$ X";
        final var unconditional = evaluate(model, "", token);
        for (final String condition : List.of(" | CheckSVar$ X | SVarCompare$ GE2",
                " | IsPresent$ Card.Self+counters_GE2_P1P1 | PresentDefined$ Self")) {
            final var conditional = evaluate(model, condition, token);
            Assert.assertEquals(conditional.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
            Assert.assertEquals(conditional.contribution().value(), unconditional.contribution().value(), 1e-9);
            Assert.assertEquals(conditional.contribution().unavailableCaseProbability(), .5, 1e-9);
            Assert.assertEquals(conditional.expectedOccurrences(), unconditional.expectedOccurrences() * .5, 1e-9);
        }
    }

    @Test
    public void rootLifeSelectorsShareOutcomeUtilitySamplesAndKeepUnknownScopesUnresolved() {
        final var model = IntrinsicReferenceModel.defaults();
        final var evaluator = new IntrinsicOutcomeEvaluator(IntrinsicEvaluationSettings.defaults());
        final var own = evaluate(model, " | LifeTotal$ You | LifeAmount$ LE5", "DB$ GainLife | Defined$ You | LifeAmount$ 3");
        Assert.assertEquals(own.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        final double active = model.lifeTotals().entries().stream().filter(entry -> entry.value() <= 5)
                .mapToDouble(WeightedValue::weight).sum();
        Assert.assertEquals(own.contribution().unavailableCaseProbability(), 1 - active, 1e-9);
        final var reference = evaluate(model, " | CheckSVar$ OwnLife | SVarCompare$ LE5", "DB$ GainLife | Defined$ You | LifeAmount$ 3");
        Assert.assertFalse(reference.contribution().complete()); // Unknown aliases are not guessed.
        final double perUse = model.lifeTotals().entries().stream().filter(entry -> entry.value() <= 5)
                .mapToDouble(entry -> entry.weight() * evaluator.evaluateLifeGain(entry.value(), 3, true)).sum();
        final var unconditional = evaluate(model, "", "DB$ GainLife | Defined$ You | LifeAmount$ 3");
        Assert.assertEquals(own.contribution().value(), perUse * unconditional.expectedOccurrences(), 1e-9);
        for (final String selector : List.of("OpponentSmallest", "OpponentGreatest")) {
            final var opponent = evaluate(model, " | LifeTotal$ " + selector + " | LifeAmount$ LE5",
                    "DB$ GainLife | Defined$ Opponent | LifeAmount$ 3");
            Assert.assertEquals(opponent.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
            Assert.assertEquals(opponent.contribution().value(), -own.contribution().value(), 1e-9);
        }
        for (final String selector : List.of("ActivePlayer", "Opponent", "TriggeredPlayer")) {
            final var unresolved = evaluate(model, " | LifeTotal$ " + selector + " | LifeAmount$ LE5", "DB$ Draw | NumCards$ 1");
            Assert.assertFalse(unresolved.contribution().complete(), selector);
        }
        final var unknownBound = evaluate(model, " | LifeTotal$ You | LifeAmount$ GEUnknownLife", "DB$ Draw | NumCards$ 1");
        Assert.assertFalse(unknownBound.contribution().complete());
    }

    @Test
    public void creaturePresenceUsesOtherPopulationPlusTheSource() {
        final var model = IntrinsicReferenceModel.defaults();
        final String draw = "DB$ Draw | Defined$ You | NumCards$ 1";
        final var unconditional = evaluate(model, "", draw);
        final var conditional = evaluate(model, " | IsPresent$ Creature.YouCtrl | PresentCompare$ GE2", draw);
        Assert.assertEquals(conditional.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(conditional.contribution().unavailableCaseProbability(), .20, 1e-9);
        Assert.assertEquals(conditional.contribution().value(), unconditional.contribution().value() * .80, 1e-9);
    }

    @Test
    public void impossibleConditionsAreUnderstoodAndUnknownConditionsStayUnsupported() {
        final var model = IntrinsicReferenceModel.defaults();
        final var inactive = evaluate(model, " | CheckSVar$ X | SVarCompare$ GE99", "DB$ Mill | NumCards$ 1");
        Assert.assertEquals(inactive.contribution().value(), 0.0);
        Assert.assertEquals(inactive.contribution().unavailableCaseProbability(), 1.0);
        Assert.assertEquals(inactive.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        for (final String condition : List.of(" | CheckSVar$ UnknownPopulation | SVarCompare$ GE1",
                " | CheckSVar$ X | SVarCompare$ GEY", " | IsPresent$ Artifact.YouCtrl",
                " | IsPresent$ Creature.YouCtrl | PresentPlayer$ Opponent")) {
            final var unknown = evaluate(model, condition, "DB$ Draw | NumCards$ 1");
            Assert.assertFalse(unknown.contribution().complete(), condition);
        }
    }

    @Test
    public void groupOutcomesReuseTheirConditionPopulationInsteadOfResampling() {
        final var model = IntrinsicReferenceModel.defaults();
        final var conditioned = model.withQuantityBindings(java.util.Map.of("otherFriendlyCreatures", 4, "opposingCreatures", 2));
        Assert.assertEquals(conditioned.friendlyCreatureCounts().entries().size(), 1);
        Assert.assertEquals(conditioned.friendlyCreatureCounts().entries().get(0).value().intValue(), 4);
        Assert.assertEquals(conditioned.opposingCreatureCounts().entries().get(0).value().intValue(), 2);
        final String group = "DB$ PutCounterAll | ValidCards$ Creature.Other+YouCtrl | CounterType$ P1P1 | CounterNum$ 1";
        final var unconditional = evaluate(model, "", group);
        // Only the zero-other-creatures case is excluded, and its group benefit was already
        // zero. Independent re-sampling would incorrectly remove 20% of all group value.
        final var conditional = evaluate(model, " | IsPresent$ Creature.YouCtrl | PresentCompare$ GE2", group);
        Assert.assertEquals(conditional.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(conditional.contribution().value(), unconditional.contribution().value(), 1e-9);
    }

    @Test
    public void staticConditionsShareTheirAmountBindingAndPreserveInactiveCoverage() {
        final var original = IntrinsicReferenceModel.defaults();
        final var model = original.withQuantities(original.quantities().with(IntrinsicReferenceQuantities.Quantity.P1P1_COUNTERS,
                WeightedDistribution.of(new WeightedValue<>(0, .5), new WeightedValue<>(4, .5))));
        final var evaluator = new IntrinsicAbilityEvaluator(model, IntrinsicEvaluationSettings.defaults());
        final var definition = new PaperCard(CardRules.fromScript(List.of("Name:Conditional Static Probe", "ManaCost:G",
                "Types:Creature Elf", "PT:1/3", "S:Mode$ Continuous | Affected$ Card.Self | AddPower$ X | CheckSVar$ X | SVarCompare$ GE2",
                "SVar:X:Count$CardCounters.P1P1", "Oracle:Conditional static benefit.")), CardEdition.UNKNOWN_CODE, CardRarity.Special);
        final var value = evaluator.evaluateDefinition(definition, CardStateName.Original).get(0);
        Assert.assertEquals(value.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(value.contribution().value() > 0);
        Assert.assertEquals(value.contribution().unavailableCaseProbability(), .5, 1e-9);
        Assert.assertEquals(value.expectedOccurrences(), .5, 1e-9);
    }

    @Test
    public void counterRangesShareOneSampleAndKeepCounterTypesSeparate() {
        final var model = IntrinsicReferenceModel.defaults();
        final var source = new IntrinsicReferenceModel.PermanentProfile(true,
                IntrinsicReferenceModel.PermanentKind.CREATURE, true, 2, 2, java.util.Set.of());
        for (final var range : java.util.Map.of("GE1_LEVEL+counters_LE3_LEVEL", .55,
                "GE4_LEVEL", .05, "EQ0_LEVEL", .40, "GE4_LEVEL+counters_LE3_LEVEL", 0.0).entrySet()) {
            final String filter = "Card.Self+counters_" + range.getKey();
            final var binding = IntrinsicQuantityResolver.resolve("Count$Valid " + filter,
                    java.util.Map.of(), model, source).orElseThrow();
            Assert.assertEquals(binding.identity(), "LEVEL_COUNTERS");
            Assert.assertEquals(binding.values().entries().stream()
                    .mapToDouble(entry -> entry.value() * entry.weight()).sum(), range.getValue(), 1e-9);
            Assert.assertEquals(IntrinsicStaticRecipientFilter.describe(filter, model).probability(), range.getValue(), 1e-9);
        }
        Assert.assertTrue(IntrinsicCounterPredicates.parse("Card.Self+counters_GE1_LEVEL+counters_GE1_P1P1").isEmpty());
        Assert.assertTrue(IntrinsicCounterPredicates.parse("Card.Self+counters_GE1_OIL").isEmpty());
        Assert.assertNotEquals(model.quantities().distribution(IntrinsicReferenceQuantities.Quantity.LEVEL_COUNTERS),
                model.quantities().distribution(IntrinsicReferenceQuantities.Quantity.P1P1_COUNTERS));
        final var plus = IntrinsicStaticRecipientFilter.describe("Creature.YouCtrl+counters_GT0_P1P1+counters_LT3_P1P1", model);
        Assert.assertEquals(plus.probability(), .45, 1e-9);
    }

    @Test
    public void levelTierDefinitionsEvaluateTheirStaticBenefitsWithoutClaimingLevelActivationSupport() {
        final var values = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(forge.model.FModel.getMagicDb().getCommonCards().getCard("Knight of Cliffhaven"),
                        CardStateName.Original);
        final var tiers = values.stream().filter(value -> value.path().contains("static:")).toList();
        Assert.assertEquals(tiers.size(), 2);
        for (final var tier : tiers) {
            Assert.assertEquals(tier.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
            Assert.assertTrue(tier.contribution().value() > 0);
        }
        Assert.assertEquals(tiers.get(0).contribution().unavailableCaseProbability(), .45, 1e-9);
        Assert.assertEquals(tiers.get(1).contribution().unavailableCaseProbability(), .95, 1e-9);
        // TODO: Level-up payments and progression must share the level sample before valuing
        // the activation itself; tier coverage does not establish that broader support.
    }

    @Test
    public void graveyardConditionsReuseTheirAmountSampleAndRespectOwnershipScope() {
        final var original = IntrinsicReferenceModel.defaults();
        final var model = original.withQuantities(original.quantities().with(IntrinsicReferenceQuantities.Quantity.GRAVEYARD_CREATURES,
                WeightedDistribution.of(new WeightedValue<>(0, .5), new WeightedValue<>(4, .5))));
        final var definition = new PaperCard(CardRules.fromScript(List.of("Name:Graveyard Condition Probe", "ManaCost:2 G",
                "Types:Creature Elf", "PT:2/3",
                "T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | Execute$ Benefit | IsPresent$ Creature.YouOwn | PresentZone$ Graveyard | PresentCompare$ GE4",
                "SVar:Benefit:DB$ Token | TokenScript$ w_1_1_soldier | TokenAmount$ X",
                "SVar:X:Count$ValidGraveyard Creature.YouOwn", "Oracle:Graveyard-conditioned tokens.")),
                CardEdition.UNKNOWN_CODE, CardRarity.Special);
        final var value = new IntrinsicAbilityEvaluator(model, IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(definition, CardStateName.Original).get(0);
        Assert.assertEquals(value.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(value.contribution().unavailableCaseProbability(), .5, 1e-9);
        Assert.assertTrue(value.contribution().value() > 0);
        final var source = new IntrinsicReferenceModel.PermanentProfile(true,
                IntrinsicReferenceModel.PermanentKind.CREATURE, true, 2, 3, java.util.Set.of());
        final var own = IntrinsicQuantityResolver.resolve("Count$ValidGraveyard Creature.YouOwn", java.util.Map.of(), model, source).orElseThrow();
        final var opposing = IntrinsicQuantityResolver.resolve("Count$ValidGraveyard Creature.OppOwn", java.util.Map.of(), model, source).orElseThrow();
        Assert.assertNotEquals(own.identity(), opposing.identity());
        Assert.assertNotEquals(own.values().entries(), opposing.values().entries());
        final var valid = IntrinsicAbilityConditions.prepare(java.util.Map.of("IsPresent", "Creature",
                "PresentZone", "Graveyard", "PresentPlayer", "Opponent"));
        Assert.assertEquals(valid.get(IntrinsicAbilityConditions.PRESENT_COUNT), "Count$ValidGraveyard Creature.OppOwn");
        for (final var invalid : List.of(java.util.Map.of("IsPresent", "Creature.YouOwn", "PresentZone", "Graveyard", "PresentPlayer", "Opponent"),
                java.util.Map.of("IsPresent", "Creature", "PresentZone", "Graveyard"),
                java.util.Map.of("IsPresent", "Creature.YouOwn+DirectlyAbove", "PresentZone", "Graveyard"))) {
            Assert.assertFalse(IntrinsicAbilityConditions.prepare(invalid).containsKey(IntrinsicAbilityConditions.PRESENT_COUNT));
        }
    }

    @Test
    public void handConditionsAndDrawOutcomesUseTheSameHandWithoutChangingTheOtherPlayer() {
        final var model = IntrinsicReferenceModel.defaults();
        final String condition = " | IsPresent$ Card.YouOwn | PresentZone$ Hand | PresentCompare$ EQ0";
        final var own = evaluate(model, condition, "DB$ Draw | Defined$ You | NumCards$ 1");
        Assert.assertEquals(own.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(own.contribution().unavailableCaseProbability(), .95, 1e-9);
        Assert.assertEquals(own.contribution().value() / own.expectedOccurrences(),
                (double) forge.ai.PlayerResourceValueEvaluator.evaluateCardDraw(0, 1), 1e-9);
        final var opposing = evaluate(model, condition, "DB$ Draw | Defined$ Opponent | NumCards$ 1");
        final double opposingMean = model.handSizes().entries().stream().mapToDouble(entry ->
                entry.weight() * forge.ai.PlayerResourceValueEvaluator.evaluateCardDraw(entry.value(), 1)).sum();
        Assert.assertEquals(opposing.contribution().value() / opposing.expectedOccurrences(), -opposingMean, 1e-9);
        final var conditioned = model.withQuantityBindings(java.util.Map.of(IntrinsicDrawOutcomeBackend.CONTROLLER_HAND, 0));
        Assert.assertEquals(conditioned.referenceIntegers(IntrinsicDrawOutcomeBackend.CONTROLLER_HAND, model.handSizes())
                .entries().get(0).value().intValue(), 0);
        Assert.assertSame(conditioned.referenceIntegers(IntrinsicDrawOutcomeBackend.OPPONENT_HAND, model.handSizes()), model.handSizes());
        Assert.assertEquals(conditioned.withLibrary(model.library()).withQuantities(model.quantities())
                .referenceIntegers(IntrinsicDrawOutcomeBackend.CONTROLLER_HAND, model.handSizes()).entries().get(0).value().intValue(), 0);
    }

    @Test
    public void graveyardConditionalStaticDefinitionIsSupportedWithoutClaimingItsOtherAbility() {
        final var values = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(forge.model.FModel.getMagicDb().getCommonCards().getCard("Killmonger, Scourge of Wakanda"),
                        CardStateName.Original);
        final var staticValue = values.stream().filter(value -> value.path().endsWith("static:0")).findFirst().orElseThrow();
        Assert.assertEquals(staticValue.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(staticValue.contribution().value() > 0);
        Assert.assertEquals(staticValue.contribution().unavailableCaseProbability(), .50, 1e-9);
    }

    @Test
    public void conditionalSelfDeathKeepsLastKnownSourceAndControllerBindings() {
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        final var values = new java.util.ArrayList<IntrinsicAbilityEvaluator.AbilityValue>();
        for (final String condition : List.of("", " | IsPresent$ Card.YouOwn | PresentZone$ Hand | PresentCompare$ EQ0")) {
            final var definition = new PaperCard(CardRules.fromScript(List.of("Name:Conditional Death Probe", "ManaCost:2 G",
                    "Types:Creature Elf", "PT:3/3",
                    "T:Mode$ ChangesZone | Origin$ Battlefield | Destination$ Graveyard | ValidCard$ Card.Self | Execute$ Benefit" + condition,
                    "SVar:Benefit:DB$ GainLife | Defined$ TriggeredCardController | LifeAmount$ X",
                    "SVar:X:TriggeredCard$CardPower", "Oracle:Conditional last-known benefit.")),
                    CardEdition.UNKNOWN_CODE, CardRarity.Special);
            values.add(evaluator.evaluateDefinition(definition, CardStateName.Original).get(0));
        }
        Assert.assertEquals(values.get(1).outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(values.get(1).contribution().value(), values.get(0).contribution().value() * .05, 1e-9);
        Assert.assertEquals(values.get(1).contribution().unavailableCaseProbability(), .95, 1e-9);
        Assert.assertTrue(values.get(0).contribution().value() > 0);
        final var unknownCondition = java.util.Map.of("Mode", "ChangesZone", "Origin", "Battlefield", "Destination", "Graveyard",
                "ValidCard", "Card.Self", "IsPresent", "UnknownPredicate");
        Assert.assertTrue(IntrinsicSelfDeathTriggerAdapter.bindableSourceEvent(unknownCondition));
        Assert.assertFalse(IntrinsicSelfDeathTriggerAdapter.supports(unknownCondition));
        Assert.assertFalse(IntrinsicSelfDeathTriggerAdapter.bindableSourceEvent(java.util.Map.of("Mode", "ChangesZone",
                "Origin", "Battlefield", "Destination", "Graveyard", "ValidCard", "Creature.Other", "IsPresent", "Card.YouOwn")));
    }

    @Test
    public void activationRootConditionsShareOutcomeStateAndDoNotStripFollowingGuards() {
        final var model = IntrinsicReferenceModel.defaults();
        final var evaluator = new IntrinsicAbilityEvaluator(model, IntrinsicEvaluationSettings.defaults());
        final var card = new PaperCard(CardRules.fromScript(List.of("Name:Conditional Activation Probe", "ManaCost:2 U",
                "Types:Creature Wizard", "PT:2/3",
                "A:AB$ Draw | Cost$ 1 | Defined$ You | NumCards$ 1 | CheckSVar$ X | SVarCompare$ EQ0 | SubAbility$ Next",
                "SVar:X:Count$ValidHand Card.YouOwn",
                "SVar:Next:DB$ Draw | Defined$ You | NumCards$ 1 | ConditionPresent$ Card.YouOwn | ConditionZone$ Hand | ConditionCompare$ EQ0",
                "Oracle:Conditional activated draw followed by a resolution-time guard.")), CardEdition.UNKNOWN_CODE, CardRarity.Special);
        final var result = evaluator.evaluateDefinition(card, CardStateName.Original).get(0);
        Assert.assertEquals(result.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(result.contribution().unavailableCaseProbability(), .95, 1e-9);
        Assert.assertTrue(result.expectedOccurrences() > 0);
        Assert.assertEquals(result.contribution().value() / result.expectedOccurrences(),
                new IntrinsicOutcomeEvaluator().evaluateCardDraw(0, 1, true), 1e-9);
        final var presence = new PaperCard(CardRules.fromScript(List.of("Name:Population Activation Probe", "ManaCost:2 U",
                "Types:Creature Wizard", "PT:2/3",
                "A:AB$ Draw | Cost$ 1 | Defined$ You | NumCards$ 1 | IsPresent$ Creature.Other+YouCtrl | PresentCompare$ GE2",
                "Oracle:Activate only if you control two other creatures.")), CardEdition.UNKNOWN_CODE, CardRarity.Special);
        final var population = evaluator.evaluateDefinition(presence, CardStateName.Original).get(0);
        Assert.assertEquals(population.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        final double missing = model.friendlyCreatureCounts().entries().stream().filter(entry -> entry.value() < 2)
                .mapToDouble(WeightedValue::weight).sum();
        Assert.assertEquals(population.contribution().unavailableCaseProbability(), missing, 1e-9);
        final var lifeCost = new PaperCard(CardRules.fromScript(List.of("Name:Life Payment Condition Probe", "ManaCost:2 B",
                "Types:Creature Wizard", "PT:2/3",
                "A:AB$ Draw | Cost$ PayLife<5> | Defined$ You | NumCards$ 1 | CheckSVar$ X | SVarCompare$ LE3",
                "SVar:X:Count$YourLifeTotal", "Oracle:An unavailable life payment under the activation condition.")),
                CardEdition.UNKNOWN_CODE, CardRarity.Special);
        final var unaffordable = evaluator.evaluateDefinition(lifeCost, CardStateName.Original).get(0);
        Assert.assertEquals(unaffordable.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(unaffordable.expectedOccurrences(), 0.0);
        Assert.assertEquals(unaffordable.contribution().value(), 0.0);
    }

    @Test
    public void playerTurnRestrictionRejectsFlashEntryUsesButRetainsFutureUses() {
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        final var values = new java.util.ArrayList<IntrinsicAbilityEvaluator.AbilityValue>();
        for (final String restriction : List.of("", " | PlayerTurn$ True", " | PlayerTurn$ False", " | OpponentTurn$ True",
                " | CheckSVar$ X | SVarCompare$ GE1")) {
            final var card = new PaperCard(CardRules.fromScript(List.of("Name:Timing Activation Probe", "ManaCost:2 U",
                    "Types:Creature Wizard", "PT:2/3", "K:Flash",
                    "A:AB$ Draw | Cost$ 1 | Defined$ You | NumCards$ 1" + restriction,
                    "SVar:X:Count$UnmodeledHistory", "Oracle:An activated draw with timing restrictions.")),
                    CardEdition.UNKNOWN_CODE, CardRarity.Special);
            values.add(evaluator.evaluateDefinition(card, CardStateName.Original).get(0));
        }
        Assert.assertTrue(values.get(0).currentTurnUses() > 0);
        Assert.assertEquals(values.get(1).outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(values.get(1).currentTurnUses(), 0.0);
        Assert.assertTrue(values.get(1).expectedOccurrences() > 0);
        for (int index = 2; index < values.size(); index++) {
            Assert.assertEquals(values.get(index).triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.UNSUPPORTED);
        }
    }

    @Test
    public void startingLifeStaticConditionUsesConfigurableReferenceBound() {
        final var card = forge.model.FModel.getMagicDb().getCommonCards().getCard("Path of Bravery");
        final var model = IntrinsicReferenceModel.defaults();
        final var normal = new IntrinsicAbilityEvaluator(model, IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(card, CardStateName.Original).stream().filter(value -> value.path().endsWith("static:0"))
                .findFirst().orElseThrow();
        Assert.assertEquals(normal.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(normal.contribution().value() > 0);
        Assert.assertEquals(normal.contribution().unavailableCaseProbability(), .60, 1e-9);
        final var higherStartingLife = model.withQuantities(model.quantities().with(
                IntrinsicReferenceQuantities.Quantity.STARTING_LIFE, WeightedDistribution.of(new WeightedValue<>(40, 1))));
        final var inactive = new IntrinsicAbilityEvaluator(higherStartingLife, IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(card, CardStateName.Original).stream().filter(value -> value.path().endsWith("static:0"))
                .findFirst().orElseThrow();
        Assert.assertEquals(inactive.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(inactive.contribution().value(), 0.0);
        Assert.assertEquals(inactive.contribution().unavailableCaseProbability(), 1.0);
    }

    @Test
    public void lifeQuantityConditionsAndUtilitiesUseOnePlayerSpecificSample() {
        final var model = IntrinsicReferenceModel.defaults();
        final var evaluator = new IntrinsicAbilityEvaluator(model, IntrinsicEvaluationSettings.defaults());
        final var definition = new PaperCard(CardRules.fromScript(List.of("Name:Life Condition Probe", "ManaCost:2 G",
                "Types:Creature Elf", "PT:2/3",
                "T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | Execute$ Benefit | CheckSVar$ X | SVarCompare$ LE5",
                "SVar:X:Count$YourLifeTotal", "SVar:Benefit:DB$ GainLife | Defined$ You | LifeAmount$ 1",
                "Oracle:At the beginning of your upkeep, if your life total is five or less, gain one life.")),
                CardEdition.UNKNOWN_CODE, CardRarity.Special);
        final var result = evaluator.evaluateDefinition(definition, CardStateName.Original).get(0);
        Assert.assertEquals(result.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        final var scorer = new IntrinsicOutcomeEvaluator();
        final double expected = model.lifeTotals().entries().stream().filter(entry -> entry.value() <= 5)
                .mapToDouble(entry -> entry.weight() * scorer.evaluateLifeGain(entry.value(), 1, true)).sum();
        // Occurrences are already multiplied by the condition's available probability.
        final double active = model.lifeTotals().entries().stream().filter(entry -> entry.value() <= 5)
                .mapToDouble(WeightedValue::weight).sum();
        Assert.assertEquals(result.contribution().value() / result.expectedOccurrences(), expected / active, 1e-9);
        final var own = IntrinsicQuantityResolver.resolve("Count$YourLifeTotal", java.util.Map.of(), model,
                new IntrinsicReferenceModel.PermanentProfile(true, IntrinsicReferenceModel.PermanentKind.CREATURE,
                        true, 2, 3, java.util.Set.of())).orElseThrow();
        Assert.assertEquals(own.identity(), IntrinsicDrawOutcomeBackend.CONTROLLER_LIFE);
        final var conditioned = model.withQuantityBindings(java.util.Map.of(own.identity(), 3));
        Assert.assertEquals(conditioned.referenceIntegers(own.identity(), model.lifeTotals()).entries().get(0).value().intValue(), 3);
        Assert.assertSame(conditioned.referenceIntegers(IntrinsicDrawOutcomeBackend.OPPONENT_LIFE, model.lifeTotals()), model.lifeTotals());
        Assert.assertEquals(IntrinsicQuantityResolver.resolve("Count$OppGreatestLifeTotal", java.util.Map.of(), model,
                IntrinsicReferenceModel.PermanentProfile.absent()).orElseThrow().identity(), IntrinsicDrawOutcomeBackend.OPPONENT_LIFE);
    }

    @Test
    public void conditionComparisonsMatchForgeOperatorsAndRemainBounded() {
        for (final String comparison : List.of("GE2", "GT1", "LE2", "LT3", "EQ2", "NE3")) {
            final var result = IntrinsicAbilityConditions.resolve(java.util.Map.of("CheckSVar", "2", "SVarCompare", comparison));
            Assert.assertFalse(result.inactive(), comparison);
            Assert.assertFalse(result.parameters().containsKey("CheckSVar"));
        }
        Assert.assertTrue(IntrinsicAbilityConditions.resolve(java.util.Map.of("CheckSVar", "2", "SVarCompare", "NE2")).inactive());
        Assert.assertTrue(IntrinsicAbilityConditions.resolve(java.util.Map.of("CheckSVar", "2", "SVarCompare", "GE2147483648"))
                .parameters().containsKey("CheckSVar"));
    }
}
