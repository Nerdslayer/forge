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

public class IntrinsicOutgoingCombatDamageBindingTest extends AITest {
    private static CardAbilityTraversal.AbilityDescription trigger(final String target) {
        return new CardAbilityTraversal.AbilityDescription("trigger", CardAbilityTraversal.Origin.TRIGGER,
                CardAbilityTraversal.Provenance.PRINTED, Map.of("Mode", "DamageDone", "ValidSource", "Card.Self",
                        "CombatDamage", "True", "ValidTarget", target),
                new AbilityOutcomeDescription("draw", "Draw", Map.of("Defined", "You", "NumCards", "X"), List.of(), null, ""));
    }

    @Test
    public void connectingDamageUsesSourcePowerAndDoubleStrikeCountsSeparateEvents() {
        final var source = new PermanentProfile(true, PermanentKind.CREATURE, true, 5, 3, Set.of());
        final var ability = trigger("Player");
        final var variables = Map.of("X", "TriggerCount$DamageAmount");
        final var cases = IntrinsicOutgoingCombatDamageBinding.cases(ability, variables, source).orElseThrow();
        Assert.assertEquals(cases.size(), 1);
        Assert.assertEquals(cases.get(0).value().amount(), 5);
        Assert.assertEquals(cases.get(0).value().bindVariables(variables).get("X"), "Number$5");
        final var doubleStrike = new PermanentProfile(true, PermanentKind.CREATURE, true, 5, 3, Set.of("Double strike"));
        Assert.assertEquals(IntrinsicOutgoingCombatDamageBinding.cases(ability, variables, doubleStrike)
                .orElseThrow().get(0).value().amount(), 5);
        Assert.assertEquals(IntrinsicEventTriggerAdapter.describe(ability.parameters(), IntrinsicReferenceModel.defaults(),
                doubleStrike).orElseThrow().occurrenceMultiplier(), 2.0);
        Assert.assertEquals(IntrinsicEventTriggerAdapter.describe(ability.parameters(), IntrinsicReferenceModel.defaults(),
                source).orElseThrow().turnScope(), IntrinsicEventTrigger.TurnScope.CONTROLLER_TURN);
    }

    @Test
    public void trampleRetainsPartialHitsAndZeroPowerDoesNotConnect() {
        final var source = new PermanentProfile(true, PermanentKind.CREATURE, true, 4, 4, Set.of("Trample"));
        final var variables = Map.of("X", "TriggerCount$DamageAmount");
        final var cases = IntrinsicOutgoingCombatDamageBinding.cases(trigger("Opponent"), variables, source).orElseThrow();
        Assert.assertEquals(cases.stream().mapToDouble(WeightedValue::weight).sum(), 1.0, 1e-9);
        Assert.assertEquals(cases.stream().mapToDouble(entry -> entry.value().amount() * entry.weight()).sum(), 2.8, 1e-9);
        final var zero = new PermanentProfile(true, PermanentKind.CREATURE, true, 0, 4, Set.of());
        Assert.assertTrue(IntrinsicOutgoingCombatDamageBinding.cases(trigger("Player"), variables, zero).orElseThrow().isEmpty());
        Assert.assertEquals(IntrinsicOutgoingCombatDamageBinding.describe(trigger("Player").parameters(), zero)
                .orElseThrow().occurrenceMultiplier(), 0.0);
    }

    @Test
    public void unrelatedSourcesAndCombatFiltersAreNotGuessed() {
        for (final var extra : List.of(Map.of("ValidSource", "Creature.YouCtrl"), Map.of("CombatDamage", "False"),
                Map.of("ValidTarget", "Battle"), Map.of("DamageAmount", "GE3"), Map.of("Mode", "DamageDealtOnce"))) {
            final var parameters = new java.util.HashMap<>(trigger("Player").parameters());
            parameters.putAll(extra);
            Assert.assertTrue(IntrinsicOutgoingCombatDamageBinding.describe(parameters, null).isEmpty());
        }
    }

    @Test
    public void battleAlternativeIsReportedAsPartialAndUnsupportedOutcomesStayUnsupported() {
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        final var warrior = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard("Doomskar Warrior"),
                CardStateName.Original).stream().filter(entry -> entry.path().endsWith("trigger:0")).findFirst().orElseThrow();
        Assert.assertEquals(warrior.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.PARTIAL);
        Assert.assertTrue(warrior.contribution().value() > 0);
        Assert.assertFalse(warrior.contribution().complete());
        Assert.assertEquals(warrior.contribution().knownCaseProbability(), 0.0);
        Assert.assertTrue(warrior.contribution().unresolvedReasons().stream().anyMatch(reason -> reason.contains("battle")));
        final var obeka = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard("Obeka, Splitter of Seconds"),
                CardStateName.Original).stream().filter(entry -> entry.path().endsWith("trigger:0")).findFirst().orElseThrow();
        Assert.assertEquals(obeka.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(obeka.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.UNSUPPORTED);
        Assert.assertEquals(obeka.contribution().value(), 0.0);
    }
}
