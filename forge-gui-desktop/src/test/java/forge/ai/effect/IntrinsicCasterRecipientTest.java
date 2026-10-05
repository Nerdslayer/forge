package forge.ai.effect;

import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

public class IntrinsicCasterRecipientTest extends forge.ai.AITest {
    private static AbilityOutcomeDescription draw(final String recipient, final AbilityOutcomeDescription next) {
        return new AbilityOutcomeDescription("draw", "Draw", Map.of("Defined", recipient, "NumCards", "1"), List.of(), next, "");
    }

    private static CardAbilityTraversal.AbilityDescription trigger(final Map<String, String> parameters,
            final AbilityOutcomeDescription outcome) {
        return new CardAbilityTraversal.AbilityDescription("trigger", CardAbilityTraversal.Origin.TRIGGER,
                CardAbilityTraversal.Provenance.PRINTED, parameters, outcome);
    }

    @Test
    public void fixedCastersBindRecipientsWithoutChangingOccurrencesOrOriginalDescriptions() {
        for (final String mode : List.of("SpellCast", "AbilityCast")) {
            for (final String player : List.of("You", "Opponent", "Player.Opponent")) {
                final var ability = trigger(Map.of("Mode", mode, "ValidActivatingPlayer", player), draw("TriggeredActivator", null));
                final var normalized = IntrinsicTriggerBindingNormalizer.normalize(ability);
                final String expected = "You".equals(player) ? "You" : "Opponent";
                Assert.assertEquals(normalized.outcome().parameters().get("Defined"), expected);
                Assert.assertEquals(normalized.parameters(), ability.parameters());
                Assert.assertEquals(ability.outcome().parameters().get("Defined"), "TriggeredActivator");
                final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults());
                final var plan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>().evaluate(
                        new OutcomeDescriptionCompiler<>(backend).compile(normalized.outcome()),
                        new IntrinsicDrawOutcomeBackend.State(3, 3));
                Assert.assertTrue(plan.complete());
                Assert.assertTrue("You".equals(player) ? plan.value() > 0 : plan.value() < 0);
            }
        }
        Assert.assertEquals(IntrinsicAbilityCastTriggerAdapter.describe(Map.of("Mode", "AbilityCast",
                "ValidActivatingPlayer", "Player.Opponent")).orElseThrow().turnScope(),
                IntrinsicEventTrigger.TurnScope.OPPONENT_TURN);
    }

    @Test
    public void eventControllerBindingsCoverTokenOwnersAndSearchPlayersWithoutTouchingObjectFields() {
        final var parameters = Map.of("Defined", "TriggeredCardController", "TokenOwner", "TriggeredCardController",
                "DefinedPlayer", "TriggeredCardController", "ValidTgts", "TriggeredCardController",
                "DamageSource", "TriggeredCard", "TokenScript", "TriggeredCardControllerToken");
        final var bound = IntrinsicTriggerBindingNormalizer.bindRecipientParameters(parameters, "Opponent",
                java.util.Set.of("TriggeredCardController"));
        for (final String field : List.of("Defined", "TokenOwner", "DefinedPlayer")) {
            Assert.assertEquals(bound.get(field), "Opponent");
            Assert.assertEquals(parameters.get(field), "TriggeredCardController");
        }
        for (final String field : List.of("ValidTgts", "DamageSource", "TokenScript")) {
            Assert.assertEquals(bound.get(field), parameters.get(field));
        }
        final var token = new AbilityOutcomeDescription("token", "Token",
                Map.of("TokenOwner", "TriggeredCardController", "TokenScript", "g_1_1_saproling"), List.of(), null, "");
        final var normalized = IntrinsicTriggerBindingNormalizer.normalize(trigger(Map.of("Mode", "ChangesZone",
                "Origin", "Battlefield", "Destination", "Graveyard", "ValidCard", "Card.Self"), token));
        Assert.assertEquals(normalized.outcome().parameters().get("TokenOwner"), "You");
        final var delayed = new AbilityOutcomeDescription("delayed", "DelayedTrigger", token.parameters(), List.of(), token, "");
        final var nested = IntrinsicTriggerBindingNormalizer.normalize(trigger(Map.of("Mode", "ChangesZone",
                "Origin", "Battlefield", "Destination", "Graveyard", "ValidCard", "Card.Self"), delayed));
        Assert.assertSame(nested.outcome(), delayed);
    }

    @Test
    public void targetedSpellAbilityControllerIsNotItsSourceCardController() {
        for (final String mode : List.of("BecomesTarget", "BecomesTargetOnce")) {
            final var outcome = draw("You & TriggeredSourceSAController", draw("TriggeredSourceController", null));
            final var normalized = IntrinsicTriggerBindingNormalizer.normalize(trigger(
                    Map.of("Mode", mode, "ValidSource", "SpellAbility.OppCtrl", "ValidTarget", "Card.Self"), outcome));
            Assert.assertEquals(normalized.outcome().parameters().get("Defined"), "You & Opponent");
            Assert.assertEquals(normalized.outcome().next().parameters().get("Defined"), "TriggeredSourceController");
        }
    }

    @Test
    public void unknownRecipientsObjectsAndNestedEventsRemainUnresolved() {
        final var nested = new AbilityOutcomeDescription("nested", "ImmediateTrigger", Map.of(), List.of(),
                draw("TriggeredActivator", null), "");
        final var normalized = IntrinsicTriggerBindingNormalizer.normalize(trigger(
                Map.of("Mode", "SpellCast", "ValidActivatingPlayer", "You"), draw("TriggeredActivator", nested)));
        Assert.assertEquals(normalized.outcome().next().next().parameters().get("Defined"), "TriggeredActivator");
        for (final String defined : List.of("TriggeredCard", "TriggeredActivator & TriggeredPlayer", "TriggeredActivatorOpponent")) {
            Assert.assertEquals(IntrinsicTriggerBindingNormalizer.normalize(trigger(
                    Map.of("Mode", "AbilityCast", "ValidActivatingPlayer", "You"), draw(defined, null)))
                    .outcome().parameters().get("Defined"), defined);
        }
        Assert.assertEquals(IntrinsicTriggerBindingNormalizer.normalize(trigger(Map.of("Mode", "SpellCast"),
                draw("TriggeredActivator", null))).outcome().parameters().get("Defined"), "TriggeredActivator");
        Assert.assertEquals(IntrinsicTriggerBindingNormalizer.normalize(trigger(
                Map.of("Mode", "BecomesTarget", "ValidSource", "Spell"), draw("TriggeredSourceSAController", null)))
                .outcome().parameters().get("Defined"), "TriggeredSourceSAController");
    }

    @Test
    public void weightedCastersShareOneIdentityAcrossSequenceAndPreserveOccurrenceScope() {
        final var ability = trigger(Map.of("Mode", "AbilityCast"), draw("TriggeredActivator", draw("TriggeredActivator", null)));
        final var model = IntrinsicReferenceModel.defaults();
        final var cases = IntrinsicTriggerBindingNormalizer.recipientCases(ability, model).orElseThrow();
        Assert.assertEquals(cases.stream().mapToDouble(WeightedValue::weight).sum(), 1.0, 1e-9);
        for (final var reference : cases) {
            final var bound = reference.value();
            Assert.assertEquals(bound.parameters(), ability.parameters());
            Assert.assertEquals(bound.outcome().parameters().get("Defined"), bound.outcome().next().parameters().get("Defined"));
            Assert.assertTrue(IntrinsicTriggerBindingNormalizer.recipientCases(bound, model).isEmpty());
        }
        final var ownOnly = model.withQuantities(model.quantities().with(
                IntrinsicReferenceQuantities.Quantity.EVENT_CASTER_IS_OPPONENT,
                new WeightedDistribution<>(List.of(new WeightedValue<>(0, 1.0)))));
        Assert.assertEquals(IntrinsicTriggerBindingNormalizer.recipientCases(ability, ownOnly).orElseThrow()
                .get(0).value().outcome().parameters().get("Defined"), "You");
    }

    @Test
    public void spellHostControllerBindingUsesTargetingPriorButActivatedHostRemainsUnknown() {
        final var model = IntrinsicReferenceModel.defaults();
        final var spell = trigger(Map.of("Mode", "BecomesTarget", "ValidSource", "Spell", "ValidTarget", "Card.Self"),
                draw("TriggeredSourceController", null));
        final var cases = IntrinsicTriggerBindingNormalizer.recipientCases(spell, model).orElseThrow();
        Assert.assertEquals(cases.stream().filter(entry -> "Opponent".equals(entry.value().outcome().parameters().get("Defined")))
                .mapToDouble(WeightedValue::weight).sum(), .75, 1e-9);
        Assert.assertTrue(IntrinsicTriggerBindingNormalizer.recipientCases(trigger(Map.of("Mode", "BecomesTarget",
                "ValidSource", "Activated", "ValidTarget", "Card.Self"), spell.outcome()), model).isEmpty());
        Assert.assertTrue(IntrinsicTriggerBindingNormalizer.recipientCases(trigger(Map.of("Mode", "SpellCast",
                "ValidActivatingPlayer", "Opponent"), draw("TriggeredActivator", null)), model).isEmpty());
    }

    @Test
    public void genericSpellTargetDamageReachesDefinitionEvaluation() {
        final var model = IntrinsicReferenceModel.defaults();
        final var value = giantTrigger(model);
        Assert.assertEquals(value.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(value.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(value.contribution().complete());
        Assert.assertTrue(value.contribution().value() > 0);
        final var friendly = giantTrigger(model.withQuantities(model.quantities().with(
                IntrinsicReferenceQuantities.Quantity.SELF_TARGETING_CASTER_IS_OPPONENT,
                new WeightedDistribution<>(List.of(new WeightedValue<>(0, 1.0))))));
        final var opposing = giantTrigger(model.withQuantities(model.quantities().with(
                IntrinsicReferenceQuantities.Quantity.SELF_TARGETING_CASTER_IS_OPPONENT,
                new WeightedDistribution<>(List.of(new WeightedValue<>(1, 1.0))))));
        Assert.assertEquals(value.expectedOccurrences(), friendly.expectedOccurrences(), 1e-9);
        Assert.assertEquals(value.expectedOccurrences(), opposing.expectedOccurrences(), 1e-9);
        Assert.assertEquals(value.contribution().value(), .25 * friendly.contribution().value()
                + .75 * opposing.contribution().value(), 1e-9);
    }

    private static IntrinsicAbilityEvaluator.AbilityValue giantTrigger(final IntrinsicReferenceModel model) {
        return new IntrinsicAbilityEvaluator(model, IntrinsicEvaluationSettings.defaults()).evaluateDefinition(
                forge.model.FModel.getMagicDb().getCommonCards().getCard("Bonecrusher Giant"), forge.card.CardStateName.Original)
                .stream().filter(entry -> entry.path().endsWith("trigger:0")).findFirst().orElseThrow();
    }
}
