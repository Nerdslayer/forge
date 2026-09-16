package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.ComputerUtilAbility;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.ApiType;
import forge.game.ability.SpellApiBased;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Regression coverage for the shared action valuation boundary. */
public class UnifiedActionValueEvaluatorTest extends AITest {
    @Test
    public void removalActionComposesTheExistingPermanentAndTransitionEvaluators() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        final Card target = addCard("Grizzly Bears", opponent);
        final ValuationContext context = ValuationContext.forRemoval(ai, 0, 0);

        final CardValueBreakdown expected = RemovalActionEvaluator.evaluate(ai, target,
                UnifiedCardValueEvaluator.evaluatePermanent(target, context),
                RemovalActionKind.BOUNCE);
        final CardValueBreakdown actual = UnifiedActionValueEvaluator.evaluate(
                new RemovalValuationAction(target, RemovalActionKind.BOUNCE), context);

        Assert.assertEquals(actual, expected);
    }

    @Test
    public void nullTraceUsesTheSameDisabledDiagnosticsBehavior() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card target = addCard("Grizzly Bears", game.getPlayers().get(0));
        final ValuationContext context = ValuationContext.forRemoval(ai, 0, 0);

        final CardValueBreakdown result = UnifiedCardValueEvaluator.evaluatePermanent(target,
                context, null);

        Assert.assertEquals(result, UnifiedCardValueEvaluator.evaluatePermanent(target, context));
    }

    @Test
    public void castActionValuesACompleteImmediateOutcomeAndChargesResources() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card source = addCardToZone("Divination", ai, ZoneType.Hand);
        addCardToZone("Forest", ai, ZoneType.Library);
        final SpellAbility draw = new SpellApiBased(ApiType.Draw, source, new Cost("1", false),
                null, Map.of("Defined", "You", "NumCards", "1"));
        draw.setActivatingPlayer(ai);

        final CardValueBreakdown result = UnifiedActionValueEvaluator.evaluate(
                new CastValuationAction(draw), ValuationContext.forCast(ai, true));

        Assert.assertTrue(result.isComplete(), result.toString());
        Assert.assertTrue(result.transitionValue() > 0, result.toString());
        Assert.assertEquals(result.accessCost(), 25 + 90);
        Assert.assertTrue(result.netValue() < result.transitionValue());
    }

    @Test
    public void activationActionValuesACompleteImmediateOutcomeAndChargesManaOnly() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card source = addCard("Grizzly Bears", ai);
        addCard("Forest", ai);
        final SpellAbility gainLife = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ 1 | Defined$ You | LifeAmount$ 2", source);
        gainLife.setActivatingPlayer(ai);

        final CardValueBreakdown result = UnifiedActionValueEvaluator.evaluate(
                new ActivateValuationAction(source, gainLife),
                ValuationContext.forActivation(ai, true));

        Assert.assertTrue(result.isComplete(), result.toString());
        Assert.assertTrue(result.transitionValue() > 0, result.toString());
        Assert.assertEquals(result.accessCost(), 25);
        Assert.assertTrue(result.netValue() < result.transitionValue());
    }

    @Test
    public void activationActionValuesAFixedLifePayment() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card source = addCard("Grizzly Bears", ai);
        final SpellAbility gainLife = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ PayLife<3> | Defined$ You | LifeAmount$ 20", source);
        gainLife.setActivatingPlayer(ai);

        final CardValueBreakdown result = UnifiedActionValueEvaluator.evaluate(
                new ActivateValuationAction(source, gainLife),
                ValuationContext.forActivation(ai, true));

        Assert.assertTrue(result.isComplete(), result.toString());
        Assert.assertTrue(result.transitionValue() > 0, result.toString());
        Assert.assertTrue(result.accessCost() > 0, result.toString());
    }

    @Test
    public void activationTieBreakerUsesSharedValueOnlyForAnExactLegacyTie() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card weakSource = addCard("Grizzly Bears", ai);
        final Card strongSource = addCard("Grizzly Bears", ai);
        addCard("Forest", ai);
        final SpellAbility weak = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ 1 | Defined$ You | LifeAmount$ 1", weakSource);
        final SpellAbility strong = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ 1 | Defined$ You | LifeAmount$ 20", strongSource);
        weak.setActivatingPlayer(ai);
        strong.setActivatingPlayer(ai);
        final List<SpellAbility> abilities = new ArrayList<>(List.of(weak, strong));

        Assert.assertEquals(ComputerUtilAbility.saEvaluator.compare(weak, strong), 0);
        ActivateAbilityValueTieBreaker.apply(ai, abilities);

        Assert.assertSame(abilities.get(0), strong);
    }

    @Test
    public void discardActionUsesKnownHandValueWithOpponentPolarity() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        final Card weak = addCardToZone("Craw Wurm", opponent, ZoneType.Hand);
        final Card strong = addCardToZone("Colossal Dreadmaw", opponent, ZoneType.Hand);
        final ValuationContext context = ValuationContext.forDiscard(ai, true);

        final CardValueBreakdown weakValue = UnifiedActionValueEvaluator.evaluate(
                new DiscardValuationAction(weak, opponent), context);
        final CardValueBreakdown strongValue = UnifiedActionValueEvaluator.evaluate(
                new DiscardValuationAction(strong, opponent), context);

        Assert.assertTrue(weakValue.isComplete(), weakValue.toString());
        Assert.assertTrue(strongValue.isComplete(), strongValue.toString());
        Assert.assertTrue(weakValue.transitionValue() > 0, weakValue.toString());
        Assert.assertTrue(strongValue.transitionValue() > weakValue.transitionValue(),
                strongValue + " <= " + weakValue);
    }

    @Test
    public void discardingOwnCardHasTheOppositePolarity() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card card = addCardToZone("Grizzly Bears", ai, ZoneType.Hand);

        final CardValueBreakdown result = UnifiedActionValueEvaluator.evaluate(
                new DiscardValuationAction(card, ai), ValuationContext.forDiscard(ai, true));

        Assert.assertTrue(result.isComplete(), result.toString());
        Assert.assertTrue(result.transitionValue() < 0, result.toString());
    }

    @Test
    public void genericActionSelectorChoosesTheBestCompleteAction() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        final Card weak = addCardToZone("Craw Wurm", opponent, ZoneType.Hand);
        final Card strong = addCardToZone("Colossal Dreadmaw", opponent, ZoneType.Hand);
        final List<Card> candidates = new ArrayList<>(List.of(weak, strong));

        final ActionValueSelector.Selection<Card> selection = ActionValueSelector.selectBest(
                candidates, ValuationContext.forDiscard(ai, true), card -> true,
                card -> new DiscardValuationAction(card, opponent));

        Assert.assertTrue(selection.valuationUsed(), selection.reason());
        Assert.assertSame(selection.selected(), strong);
        Assert.assertEquals(selection.orderedCandidates(), List.of(strong, weak));
    }

    @Test
    public void genericActionSelectorPreservesFallbackForUnsupportedActions() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card target = addCard("Grizzly Bears", game.getPlayers().get(0));
        final ValuationAction action = new RemovalValuationAction(target, RemovalActionKind.EXILE);
        final List<ValuationAction> candidates = new ArrayList<>(List.of(action, action));

        final ActionValueSelector.Selection<ValuationAction> selection =
                ActionValueSelector.selectBest(candidates, ValuationContext.forDiscard(ai, true),
                        candidate -> true, candidate -> candidate);

        Assert.assertFalse(selection.valuationUsed(), selection.reason());
        Assert.assertSame(selection.selected(), action);
        Assert.assertEquals(selection.orderedCandidates(), candidates);
    }

    @Test
    public void removalActionRejectsAContextForAnotherDecision() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card target = addCard("Grizzly Bears", game.getPlayers().get(0));

        final CardValueBreakdown result = UnifiedActionValueEvaluator.evaluate(
                new RemovalValuationAction(target, RemovalActionKind.EXILE),
                ValuationContext.forCast(ai, true));

        Assert.assertEquals(result.completeness(), ValuationCompleteness.UNSUPPORTED);
    }
}
