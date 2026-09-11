package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.Test;
import forge.ai.AITest;
import forge.ai.PlayerResourceValueEvaluator;
import forge.card.CardStateName;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;

/** Semantic coverage for game-free interpretation, composition and live compiler parity. */
public class AbilityTraversalTest extends AITest {
    @Test
    public void definitionExecuteIsResolvedWithoutGameAndUnsupportedOriginsRemainVisible() {
        host(); // Initialize the script database only; the definition path does not use its game.
        final List<IntrinsicAbilityEvaluator.AbilityValue> results = new IntrinsicAbilityEvaluator(
                IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults()).evaluateDefinition(
                        forge.StaticData.instance().getCommonCards().getCard("Staff of Nin"), CardStateName.Original);
        Assert.assertTrue(results.stream().anyMatch(r -> r.contribution().complete() && r.contribution().value() > 0),
                results + " " + CardAbilityTraversal.inspectDefinition(
                        forge.StaticData.instance().getCommonCards().getCard("Staff of Nin"), CardStateName.Original));
        Assert.assertTrue(results.stream().anyMatch(r -> !r.contribution().complete()));
    }

    private Card host() {
        final forge.game.Game game = initAndCreateGame();
        return addCard("Grizzly Bears", game.getPlayers().get(0));
    }

    @Test
    public void traversalDistinguishesSharedBranchesFromCycles() {
        final Card card = host();
        final SpellAbility root = AbilityFactory.getAbility("DB$ Draw", card);
        final forge.game.spellability.AbilitySub child = (forge.game.spellability.AbilitySub)
                AbilityFactory.getAbility("DB$ Draw", card);
        root.setAdditionalAbilityList("Choices", List.of(child, child));
        final AbilityOutcomeDescription shared = AbilityOutcomeParser.parse(root, "root");
        Assert.assertEquals(shared.choices().size(), 2);
        Assert.assertTrue(shared.choices().stream().allMatch(c -> c.issue().isEmpty()));
        child.setAdditionalAbilityList("Choices", List.of(child));
        final AbilityOutcomeDescription cyclic = AbilityOutcomeParser.parse(root, "root");
        Assert.assertTrue(cyclic.choices().get(0).choices().get(0).issue().contains("Cyclic"));
    }

    @Test
    public void randomUnknownBranchesRetainProbabilityRatherThanRenormalizing() {
        final AbilityOutcomeDescription draw = new AbilityOutcomeDescription("draw", "Draw",
                Map.of(), List.of(), null, "");
        final AbilityOutcomeDescription random = new AbilityOutcomeDescription("random", "Charm",
                Map.of("Random", "True"), List.of(draw,
                        AbilityOutcomeDescription.unresolved("unknown", "Unmodeled branch")), null, "");
        final Outcome<IntrinsicDrawOutcomeBackend.State> outcome = new OutcomeDescriptionCompiler<>(
                new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults())).compile(random);
        final OutcomePlan<IntrinsicDrawOutcomeBackend.State> plan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>()
                .evaluate(outcome, new IntrinsicDrawOutcomeBackend.State(0, 0));
        Assert.assertEquals(plan.completeness(), OutcomePlan.Completeness.PARTIAL);
        Assert.assertEquals(plan.unresolvedProbability(), 0.5);
        Assert.assertEquals(plan.value(), PlayerResourceValueEvaluator.evaluateCardDraw(0, 1) * 0.5);
    }

    @Test
    public void intrinsicCostBearingModesAreNotFreeOutcomes() {
        final AbilityOutcomeDescription paid = new AbilityOutcomeDescription("paid", "Draw",
                Map.of("Cost", "3", "NumCards", "4"), List.of(), null, "");
        final Outcome<IntrinsicDrawOutcomeBackend.State> outcome = new OutcomeDescriptionCompiler<>(
                new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults())).compile(paid);
        final OutcomePlan<IntrinsicDrawOutcomeBackend.State> plan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>()
                .evaluate(outcome, new IntrinsicDrawOutcomeBackend.State(0, 0));
        Assert.assertEquals(plan.completeness(), OutcomePlan.Completeness.UNSUPPORTED);
        Assert.assertEquals(plan.value(), 0.0);
    }

    @Test
    public void scheduledDrawTraversesAndUsesReferenceHandDistribution() {
        final Card card = host();
        final Trigger trigger = TriggerHandler.parseTrigger(
                "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | TriggerZones$ Battlefield", card, true);
        trigger.setOverridingAbility(AbilityFactory.getAbility("DB$ Draw | Defined$ You | NumCards$ 1", card));
        card.getCurrentState().addTrigger(trigger);
        final List<CardAbilityTraversal.AbilityDescription> descriptions = CardAbilityTraversal.inspect(card.getCurrentState());
        final CardAbilityTraversal.AbilityDescription found = descriptions.stream()
                .filter(a -> a.origin() == CardAbilityTraversal.Origin.TRIGGER).findFirst().orElseThrow();
        final IntrinsicReferenceModel model = IntrinsicReferenceModel.defaults();
        final IntrinsicAbilityEvaluator.AbilityValue result = new IntrinsicAbilityEvaluator(model,
                IntrinsicEvaluationSettings.defaults()).evaluate(List.of(found),
                        new IntrinsicReferenceModel.PermanentProfile(true, IntrinsicReferenceModel.PermanentKind.CREATURE,
                                true, 2, 2, Set.of()), EntryTiming.NORMAL_SPEED).get(0);
        final double draw = model.handSizes().entries().stream().mapToDouble(e -> e.weight()
                * PlayerResourceValueEvaluator.evaluateCardDraw(e.value(), 1)).sum();
        Assert.assertTrue(result.contribution().complete());
        Assert.assertEquals(result.contribution().value(), draw * result.expectedOccurrences(), 0.0000001);
        Assert.assertEquals(trigger.getOverridingAbility().getParam("NumCards"), "1");
        Assert.assertFalse(CardAbilityTraversal.inspectDefinition(card.getPaperCard(), CardStateName.Original)
                .stream().anyMatch(a -> a.origin() == CardAbilityTraversal.Origin.TRIGGER));
    }

    @Test
    public void sequenceProjectsHandChangesAndUnknownChoiceRemainsPartial() {
        final Card card = host();
        card.setSVar("SecondDraw", "DB$ Draw | NumCards$ 1");
        final SpellAbility ability = AbilityFactory.getAbility("DB$ Draw | NumCards$ 1 | SubAbility$ SecondDraw", card);
        final IntrinsicDrawOutcomeBackend backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults());
        final OutcomeDescriptionCompiler<IntrinsicDrawOutcomeBackend.State> compiler = new OutcomeDescriptionCompiler<>(backend);
        final AbilityOutcomeDescription parsed = AbilityOutcomeParser.parse(ability, "draw");
        final OutcomePlan<IntrinsicDrawOutcomeBackend.State> sequence = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>()
                .evaluate(compiler.compile(parsed), new IntrinsicDrawOutcomeBackend.State(0, 0));
        Assert.assertEquals(sequence.state().controllerHand(), 2);
        Assert.assertEquals(sequence.value(), (double) PlayerResourceValueEvaluator.evaluateCardDraw(0, 2));
        final AbilityOutcomeDescription choice = new AbilityOutcomeDescription("choice", "Charm",
                Map.of("CharmNum", "1"), List.of(parsed,
                        AbilityOutcomeDescription.unresolved("unknown", "Unsupported effect")), null, "");
        final OutcomePlan<IntrinsicDrawOutcomeBackend.State> partial = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>()
                .evaluate(compiler.compile(choice), new IntrinsicDrawOutcomeBackend.State(0, 0));
        Assert.assertEquals(partial.completeness(), OutcomePlan.Completeness.PARTIAL);
        Assert.assertEquals(partial.unresolvedProbability(), 0.0);
        Assert.assertFalse(partial.unresolvedAlternatives().isEmpty());
    }

    @Test
    public void sharedCompilerMatchesLiveDrawSequenceAndPreservesSource() {
        final Card card = host();
        card.setSVar("SecondDraw", "DB$ Draw | NumCards$ 1");
        final SpellAbility ability = AbilityFactory.getAbility("DB$ Draw | NumCards$ 1 | SubAbility$ SecondDraw", card);
        ability.setActivatingPlayer(card.getController());
        // Populate a library so the live draw cap doesn't make the effect unavailable.
        for (int i = 0; i < 4; i++) {
            final Card libraryCard = Card.fromPaperCard(card.getPaperCard(), card.getController());
            card.getController().getZone(forge.game.zone.ZoneType.Library).add(libraryCard);
        }
        final int hand = card.getController().getCardsIn(forge.game.zone.ZoneType.Hand).size();
        final Map<String, String> before = Map.copyOf(ability.getMapParams());
        final OutcomePlan<OutcomeState> result = SpellAbilityOutcomePlanner.evaluate(ability,
                card.getController().getOpponents().get(0));
        Assert.assertTrue(result.complete(), result.reason());
        Assert.assertEquals(result.value(), (double) PlayerResourceValueEvaluator.evaluateCardDraw(hand, 2));
        Assert.assertEquals(ability.getMapParams(), before);
        Assert.assertNotNull(ability.getSubAbility());
        Assert.assertEquals(card.getController().getCardsIn(forge.game.zone.ZoneType.Hand).size(), hand);
    }
}
