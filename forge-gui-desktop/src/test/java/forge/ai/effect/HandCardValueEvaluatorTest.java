package forge.ai.effect;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;

/** Regression coverage for explicit hand information boundaries and known-card valuation. */
public class HandCardValueEvaluatorTest extends AITest {
    @Test
    public void knownCardUsesDefinitionValueWithoutInspectingHiddenAlternatives() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player owner = game.getPlayers().get(0);
        final Card known = addCard("Grizzly Bears", owner);
        addCardToZone("Forest", owner, forge.game.zone.ZoneType.Hand);
        addCardToZone("Forest", owner, forge.game.zone.ZoneType.Hand);

        final HandValuationContext context = HandValuationContext.knownCardOnly(ai, owner, known, 3);
        final CardValueBreakdown result = HandCardValueEvaluator.evaluateKnownCard(known, context);

        Assert.assertEquals(context.knowledge(), HandKnowledge.KNOWN_CARD_ONLY);
        Assert.assertEquals(context.unknownCardCount(), 2);
        Assert.assertTrue(result.isComplete());
        Assert.assertEquals(result.currentPresenceValue(), 0);
        Assert.assertEquals(result.futurePotentialValue(), 130);
        Assert.assertEquals(result.accessCost(), 50);
        Assert.assertEquals(result.netValue(), 80);
    }

    @Test
    public void unifiedCardEntryPointDelegatesToIntrinsicAndHandContexts() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player owner = game.getPlayers().get(0);
        final Card known = addCard("Grizzly Bears", owner);

        final HandValuationContext handContext = HandValuationContext.knownCardOnly(
                ai, owner, known, 1);
        final CardValueBreakdown handValue = UnifiedCardValueEvaluator.evaluateCard(
                known, handContext);
        final CardValueBreakdown intrinsicValue = UnifiedCardValueEvaluator.evaluateCard(
                known, ValuationContext.intrinsicCard());
        final CardValueBreakdown definitionValue = UnifiedCardValueEvaluator.evaluateCard(
                known.getPaperCard().getRules(), known.getPaperCard().getEdition(),
                ValuationContext.intrinsicCard());

        Assert.assertTrue(handValue.isComplete());
        Assert.assertTrue(intrinsicValue.isComplete());
        Assert.assertTrue(definitionValue.isComplete());
        Assert.assertEquals(handValue.futurePotentialValue(), intrinsicValue.currentPresenceValue());
        Assert.assertEquals(definitionValue, intrinsicValue);
        Assert.assertTrue(intrinsicValue.accessCost() > handValue.accessCost());
    }

    @Test
    public void situationalHandContextUsesOnlyTheInformationItWasGiven() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player owner = game.getPlayers().get(0);
        final Card known = addCardToZone("Grizzly Bears", owner,
                forge.game.zone.ZoneType.Hand);
        addCardToZone("Forest", owner, forge.game.zone.ZoneType.Hand);

        final CardValueBreakdown partial = UnifiedCardValueEvaluator.evaluateCard(known,
                ValuationContext.forHandSelection(ai, false));
        final CardValueBreakdown complete = UnifiedCardValueEvaluator.evaluateCard(known,
                ValuationContext.forHandSelection(ai, true));

        Assert.assertTrue(partial.isComplete());
        Assert.assertTrue(complete.isComplete());
        Assert.assertEquals(partial.futurePotentialValue(), complete.futurePotentialValue());
    }

    @Test
    public void handAccessEstimateSeparatesCurrentAndKnownFutureMana() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player owner = game.getPlayers().get(0);
        final Card known = addCardToZone("Grizzly Bears", owner,
                forge.game.zone.ZoneType.Hand);
        addCardToZone("Forest", owner, forge.game.zone.ZoneType.Hand);
        addCardToZone("Forest", owner, forge.game.zone.ZoneType.Hand);

        final HandValuationContext context = HandValuationContext.fullHand(ai, owner);
        final HandCardAccessEvaluator.Estimate access = HandCardAccessEvaluator.evaluate(known,
                context);

        Assert.assertFalse(access.castableNow());
        Assert.assertEquals(access.availableMana(), 0);
        Assert.assertEquals(access.landsInHand(), 2);
        Assert.assertEquals(access.knownManaAfterLookahead(), 2);
        Assert.assertEquals(access.earliestKnownTurn(), 2);
        Assert.assertTrue(access.canReachWithKnownLands());
    }

    @Test
    public void partialAccessEstimateDoesNotInspectHiddenHandCards() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player owner = game.getPlayers().get(0);
        final Card known = addCardToZone("Grizzly Bears", owner,
                forge.game.zone.ZoneType.Hand);
        addCardToZone("Forest", owner, forge.game.zone.ZoneType.Hand);
        addCardToZone("Forest", owner, forge.game.zone.ZoneType.Hand);

        final HandValuationContext context = HandValuationContext.knownCardOnly(
                ai, owner, known, 3);
        final HandCardAccessEvaluator.Estimate access = HandCardAccessEvaluator.evaluate(known,
                context);

        Assert.assertEquals(access.landsInHand(), 0);
        Assert.assertEquals(access.knownManaAfterLookahead(), 0);
        Assert.assertFalse(access.canReachWithKnownLands());
    }

    @Test
    public void unknownCardValueUsesTheHandPositionButNotCardIdentity() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player owner = game.getPlayers().get(0);
        final HandValuationContext context = HandValuationContext.knownCardOnly(ai, owner, null, 3);

        Assert.assertEquals(HandCardValueEvaluator.evaluateUnknownCard(context), 100);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void fullHandContextRejectsMissingCards() {
        final Game game = initAndCreateGame();
        final Player owner = game.getPlayers().get(0);
        new HandValuationContext(game.getPlayers().get(1), owner, 1, java.util.List.of(),
                HandKnowledge.FULL_HAND);
    }
}
