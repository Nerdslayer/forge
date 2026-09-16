package forge.ai.effect;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;

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
