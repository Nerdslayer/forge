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
