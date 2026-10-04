package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.effect.IntrinsicAbilityEvaluator.AbilityValue;
import forge.ai.effect.IntrinsicAbilityEvaluator.SupportStatus;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;
import forge.ai.effect.IntrinsicReferenceQuantities.Quantity;
import forge.card.CardStateName;
import forge.model.FModel;

/** Broad quantity, binding, completeness and actual-definition integration regressions. */
public class IntrinsicQuantitySupportTest extends AITest {
    private static final PermanentProfile SOURCE = new PermanentProfile(true, PermanentKind.CREATURE,
            true, 2, 3, Set.of());

    private static AbilityOutcomeDescription token(final String amount) {
        return new AbilityOutcomeDescription("token", "Token", Map.of("TokenScript", "w_1_1_soldier",
                "TokenAmount", amount), List.of(), null, "");
    }

    @Test
    public void quantitiesIncludeZeroAndAreIndependentlyCustomizable() {
        final IntrinsicReferenceModel original = IntrinsicReferenceModel.defaults();
        for (final Quantity quantity : Quantity.values()) {
            final var entries = original.quantities().distribution(quantity).entries();
            Assert.assertEquals(entries.stream().mapToDouble(WeightedValue::weight).sum(), 1.0, 1e-9);
            if (quantity != Quantity.STARTING_LIFE) {
                Assert.assertTrue(entries.stream().anyMatch(entry -> entry.value() == 0));
            } else {
                Assert.assertEquals(entries.size(), 1);
                Assert.assertEquals(entries.get(0).value().intValue(), 20);
            }
        }
        final var changed = original.withQuantities(original.quantities().with(Quantity.X_PAID,
                WeightedDistribution.of(new WeightedValue<>(4, 1))));
        Assert.assertEquals(changed.quantities().distribution(Quantity.X_PAID).entries().get(0).value().intValue(), 4);
        Assert.assertEquals(original.quantities().distribution(Quantity.X_PAID).entries().get(0).value().intValue(), 0);
        Assert.assertSame(original.handSizes(), changed.handSizes());
    }

    @Test
    public void quantityAliasesShareOneBindingAcrossSequencesAndChoices() {
        final var first = token("X");
        final var sequence = new AbilityOutcomeDescription(first.path(), first.api(), first.parameters(),
                List.of(token("Alias")), token("Alias"), "");
        final var cases = IntrinsicOutcomeQuantityBinder.bind(sequence,
                Map.of("X", "Count$CardCounters.P1P1", "Alias", "X"), IntrinsicReferenceModel.defaults(), SOURCE);
        Assert.assertEquals(cases.size(), 6);
        Assert.assertEquals(cases.stream().mapToDouble(WeightedValue::weight).sum(), 1.0, 1e-9);
        for (final var reference : cases) {
            final String amount = reference.value().parameters().get("TokenAmount");
            Assert.assertEquals(reference.value().next().parameters().get("TokenAmount"), amount);
            Assert.assertEquals(reference.value().choices().get(0).parameters().get("TokenAmount"), amount);
        }
    }

    @Test
    public void arithmeticUsesTheSameUnderlyingQuantityAcrossModesAndSequences() {
        final var first = token("X");
        final var structure = new AbilityOutcomeDescription(first.path(), first.api(), first.parameters(),
                List.of(token("Twice")), token("Incremented"), "");
        final var model = IntrinsicReferenceModel.defaults();
        final var variables = Map.of("X", "Count$CardCounters.P1P1", "Twice", "X/Times.2",
                "Incremented", "X/Plus.1");
        final var cases = IntrinsicOutcomeQuantityBinder.bind(structure, variables, model, SOURCE);
        Assert.assertEquals(cases.size(), 6); // Not 6 cubed: all three are derived from one X.
        for (final var reference : cases) {
            final int x = Integer.parseInt(reference.value().parameters().get("TokenAmount"));
            Assert.assertEquals(Integer.parseInt(reference.value().choices().get(0).parameters().get("TokenAmount")), x * 2);
            Assert.assertEquals(Integer.parseInt(reference.value().next().parameters().get("TokenAmount")), x + 1);
        }
        Assert.assertTrue(IntrinsicQuantityResolver.resolve("X/Divide.0", variables, model, SOURCE).isEmpty());
        Assert.assertTrue(IntrinsicQuantityResolver.resolve("X/Times.2147483647", variables, model, SOURCE).isEmpty());
        Assert.assertTrue(IntrinsicQuantityResolver.resolve("X/Unknown.1", variables, model, SOURCE).isEmpty());
        Assert.assertEquals(IntrinsicQuantityResolver.resolve("Number$8/DivideEvenlyDown.2/Minus.1", variables, model, SOURCE)
                .orElseThrow().values().entries().get(0).value().intValue(), 3);
    }

    @Test
    public void definitionActivationLimitsAreEnforcedBeforeOutcomeMetadataIsRemoved() {
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        final var values = new java.util.ArrayList<AbilityValue>();
        for (final String restriction : List.of("", " | ActivationLimit$ 1", " | ActivationLimit$ 0",
                " | ActivationLimit$ X", " | ActivationLimit$ 999999999999", " | GameActivationLimit$ 1")) {
            final var card = new forge.item.PaperCard(forge.card.CardRules.fromScript(List.of("Name:Activation Limit Probe",
                    "ManaCost:2 U", "Types:Creature Wizard", "PT:2/3",
                    "A:AB$ Draw | Cost$ 1 | Defined$ You | NumCards$ 1" + restriction,
                    "Oracle:A bounded activated draw ability.")), forge.card.CardEdition.UNKNOWN_CODE, forge.card.CardRarity.Special);
            values.add(evaluator.evaluateDefinition(card, CardStateName.Original).get(0));
        }
        Assert.assertEquals(values.get(0).outcomeStatus(), SupportStatus.SUPPORTED);
        Assert.assertEquals(values.get(1).outcomeStatus(), SupportStatus.SUPPORTED);
        Assert.assertTrue(values.get(1).currentTurnUses() < values.get(0).currentTurnUses());
        Assert.assertTrue(values.get(1).contribution().value() > 0);
        Assert.assertTrue(values.get(1).contribution().value() < values.get(0).contribution().value());
        Assert.assertEquals(values.get(2).outcomeStatus(), SupportStatus.SUPPORTED);
        Assert.assertEquals(values.get(2).contribution().value(), 0.0);
        for (int index = 3; index < values.size(); index++) {
            Assert.assertEquals(values.get(index).triggerStatus(), SupportStatus.UNSUPPORTED);
        }
    }

    @Test
    public void comparisonOperandsShareSamplesAndMutableStepBoundsStayUnresolved() {
        final var model = IntrinsicReferenceModel.defaults();
        final var root = new AbilityOutcomeDescription("root", "IntrinsicRoot",
                Map.of("CheckSVar", "X", "SVarCompare", "GEAlias"), List.of(), token("X"), "");
        final var cases = IntrinsicOutcomeQuantityBinder.bind(root,
                Map.of("X", "Count$CardCounters.P1P1", "Alias", "SVar$X/Plus.1"), model, SOURCE);
        Assert.assertEquals(cases.size(), model.quantities().distribution(Quantity.P1P1_COUNTERS).entries().size());
        for (final var reference : cases) {
            final var bound = reference.value();
            final int x = Integer.parseInt(bound.parameters().get("CheckSVar"));
            Assert.assertEquals(bound.parameters().get("SVarCompare"), "GE" + (x + 1));
            Assert.assertEquals(bound.next().parameters().get("TokenAmount"), Integer.toString(x));
            Assert.assertTrue(IntrinsicAbilityConditions.resolve(bound.parameters()).inactive());
        }
        final var step = new AbilityOutcomeDescription("step", "Draw",
                Map.of("ConditionCheckSVar", "Paid", "ConditionSVarCompare", "GEStart", "NumCards", "1"), List.of(), null, "");
        for (final var reference : IntrinsicOutcomeQuantityBinder.bind(step,
                Map.of("Paid", "Count$xPaid", "Start", "Count$YourStartingLife"), model, SOURCE)) {
            Assert.assertEquals(reference.value().parameters().get("ConditionSVarCompare"), "GE20");
            Assert.assertTrue(reference.value().parameters().get("ConditionCheckSVar").matches("\\d+"));
        }
        final var mutable = new AbilityOutcomeDescription("mutable", "Draw", Map.of("NumCards", "Hand",
                "ConditionLifeTotal", "You", "ConditionLifeAmount", "GEHand"), List.of(), null, "");
        for (final var reference : IntrinsicOutcomeQuantityBinder.bind(mutable,
                Map.of("Hand", "Count$ValidHand Card.YouOwn"), model, SOURCE)) {
            // The same expression is bound for an amount but must not freeze a mutable step guard.
            Assert.assertEquals(reference.value().parameters().get("ConditionLifeAmount"), "GEHand");
        }
    }

    @Test
    public void variableArithmeticOperandsRetainThePrimitiveBindingAndRejectIndependentInputs() {
        final var model = IntrinsicReferenceModel.defaults();
        final var variables = Map.of("X", "Count$CardCounters.P1P1", "Alias", "SVar$X", "Two", "Number$2",
                "Sum", "SVar$X/Plus.Alias", "Squared", "SVar$X/Times.X", "Reverse", "Number$8/Minus.X");
        final var first = token("X");
        final var tree = new AbilityOutcomeDescription(first.path(), first.api(), first.parameters(),
                List.of(token("Sum"), token("Squared"), token("Reverse")), token("X/Times.Two"), "");
        final var cases = IntrinsicOutcomeQuantityBinder.bind(tree, variables, model, SOURCE);
        Assert.assertEquals(cases.size(), model.quantities().distribution(Quantity.P1P1_COUNTERS).entries().size());
        for (final var reference : cases) {
            final var outcome = reference.value();
            final int x = Integer.parseInt(outcome.parameters().get("TokenAmount"));
            Assert.assertEquals(outcome.choices().get(0).parameters().get("TokenAmount"), Integer.toString(2 * x));
            Assert.assertEquals(outcome.choices().get(1).parameters().get("TokenAmount"), Integer.toString(x * x));
            Assert.assertEquals(outcome.choices().get(2).parameters().get("TokenAmount"), Integer.toString(8 - x));
            Assert.assertEquals(outcome.next().parameters().get("TokenAmount"), Integer.toString(2 * x));
        }
        Assert.assertTrue(IntrinsicQuantityResolver.resolve("X/Plus.Y", Map.of("X", "Count$CardCounters.P1P1",
                "Y", "Count$ValidGraveyard Card.YouOwn"), model, SOURCE).isEmpty());
        Assert.assertTrue(IntrinsicQuantityResolver.resolve("X", Map.of("X", "SVar$Y/Plus.1", "Y", "SVar$X"), model, SOURCE).isEmpty());
        Assert.assertTrue(IntrinsicQuantityResolver.resolve("X/Mod.X", variables, model, SOURCE).isEmpty());
        Assert.assertEquals(IntrinsicQuantityResolver.resolve("Number$5/DivideEvenlyUp.2", variables, model, SOURCE)
                .orElseThrow().values().entries().get(0).value().intValue(), 3);
        Assert.assertEquals(IntrinsicQuantityResolver.resolve("Number$-5/DivideEvenlyUp.2", variables, model, SOURCE)
                .orElseThrow().values().entries().get(0).value().intValue(), -1);
        Assert.assertEquals(IntrinsicQuantityResolver.resolve("Number$5/DivideEvenlyDown.0", variables, model, SOURCE)
                .orElseThrow().values().entries().get(0).value().intValue(), 0);
        Assert.assertEquals(IntrinsicQuantityResolver.resolve("Number$5/Mod.2", variables, model, SOURCE)
                .orElseThrow().values().entries().get(0).value().intValue(), 1);
    }

    @Test
    public void boundedArithmeticMatchesForgeAndPreservesSignedAmounts() {
        final var model = IntrinsicReferenceModel.defaults();
        final var expressions = Map.of("Number$5/HalfUp", 3, "Number$5/HalfDown", 2,
                "Number$5/ThirdUp", 2, "Number$5/ThirdDown", 1,
                "Number$5/LimitMax.3", 3, "Number$5/LimitMin.8", 8,
                "Number$5/NMinus.2", -3, "Number$5/Negative/Abs/Twice", 10,
                "Number$-5/HalfDown", -3, "Number$-5/ThirdUp", -1);
        for (final var expression : expressions.entrySet()) {
            Assert.assertEquals(IntrinsicQuantityResolver.resolve(expression.getKey(), Map.of(), model, SOURCE)
                    .orElseThrow().values().entries().get(0).value(), expression.getValue());
        }
        Assert.assertTrue(IntrinsicQuantityResolver.resolve("Number$-2147483648/Abs", Map.of(), model, SOURCE).isEmpty());
        Assert.assertTrue(IntrinsicQuantityResolver.resolve("Number$5/HalfUp.1", Map.of(), model, SOURCE).isEmpty());
    }

    @Test
    public void dynamicCharacteristicsAndPopulationAliasesShareTheirSamples() {
        final var node = new AbilityOutcomeDescription("pump", "Pump", Map.of("Defined", "Self",
                "NumAtt", "All", "NumDef", "Others"), List.of(), token("All"), "");
        final var cases = IntrinsicOutcomeQuantityBinder.bind(node,
                Map.of("All", "Count$Valid Creature.YouCtrl", "Others", "Count$Valid Creature.Other+YouCtrl"),
                IntrinsicReferenceModel.defaults(), SOURCE);
        Assert.assertEquals(cases.size(), IntrinsicReferenceModel.defaults().friendlyCreatureCounts().entries().size());
        for (final var reference : cases) {
            final int power = Integer.parseInt(reference.value().parameters().get("NumAtt"));
            final int toughness = Integer.parseInt(reference.value().parameters().get("NumDef"));
            Assert.assertEquals(power, toughness + 1);
            Assert.assertEquals(reference.value().next().parameters().get("TokenAmount"), String.valueOf(power));
        }
        final var token = new AbilityOutcomeDescription("scaled-token", "Token", Map.of("TokenScript", "w_1_1_soldier",
                "TokenPower", "X", "TokenToughness", "DoubleX"), List.of(), null, "");
        for (final var reference : IntrinsicOutcomeQuantityBinder.bind(token,
                Map.of("X", "Count$xPaid", "DoubleX", "X/Twice"), IntrinsicReferenceModel.defaults(), SOURCE)) {
            Assert.assertEquals(Integer.parseInt(reference.value().parameters().get("TokenToughness")),
                    2 * Integer.parseInt(reference.value().parameters().get("TokenPower")));
        }
    }

    @Test
    public void zeroResourceAndDamageQuantitiesAreUnderstoodWithoutDeathtouchCasualties() {
        final var source = new PermanentProfile(true, PermanentKind.CREATURE, true, 2, 2, Set.of("Deathtouch"));
        final var settings = IntrinsicEvaluationSettings.defaults();
        final var backend = new IntrinsicDrawOutcomeBackend(settings, source);
        final var creature = new IntrinsicReferenceModel.CreatureProfile(true, 2, 2, Set.of(), false, false);
        final var initial = new IntrinsicDrawOutcomeBackend.State(2, 2, 20, 20, 3, 3, 1, 1,
                creature, creature, source, source, source, null);
        for (final var pair : Map.of("GainLife", "LifeAmount", "LoseLife", "LifeAmount", "Mana", "Amount",
                "Discard", "NumCards", "DealDamage", "NumDmg", "DamageAll", "NumDmg").entrySet()) {
            final var parameters = new java.util.HashMap<String, String>();
            parameters.put(pair.getValue(), "0");
            if (pair.getKey().equals("Mana")) { parameters.put("Produced", "Any"); }
            if (pair.getKey().equals("DealDamage")) { parameters.put("ValidTgts", "Creature.OppCtrl"); }
            else if (pair.getKey().equals("DamageAll")) { parameters.put("ValidCards", "Creature"); }
            else { parameters.put("Defined", "You"); }
            final var node = new AbilityOutcomeDescription("zero", pair.getKey(), parameters, List.of(), null, "");
            final var plan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>().evaluate(
                    new OutcomeDescriptionCompiler<>(backend).compile(node), initial);
            Assert.assertEquals(plan.completeness(), OutcomePlan.Completeness.COMPLETE, pair.getKey());
            Assert.assertEquals(plan.value(), 0.0, pair.getKey());
            Assert.assertTrue(plan.state().opponentCreature().present(), pair.getKey());
            Assert.assertTrue(plan.state().sourcePermanent().present(), pair.getKey());
        }
    }

    @Test
    public void damageGroupsRespectSourceDeathtouchAndIndestructibility() {
        final var creature = new IntrinsicReferenceModel.CreatureProfile(true, 3, 3, Set.of(), false, false);
        final var node = new AbilityOutcomeDescription("damage", "DamageAll", Map.of("NumDmg", "1",
                "ValidCards", "Creature"), List.of(), null, "");
        for (final boolean indestructible : List.of(false, true)) {
            final var keywords = indestructible ? Set.of("Deathtouch", "Indestructible") : Set.of("Deathtouch");
            final var source = new PermanentProfile(true, PermanentKind.CREATURE, true, 2, 2, keywords);
            final var initial = new IntrinsicDrawOutcomeBackend.State(2, 2, 20, 20, 3, 3, 1, 1,
                    creature, creature, source, source, source, null);
            final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults(), source);
            final var result = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>().evaluate(
                    new OutcomeDescriptionCompiler<>(backend).compile(node), initial);
            Assert.assertEquals(result.completeness(), OutcomePlan.Completeness.COMPLETE);
            Assert.assertFalse(result.state().opponentCreature().present());
            Assert.assertEquals(result.state().sourcePermanent().present(), indestructible);
        }
    }

    @Test
    public void conditionalStaticRecipientsUseSharedCounterProbabilityAndTribalNormalization() {
        final var model = IntrinsicReferenceModel.defaults();
        final var gated = IntrinsicStaticRecipientFilter.describe("Creature.YouCtrl+counters_GE1_P1P1", model);
        Assert.assertEquals(gated.affected(), "Creature.YouCtrl");
        Assert.assertEquals(gated.probability(), .65, 1e-9);
        Assert.assertEquals(IntrinsicStaticRecipientFilter.describe("Card.Merfolk+Other", model).affected(),
                "Creature.Merfolk+Other");
        Assert.assertEquals(IntrinsicStaticRecipientFilter.describe("Creature.YouCtrl+counters_GE99_P1P1", model).probability(), 0.0);
        Assert.assertEquals(IntrinsicStaticRecipientFilter.describe("Creature.YouCtrl+counters_GE1_OIL", model).affected(),
                "Creature.YouCtrl+counters_GE1_OIL");
        final var abilities = new IntrinsicAbilityEvaluator(model, IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard("Abzan Falconer"), CardStateName.Original);
        final var flying = abilities.stream().filter(value -> value.path().endsWith("static:0")).findFirst().orElseThrow();
        Assert.assertEquals(flying.outcomeStatus(), SupportStatus.SUPPORTED);
        Assert.assertTrue(flying.contribution().value() > 0);
    }

    @Test
    public void unknownAndCyclicExpressionsRemainUnresolved() {
        final var model = IntrinsicReferenceModel.defaults();
        Assert.assertTrue(IntrinsicQuantityResolver.resolve("X", Map.of("X", "Y", "Y", "X"), model, SOURCE).isEmpty());
        Assert.assertTrue(IntrinsicQuantityResolver.resolve("TriggeredCard$CardPower", Map.of(), model, SOURCE).isEmpty());
        Assert.assertEquals(IntrinsicOutcomeQuantityBinder.bind(token("Mystery"), Map.of(), model, SOURCE)
                .get(0).value().parameters().get("TokenAmount"), "Mystery");
    }

    @Test
    public void populationCountsIncludeSourceOnlyWhenItIsACreature() {
        final var model = IntrinsicReferenceModel.defaults();
        final var creature = IntrinsicQuantityResolver.resolve("Count$Valid Creature.YouCtrl", Map.of(), model, SOURCE).orElseThrow();
        final var artifact = IntrinsicQuantityResolver.resolve("Count$Valid Creature.YouCtrl", Map.of(), model,
                new PermanentProfile(true, PermanentKind.ARTIFACT, true, 0, 0, Set.of())).orElseThrow();
        Assert.assertEquals(creature.values().entries().get(0).value().intValue(), 1);
        Assert.assertEquals(artifact.values().entries().get(0).value().intValue(), 0);
    }

    @Test
    public void unsupportedCasesRetainTheirProbabilityInsteadOfRenormalizingValue() {
        final var supported = new AbilityValue("ability", 1,
                new IntrinsicReferenceAggregate(100, 1, 0, 0, 0, 0, List.of()), SupportStatus.SUPPORTED, SupportStatus.SUPPORTED);
        final var unsupported = new AbilityValue("ability", 1,
                new IntrinsicReferenceAggregate(0, 0, 0, 0, 1, 0, List.of("unknown")), SupportStatus.SUPPORTED, SupportStatus.UNSUPPORTED);
        final var combined = IntrinsicAbilityValueAggregator.aggregate(List.of(new WeightedValue<>(supported, .75),
                new WeightedValue<>(unsupported, .25)));
        Assert.assertEquals(combined.contribution().value(), 75.0);
        Assert.assertEquals(combined.contribution().unsupportedCaseProbability(), .25);
        Assert.assertEquals(combined.outcomeStatus(), SupportStatus.PARTIAL);
    }

    @Test
    public void sorcerySpeedActivationCannotBeUsedOnFlashEntryDuringOpponentTurn() {
        final var model = IntrinsicReferenceModel.defaults();
        final var source = new PermanentProfile(true, PermanentKind.ARTIFACT, true, 0, 0, Set.of("Flash"));
        final var settings = IntrinsicEvaluationSettings.defaults();
        // Use a cost below the global occurrence cap so its first-turn difference is observable.
        final var fast = IntrinsicActivationOccurrenceEstimator.estimate(4, false, 0, source, model,
                settings, EntryTiming.FLASH_LATE_TURN, false);
        final var sorcery = IntrinsicActivationOccurrenceEstimator.estimate(4, false, 0, source, model,
                settings, EntryTiming.FLASH_LATE_TURN, true);
        Assert.assertTrue(fast.currentTurnUses() > 0);
        Assert.assertEquals(sorcery.currentTurnUses(), 0.0);
        Assert.assertEquals(fast.expectedOccurrences() - sorcery.expectedOccurrences(), fast.currentTurnUses(), 1e-9);
        final var outlast = new IntrinsicAbilityEvaluator(model, settings).evaluateDefinition(
                FModel.getMagicDb().getCommonCards().getCard("Abzan Falconer"), CardStateName.Original).stream()
                .filter(value -> value.path().endsWith("ability:0")).findFirst().orElseThrow();
        Assert.assertEquals(outlast.outcomeStatus(), SupportStatus.SUPPORTED);
        Assert.assertEquals(outlast.currentTurnUses(), 0.0); // Creature tap cost: summoning sickness.
        Assert.assertTrue(outlast.contribution().value() > 0);
    }

    @Test
    public void variableSourceCharacteristicsUsePopulationCasesWithoutDuplicateBodyCredit() {
        final var card = FModel.getMagicDb().getCommonCards().getCard("Adeline, Resplendent Cathar");
        final var state = CardAbilityTraversal.definitionState(card, CardStateName.Original);
        final var source = new PermanentProfile(true, PermanentKind.CREATURE, true, 0, 4, Set.of("Vigilance"));
        final var profiles = IntrinsicSourceProfileResolver.resolve(state, source, IntrinsicReferenceModel.defaults()).orElseThrow();
        Assert.assertEquals(profiles.size(), 6);
        Assert.assertEquals(profiles.get(0).value().power(), 1);
        Assert.assertEquals(profiles.get(5).value().power(), 6);
        Assert.assertTrue(profiles.stream().allMatch(reference -> reference.value().toughness() == 4));
        final var model = IntrinsicReferenceModel.defaults();
        for (final var reference : IntrinsicSourceProfileResolver.resolveCases(state, source, model).orElseThrow()) {
            final var bound = IntrinsicOutcomeQuantityBinder.bind(token("X"),
                    Map.of("X", "Count$Valid Creature.YouCtrl"), model, reference.value().profile(),
                    reference.value().quantities());
            Assert.assertEquals(bound.size(), 1); // The CDA population is not sampled a second time.
            Assert.assertEquals(bound.get(0).weight(), 1.0);
            Assert.assertEquals(bound.get(0).value().parameters().get("TokenAmount"),
                    String.valueOf(reference.value().profile().power()));
        }
        final var evaluated = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(card, CardStateName.Original);
        final var characteristic = evaluated.stream().filter(value -> value.path().endsWith("static:0")).findFirst().orElseThrow();
        Assert.assertEquals(characteristic.outcomeStatus(), SupportStatus.SUPPORTED);
        Assert.assertEquals(characteristic.contribution().value(), 0.0);
        final var attack = evaluated.stream().filter(value -> value.path().endsWith("trigger:0")).findFirst().orElseThrow();
        Assert.assertEquals(attack.outcomeStatus(), SupportStatus.SUPPORTED);
        Assert.assertTrue(attack.contribution().value() > 0);
        // Population CDAs and one-opponent repetition are structural adapters, not card-name dispatch.
        Assert.assertFalse(evaluated.stream().anyMatch(value -> value.contribution().unresolvedReasons().stream()
                .anyMatch(reason -> reason.contains("Variable creature characteristics"))));
    }

    @Test
    public void selfDeathTriggersUseDepartureOccurrenceAndLastKnownSourceBindings() {
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        for (final String name : List.of("Doomed Traveler", "Conclave Mentor", "Perilous Myr")) {
            final var ability = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard(name), CardStateName.Original)
                    .stream().filter(value -> value.path().endsWith("trigger:0")).findFirst().orElseThrow();
            Assert.assertEquals(ability.triggerStatus(), SupportStatus.SUPPORTED, name);
            Assert.assertEquals(ability.outcomeStatus(), SupportStatus.SUPPORTED, name);
            Assert.assertTrue(ability.expectedOccurrences() > 0 && ability.expectedOccurrences() <= 1, name);
            Assert.assertTrue(ability.contribution().value() > 0, name);
        }
        final var parameters = Map.of("Mode", "ChangesZone", "Origin", "Battlefield", "Destination", "Graveyard",
                "ValidCard", "Card.Self");
        Assert.assertTrue(IntrinsicSelfDeathTriggerAdapter.supports(parameters));
        final var conditional = new java.util.HashMap<>(parameters);
        conditional.put("ConditionPresent", "Creature.YouCtrl");
        Assert.assertFalse(IntrinsicSelfDeathTriggerAdapter.supports(conditional));
        Assert.assertEquals(IntrinsicSelfDeathTriggerAdapter.bindSourceQuantities(Map.of("P", "TriggeredCard$CardPower/Twice",
                "Other", "TriggeredCard$CardManaCost")), Map.of("P", "Count$CardPower/Twice", "Other", "TriggeredCard$CardManaCost"));
    }

    @Test
    public void departedSourceCannotReceiveCountersFromItsDeathTrigger() {
        final var parameters = Map.of("Mode", "ChangesZone", "Origin", "Battlefield", "Destination", "Graveyard",
                "ValidCard", "Card.Self");
        final var node = new AbilityOutcomeDescription("death-counter", "PutCounter", Map.of("Defined", "Self",
                "CounterType", "P1P1", "CounterNum", "1"), List.of(), null, "");
        final var ability = new CardAbilityTraversal.AbilityDescription("death", CardAbilityTraversal.Origin.TRIGGER,
                CardAbilityTraversal.Provenance.PRINTED, parameters, node);
        final var evaluated = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults())
                .evaluate(List.of(ability), SOURCE, EntryTiming.NORMAL_SPEED).get(0);
        Assert.assertEquals(evaluated.outcomeStatus(), SupportStatus.SUPPORTED);
        Assert.assertEquals(evaluated.contribution().value(), 0.0);
        Assert.assertEquals(evaluated.contribution().unavailableCaseProbability(), 1.0);
    }

    @Test
    public void staticDynamicAmountsUseReferenceQuantitiesWithoutHidingOtherUnsupportedAbilities() {
        final var values = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard("Zell Dincht"), CardStateName.Original);
        final var scaling = values.stream().filter(value -> value.path().endsWith("static:1")).findFirst().orElseThrow();
        Assert.assertEquals(scaling.outcomeStatus(), SupportStatus.SUPPORTED);
        Assert.assertTrue(scaling.contribution().value() > 0);
        final var landPlays = values.stream().filter(value -> value.path().endsWith("static:0")).findFirst().orElseThrow();
        Assert.assertEquals(landPlays.triggerStatus(), SupportStatus.UNSUPPORTED);
        Assert.assertEquals(landPlays.outcomeStatus(), SupportStatus.NOT_EVALUATED);
    }

    @Test
    public void fixedDrawerBindingsCoverOwnershipAndPlayerEncodingsWithoutCrossingEventScopes() {
        final var effect = new AbilityOutcomeDescription("loss", "LoseLife",
                Map.of("Defined", "TriggeredPlayer", "LifeAmount", "1"), List.of(), null, "");
        for (final var scope : List.of(Map.of("ValidCard", "Card.YouOwn"), Map.of("ValidCard", "Card.OppOwn"),
                Map.of("ValidPlayer", "You"), Map.of("ValidPlayer", "Player.Opponent"))) {
            final var parameters = new java.util.HashMap<>(scope);
            parameters.put("Mode", "Drawn");
            final var ability = new CardAbilityTraversal.AbilityDescription("draw", CardAbilityTraversal.Origin.TRIGGER,
                    CardAbilityTraversal.Provenance.PRINTED, parameters, effect);
            final var normalized = IntrinsicTriggerBindingNormalizer.normalize(ability);
            final boolean own = scope.containsValue("You") || scope.containsValue("Card.YouOwn");
            Assert.assertEquals(normalized.outcome().parameters().get("Defined"), own ? "You" : "Opponent");
            Assert.assertTrue(IntrinsicEventTriggerAdapter.describe(normalized.parameters()).isPresent());
        }
        for (final var scope : List.of(Map.of("ValidCard", "Card.OppOwn", "ValidPlayer", "You"),
                Map.of("ValidPlayer", "Player"), Map.of("ValidCard", "Card.OppOwn+Creature"))) {
            final var parameters = new java.util.HashMap<>(scope);
            parameters.put("Mode", "Drawn");
            final var ability = new CardAbilityTraversal.AbilityDescription("draw", CardAbilityTraversal.Origin.TRIGGER,
                    CardAbilityTraversal.Provenance.PRINTED, parameters, effect);
            Assert.assertSame(IntrinsicTriggerBindingNormalizer.normalize(ability), ability);
        }
        final var delayed = new AbilityOutcomeDescription("later", "DelayedTrigger", Map.of(), List.of(effect), effect, "");
        final var ability = new CardAbilityTraversal.AbilityDescription("draw", CardAbilityTraversal.Origin.TRIGGER,
                CardAbilityTraversal.Provenance.PRINTED, Map.of("Mode", "Drawn", "ValidCard", "Card.OppOwn"), delayed);
        Assert.assertSame(IntrinsicTriggerBindingNormalizer.normalize(ability).outcome(), delayed);
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        for (final String name : List.of("Underworld Dreams", "Scrawling Crawler")) {
            final var values = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard(name), CardStateName.Original);
            final var loss = values.stream().filter(value -> value.path().endsWith(name.equals("Underworld Dreams") ? "trigger:0" : "trigger:1"))
                    .findFirst().orElseThrow();
            Assert.assertEquals(loss.outcomeStatus(), SupportStatus.SUPPORTED, name);
            Assert.assertTrue(loss.contribution().value() > 0, name);
        }
    }

    @Test
    public void cardControllerDrawBindingsAndCounterScaledTokenDefinitionsAreEvaluated() {
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        final var life = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard("Sheoldred, the Apocalypse"),
                CardStateName.Original);
        Assert.assertEquals(life.size(), 2);
        Assert.assertTrue(life.stream().allMatch(value -> value.outcomeStatus() == SupportStatus.SUPPORTED
                && value.contribution().value() > 0));
        final var tokens = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard("Royal Talon Fighter Jet"),
                CardStateName.Original).stream().filter(value -> value.path().endsWith("trigger:0")).findFirst().orElseThrow();
        Assert.assertEquals(tokens.outcomeStatus(), SupportStatus.SUPPORTED);
        Assert.assertTrue(tokens.contribution().value() > 0);
    }
}
