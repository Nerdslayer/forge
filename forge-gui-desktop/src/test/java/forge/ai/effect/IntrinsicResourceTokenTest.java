package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.effect.IntrinsicDrawOutcomeBackend.State;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;
import forge.card.CardRules;
import forge.card.CardStateName;
import forge.model.FModel;

/** Resource-option, ownership, tapped timing and projected-state regressions for artifact tokens. */
public class IntrinsicResourceTokenTest extends AITest {
    private static final IntrinsicEvaluationSettings SETTINGS = IntrinsicEvaluationSettings.defaults();
    private static final PermanentProfile SOURCE = new PermanentProfile(true, PermanentKind.CREATURE, true, 2, 2, Set.of());

    private static IntrinsicTokenResolver resolver() {
        return IntrinsicTokenProfileResolver.forSource(FModel.getMagicDb().getCommonCards().getCard("Forest"),
                IntrinsicReferenceModel.defaults(), SETTINGS);
    }

    private static AbilityOutcomeDescription token(final String script, final String owner, final boolean tapped) {
        return new AbilityOutcomeDescription("token", "Token", Map.of("TokenScript", script,
                "TokenOwner", owner, "TokenTapped", String.valueOf(tapped), "TokenAmount", "1"), List.of(), null, "");
    }

    private static OutcomePlan<State> evaluate(final AbilityOutcomeDescription node, final State state) {
        final var backend = new IntrinsicDrawOutcomeBackend(SETTINGS, SOURCE, resolver());
        return new OutcomePlanner<State>().evaluate(new OutcomeDescriptionCompiler<>(backend).compile(node), state);
    }

    @Test
    public void supportedConsumableTokensAreNotCountedAsCreaturesOrImmediatelyConsumed() {
        for (final String script : List.of("c_a_treasure_sac", "c_a_gold_sac", "c_a_clue_draw", "c_a_food_sac")) {
            final var definition = resolver().resolveToken(script).orElseThrow();
            Assert.assertEquals(definition.profile().kind(), PermanentKind.ARTIFACT);
            Assert.assertNotNull(definition.resourceValue());
            final var initial = new State(2, 2).withLife(true, 4);
            final var result = evaluate(token(script, "You", false), initial);
            Assert.assertTrue(result.complete(), result.toString());
            Assert.assertTrue(result.value() > 0, script);
            Assert.assertEquals(result.state().controllerCreatureCount(), initial.controllerCreatureCount());
            Assert.assertEquals(result.state().controllerHand(), initial.controllerHand());
            Assert.assertEquals(result.state().controllerLife(), initial.controllerLife());
            Assert.assertEquals(result.state().controllerMana(), initial.controllerMana());
        }
    }

    @Test
    public void resourceValuesUseActualRecipientAndTappedCostTiming() {
        final var initial = new State(2, 2).withLife(true, 4).withLife(false, 4);
        for (final String script : List.of("c_a_treasure_sac", "c_a_clue_draw", "c_a_food_sac")) {
            final var friendly = evaluate(token(script, "You", false), initial);
            final var hostile = evaluate(token(script, "Opponent", false), initial);
            Assert.assertEquals(friendly.value(), -hostile.value(), 1e-9, script);
        }
        Assert.assertTrue(evaluate(token("c_a_treasure_sac", "You", true), initial).value()
                < evaluate(token("c_a_treasure_sac", "You", false), initial).value());
        Assert.assertEquals(evaluate(token("c_a_gold_sac", "You", true), initial).value(),
                evaluate(token("c_a_gold_sac", "You", false), initial).value());
        Assert.assertEquals(evaluate(token("c_a_clue_draw", "You", true), initial).value(),
                evaluate(token("c_a_clue_draw", "You", false), initial).value());
        Assert.assertTrue(evaluate(token("c_a_food_sac", "You", false), initial).value()
                > evaluate(token("c_a_food_sac", "You", false), initial.withLife(true, 20)).value());
    }

    @Test
    public void projectedDrawChangesSubsequentClueOptionValue() {
        final var initial = new State(0, 2);
        final var clue = token("c_a_clue_draw", "You", false);
        final var draw = new AbilityOutcomeDescription("draw", "Draw", Map.of("Defined", "You", "NumCards", "1"),
                List.of(), clue, "");
        final var result = evaluate(draw, initial);
        final var utility = new IntrinsicOutcomeEvaluator(SETTINGS);
        final double afterDraw = evaluate(clue, initial.withHands(true, 1)).value();
        Assert.assertTrue(result.complete(), result.toString());
        Assert.assertEquals(result.value(), utility.evaluateCardDraw(0, 1, true) + afterDraw, 1e-9);
        Assert.assertTrue(afterDraw < evaluate(clue, initial).value());
        final var backend = new IntrinsicDrawOutcomeBackend(SETTINGS, SOURCE, resolver());
        Assert.assertTrue(backend.referenceDimensions(clue).contains(IntrinsicDrawOutcomeBackend.CONTROLLER_HAND));
    }

    @Test
    public void investigateAndActualTokenProducingDefinitionsUseTheSharedResourcePath() {
        final var initial = new State(2, 2);
        final var investigate = new AbilityOutcomeDescription("investigate", "Investigate", Map.of("Num", "3"), List.of(), null, "");
        final var result = evaluate(investigate, initial);
        Assert.assertTrue(result.complete(), result.toString());
        Assert.assertEquals(result.value(), 3 * evaluate(token("c_a_clue_draw", "You", false), initial).value(), 1e-9);
        final var zero = new AbilityOutcomeDescription("zero", "Investigate", Map.of("Num", "0"), List.of(), null, "");
        Assert.assertEquals(evaluate(zero, initial).value(), 0.0);
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), SETTINGS);
        for (final String name : List.of("Pitiless Plunderer", "Gilded Goose", "Tireless Tracker")) {
            final var trigger = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard(name), CardStateName.Original)
                    .stream().filter(value -> value.path().endsWith("trigger:0")).findFirst().orElseThrow();
            Assert.assertEquals(trigger.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED, name);
            Assert.assertTrue(trigger.contribution().value() > 0, name);
        }
        final var entry = Map.of("Mode", "ChangesZone", "Origin", "Any", "Destination", "Battlefield", "ValidCard", "Land.YouCtrl");
        Assert.assertEquals(IntrinsicLandEntryTriggerAdapter.describe(entry).orElseThrow().turnScope(),
                IntrinsicEventTrigger.TurnScope.CONTROLLER_TURN);
        final var omittedOrigin = new java.util.HashMap<>(entry);
        omittedOrigin.remove("Origin");
        Assert.assertEquals(IntrinsicLandEntryTriggerAdapter.describe(omittedOrigin), IntrinsicLandEntryTriggerAdapter.describe(entry));
        final var bill = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard("Bristly Bill, Spine Sower"),
                CardStateName.Original).stream().filter(value -> value.path().endsWith("trigger:0")).findFirst().orElseThrow();
        Assert.assertEquals(bill.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(bill.contribution().value() > 0);
        final var filtered = new java.util.HashMap<>(entry);
        filtered.put("ValidCard", "Land.Plains+YouCtrl");
        Assert.assertTrue(IntrinsicLandEntryTriggerAdapter.describe(filtered).isEmpty());
    }

    @Test
    public void extraCostsOrUnsupportedAbilitiesDoNotReceivePartialFalseCompleteTokenValue() {
        Assert.assertTrue(resolver().resolveToken("c_a_blood_draw").isEmpty());
        Assert.assertTrue(resolver().resolveToken("c_a_map_sac_explore").isEmpty());
        final var mixed = CardRules.fromScript(List.of("Name:Mixed Resource Token", "ManaCost:no cost", "Types:Artifact",
                "A:AB$ Draw | Cost$ 2 Sac<1/CARDNAME> | NumCards$ 1",
                "A:AB$ Mill | Cost$ Sac<1/CARDNAME> | NumCards$ 2"));
        Assert.assertTrue(IntrinsicConsumableAbilityEvaluator.evaluate(mixed.getMainPart(), IntrinsicReferenceModel.defaults(), SETTINGS).isEmpty());
    }
}
