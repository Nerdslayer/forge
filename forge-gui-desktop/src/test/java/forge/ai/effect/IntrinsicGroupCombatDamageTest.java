package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;
import forge.card.CardStateName;
import forge.model.FModel;

public class IntrinsicGroupCombatDamageTest extends AITest {
    private static IntrinsicReferenceModel withProfiles(final WeightedDistribution<IntrinsicReferenceModel.CreatureProfile> profiles) {
        final var reference = IntrinsicReferenceModel.defaults();
        final var availability = new java.util.EnumMap<PermanentKind, WeightedDistribution<Boolean>>(PermanentKind.class);
        final var events = new java.util.EnumMap<IntrinsicReferenceModel.EventType, WeightedDistribution<Double>>(IntrinsicReferenceModel.EventType.class);
        final var survival = new java.util.EnumMap<PermanentKind, IntrinsicReferenceModel.SurvivalProfile>(PermanentKind.class);
        for (final var kind : PermanentKind.values()) {
            if (reference.targetAvailability(kind) != null) { availability.put(kind, reference.targetAvailability(kind)); }
            survival.put(kind, reference.survivalProfile(kind));
        }
        for (final var type : IntrinsicReferenceModel.EventType.values()) {
            if (reference.eventRates(type) != null) { events.put(type, reference.eventRates(type)); }
        }
        return new IntrinsicReferenceModel(reference.lifeTotals(), reference.handSizes(), reference.availableMana(),
                reference.friendlyCreatureCounts(), reference.opposingCreatureCounts(), profiles, reference.permanentProfiles(),
                availability, events, survival, .02);
    }

    private static Map<String, String> parameters(final String source) {
        return Map.of("Mode", "DamageDone", "ValidSource", source, "ValidTarget", "Player", "CombatDamage", "True");
    }

    @Test
    public void populationAndHostAreSeparateAndOwnersUseTheRightTurnScope() {
        final var model = IntrinsicReferenceModel.defaults().withQuantityBindings(Map.of("otherFriendlyCreatures", 2, "opposingCreatures", 3));
        final var host = new PermanentProfile(true, PermanentKind.CREATURE, true, 3, 3, Set.of());
        final double others = IntrinsicGroupCombatDamageAdapter.describe(parameters("Creature.Other+YouCtrl"), model, host)
                .orElseThrow().occurrenceMultiplier();
        final var all = IntrinsicEventTriggerAdapter.describe(parameters("Creature.YouCtrl"), model, host).orElseThrow();
        Assert.assertEquals(all.occurrenceMultiplier(), others + 1, 1e-9);
        Assert.assertEquals(all.turnScope(), IntrinsicEventTrigger.TurnScope.CONTROLLER_TURN);
        final var opponent = IntrinsicGroupCombatDamageAdapter.describe(parameters("Creature.OppCtrl"), model, host).orElseThrow();
        Assert.assertEquals(opponent.turnScope(), IntrinsicEventTrigger.TurnScope.OPPONENT_TURN);
        Assert.assertEquals(opponent.occurrenceMultiplier(), others * 1.5, 1e-9);
        final var zeroPopulation = model.withQuantityBindings(Map.of("otherFriendlyCreatures", 0));
        Assert.assertEquals(IntrinsicGroupCombatDamageAdapter.describe(parameters("Creature.Other+YouCtrl"), zeroPopulation, host)
                .orElseThrow().occurrenceMultiplier(), 0.0);
    }

    @Test
    public void hostZeroPowerDefenderAndDoubleStrikeAreObserved() {
        final var model = IntrinsicReferenceModel.defaults().withQuantityBindings(Map.of("otherFriendlyCreatures", 0));
        for (final var host : List.of(new PermanentProfile(true, PermanentKind.CREATURE, true, 0, 3, Set.of()),
                new PermanentProfile(true, PermanentKind.CREATURE, true, 3, 3, Set.of("Defender")),
                new PermanentProfile(true, PermanentKind.ENCHANTMENT, true, 3, 3, Set.of()))) {
            Assert.assertEquals(IntrinsicGroupCombatDamageAdapter.describe(parameters("Creature.YouCtrl"), model, host)
                    .orElseThrow().occurrenceMultiplier(), 0.0);
        }
        final var host = new PermanentProfile(true, PermanentKind.CREATURE, true, 3, 3, Set.of("Double strike"));
        Assert.assertEquals(IntrinsicGroupCombatDamageAdapter.describe(parameters("Creature.YouCtrl"), model, host)
                .orElseThrow().occurrenceMultiplier(), 2.0);
    }

    @Test
    public void tribalUnionsDoNotDuplicateAHitAndPrintedHostEligibilityIsExact() {
        final var model = IntrinsicReferenceModel.defaults().withQuantityBindings(Map.of("otherFriendlyCreatures", 2));
        final var state = CardAbilityTraversal.definitionState(FModel.getMagicDb().getCommonCards().getCard("Tovolar, Dire Overlord"),
                CardStateName.Original);
        final var ability = CardAbilityTraversal.inspect(state).stream().filter(entry -> entry.path().endsWith("trigger:0")).findFirst().orElseThrow();
        final var prepared = IntrinsicGroupCombatDamageAdapter.prepare(ability, state.getType());
        final var host = new PermanentProfile(true, PermanentKind.CREATURE, true, 3, 3, Set.of());
        final double others = IntrinsicGroupCombatDamageAdapter.describe(parameters("Creature.Other+YouCtrl"), model, host)
                .orElseThrow().occurrenceMultiplier();
        Assert.assertEquals(IntrinsicGroupCombatDamageAdapter.describe(prepared.parameters(), model, host)
                .orElseThrow().occurrenceMultiplier(), others * .75 + 1, 1e-9);
        final var duplicate = new java.util.HashMap<>(prepared.parameters());
        duplicate.put("ValidSource", "Werewolf.YouCtrl,Werewolf.YouCtrl");
        Assert.assertEquals(IntrinsicGroupCombatDamageAdapter.describe(duplicate, model, host)
                .orElseThrow().occurrenceMultiplier(), others * .5 + 1, 1e-9);
        final var noMatch = CardAbilityTraversal.definitionState(FModel.getMagicDb().getCommonCards().getCard("Slimefoot, the Stowaway"),
                CardStateName.Original);
        final var nonmatching = IntrinsicGroupCombatDamageAdapter.prepare(ability, noMatch.getType());
        Assert.assertEquals(IntrinsicGroupCombatDamageAdapter.describe(nonmatching.parameters(), model, host)
                .orElseThrow().occurrenceMultiplier(), others * .75, 1e-9);
        final var conditionalParameters = new java.util.HashMap<>(ability.parameters());
        conditionalParameters.put("CheckSVar", "Population");
        conditionalParameters.put("SVarCompare", "GE1");
        final var conditional = new CardAbilityTraversal.AbilityDescription(ability.path(), ability.origin(), ability.provenance(),
                conditionalParameters, ability.outcome());
        final var preparedConditional = new java.util.HashMap<>(IntrinsicGroupCombatDamageAdapter.prepare(conditional, state.getType()).parameters());
        // Simulate a supported root condition having been evaluated. Printed host eligibility
        // must survive that step rather than being lost because the original trigger was gated.
        preparedConditional.remove("CheckSVar");
        preparedConditional.remove("SVarCompare");
        Assert.assertEquals(IntrinsicGroupCombatDamageAdapter.describe(preparedConditional,
                model.withQuantityBindings(Map.of("otherFriendlyCreatures", 0)), host).orElseThrow().occurrenceMultiplier(), 1.0);
    }

    @Test
    public void mixedOwnershipAndRicherFiltersStayUnresolved() {
        for (final String validity : List.of("Creature", "Creature.YouCtrl+Red", "Creature.YouCtrl+token",
                "Wolf.YouCtrl,Werewolf.OppCtrl", "Creature.Other+YouCtrl,Wolf.YouCtrl", "Creature.YouCtrl+OppCtrl")) {
            Assert.assertTrue(IntrinsicGroupCombatDamageAdapter.describe(parameters(validity), IntrinsicReferenceModel.defaults(), null)
                    .isEmpty(), validity);
        }
    }

    @Test
    public void donorDamageMixturesUseHitWeightsAndTheBoundPopulation() {
        final var profiles = WeightedDistribution.of(
                new WeightedValue<>(new IntrinsicReferenceModel.CreatureProfile(true, 2, 2, Set.of(), false, false), .5),
                new WeightedValue<>(new IntrinsicReferenceModel.CreatureProfile(true, 4, 4, Set.of("Double strike"), false, false), .5));
        final var model = withProfiles(profiles).withQuantityBindings(Map.of("otherFriendlyCreatures", 2));
        final var host = new PermanentProfile(true, PermanentKind.CREATURE, true, 7, 7, Set.of());
        final var ability = new CardAbilityTraversal.AbilityDescription("trigger", CardAbilityTraversal.Origin.TRIGGER,
                CardAbilityTraversal.Provenance.PRINTED, parameters("Creature.YouCtrl"),
                new AbilityOutcomeDescription("draw", "Draw", Map.of("Defined", "You", "NumCards", "X"), List.of(), null, ""));
        final var variables = Map.of("X", "TriggerCount$DamageAmount");
        final var cases = IntrinsicGroupCombatDamageAdapter.amountCases(ability, variables, model, host).orElseThrow();
        Assert.assertEquals(cases.size(), 3);
        final Map<Integer, Double> expected = Map.of(2, .25, 4, .50, 7, .25);
        for (final var reference : cases) {
            Assert.assertEquals(reference.weight(), expected.get(reference.value().amount()), 1e-9);
        }
        final var hostOnly = model.withQuantityBindings(Map.of("otherFriendlyCreatures", 0));
        final var only = IntrinsicGroupCombatDamageAdapter.amountCases(ability, variables, hostOnly, host).orElseThrow();
        Assert.assertEquals(only.size(), 1);
        Assert.assertEquals(only.get(0).value().amount(), 7);
        final var otherOnly = new CardAbilityTraversal.AbilityDescription(ability.path(), ability.origin(), ability.provenance(),
                parameters("Creature.Other+YouCtrl"), ability.outcome());
        Assert.assertTrue(IntrinsicGroupCombatDamageAdapter.amountCases(otherOnly, variables, hostOnly, host).orElseThrow().isEmpty());
    }

    @Test
    public void trampleAndNonconnectingProfilesShareOccurrenceAndAmountEligibility() {
        final var profiles = WeightedDistribution.of(
                new WeightedValue<>(new IntrinsicReferenceModel.CreatureProfile(false, 0, 0, Set.of(), false, false), .2),
                new WeightedValue<>(new IntrinsicReferenceModel.CreatureProfile(true, 0, 4, Set.of(), false, false), .2),
                new WeightedValue<>(new IntrinsicReferenceModel.CreatureProfile(true, 4, 4, Set.of("Defender"), false, false), .2),
                new WeightedValue<>(new IntrinsicReferenceModel.CreatureProfile(true, 4, 4, Set.of("Trample"), false, false), .4));
        final var model = withProfiles(profiles).withQuantityBindings(Map.of("otherFriendlyCreatures", 2));
        final var host = new PermanentProfile(true, PermanentKind.ENCHANTMENT, true, 0, 0, Set.of());
        final var ability = new CardAbilityTraversal.AbilityDescription("trigger", CardAbilityTraversal.Origin.TRIGGER,
                CardAbilityTraversal.Provenance.PRINTED, parameters("Creature.YouCtrl"), null);
        final var cases = IntrinsicGroupCombatDamageAdapter.amountCases(ability, Map.of("X", "TriggerCount$DamageAmount"), model, host).orElseThrow();
        Assert.assertEquals(cases.stream().mapToDouble(entry -> entry.value().amount() * entry.weight()).sum(), 2.8, 1e-9);
        Assert.assertEquals(IntrinsicGroupCombatDamageAdapter.describe(ability.parameters(), model, host)
                .orElseThrow().occurrenceMultiplier(), 1.0, 1e-9);
    }

    @Test
    public void definitionDonorMixtureSharesTheRootConditionPopulation() {
        final var profile = new IntrinsicReferenceModel.CreatureProfile(true, 2, 2, Set.of(), false, false);
        final var model = withProfiles(WeightedDistribution.of(new WeightedValue<>(profile, 1)));
        final var definition = new forge.item.PaperCard(forge.card.CardRules.fromScript(List.of(
                "Name:Conditional Group Damage Probe", "ManaCost:3 G", "Types:Creature Elf", "PT:7/7",
                "T:Mode$ DamageDone | ValidSource$ Creature.YouCtrl | ValidTarget$ Player | CombatDamage$ True | CheckSVar$ Population | SVarCompare$ GE2 | Execute$ Benefit",
                "SVar:Population:Count$Valid Creature.Other+YouCtrl", "SVar:X:TriggerCount$DamageAmount",
                "SVar:Benefit:DB$ Draw | Defined$ You | NumCards$ X", "Oracle:Conditional combat draw.")),
                forge.card.CardEdition.UNKNOWN_CODE, forge.card.CardRarity.Special);
        final var settings = IntrinsicEvaluationSettings.defaults();
        final var actual = new IntrinsicAbilityEvaluator(model, settings).evaluateDefinition(definition, CardStateName.Original).get(0);
        double expected = 0;
        double inactive = 0;
        for (final var population : model.friendlyCreatureCounts().entries()) {
            final var fixed = model.withQuantityBindings(Map.of("otherFriendlyCreatures", population.value()));
            final var value = new IntrinsicAbilityEvaluator(fixed, settings).evaluateDefinition(definition, CardStateName.Original).get(0);
            expected += population.weight() * value.contribution().value();
            if (population.value() < 2) { inactive += population.weight(); }
        }
        Assert.assertEquals(actual.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(actual.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(actual.contribution().value(), expected, 1e-7);
        Assert.assertEquals(actual.contribution().unavailableCaseProbability(), inactive, 1e-9);
    }

    @Test
    public void sharedCombatDrawsAndDamageScaledTokenOutcomesReachExistingEvaluation() {
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        for (final String name : List.of("Toski, Bearer of Secrets", "Tovolar, Dire Overlord")) {
            final var value = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard(name), CardStateName.Original)
                    .stream().filter(entry -> entry.path().endsWith("trigger:0")).findFirst().orElseThrow();
            Assert.assertEquals(value.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED, name);
            Assert.assertEquals(value.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED, name);
            Assert.assertTrue(value.contribution().value() > 0, name);
        }
        final var gnawbone = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard("Old Gnawbone"), CardStateName.Original)
                .stream().filter(entry -> entry.path().endsWith("trigger:0")).findFirst().orElseThrow();
        Assert.assertEquals(gnawbone.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(gnawbone.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(gnawbone.contribution().value() > 0);
    }
}
