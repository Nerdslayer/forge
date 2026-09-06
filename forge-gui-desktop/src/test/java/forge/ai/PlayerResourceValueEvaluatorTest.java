package forge.ai;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.player.Player;

public class PlayerResourceValueEvaluatorTest extends AITest {
    @Test
    public void testMarginalCardValueDeclinesWithHandSize() {
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateNextCard(0), 140);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateNextCard(3), 104);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateNextCard(7), 60);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateNextCard(20), 60);
    }

    @Test
    public void testMultipleDrawsUseSuccessiveMarginalValues() {
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateCardDraw(0, 3),
                140 + 128 + 116);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateCardDraw(3, 2), 104 + 92);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateCardDraw(3, 0), 0);
    }

    @Test
    public void testRandomDiscardReversesCardDrawValue() {
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateRandomDiscard(7, 1), 68);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateRandomDiscard(5, 2), 196);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateRandomDiscard(3, 1), 116);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateRandomDiscard(3, 2), 244);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateRandomDiscard(1, 3), 140);
    }

    @Test
    public void testChosenDiscardDiscountShrinksAsHandIsEmptied() {
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateChosenDiscard(7, 1), 39);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateChosenDiscard(5, 2), 137);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateChosenDiscard(3, 1), 77);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateChosenDiscard(3, 2), 203);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateChosenDiscard(1, 3), 140);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateChosenDiscard(0, 1), 0);
    }

    @Test
    public void testManaUsesSharedPermanentEvaluationScale() {
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateMana(1), 35);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateMana(3), 105);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateMana(0), 0);
    }

    @Test
    public void testLifeUtilityMatchesBurnPressureCalibration() {
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateLifeChange(21, 16), -79);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateLifeChange(16, 11), -109);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateLifeChange(11, 6), -176);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateLifeChange(6, 1), -520);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateLifeChange(1, 6), 520);
    }

    @Test
    public void testFinalOpponentAndOwnEliminationUseTerminalValue() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);

        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateEliminations(
                ai, List.of(opponent)), PlayerResourceValueEvaluator.TERMINAL_GAME_VALUE);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateEliminations(
                ai, List.of(ai)), -PlayerResourceValueEvaluator.TERMINAL_GAME_VALUE);
    }

    @Test
    public void testMultiplayerEliminationUsesCompetitorEstimate() {
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateCompetitorReduction(4, 3), 833);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateCompetitorReduction(3, 2), 1667);

        final Game game = initAndCreateThreePlayerGame();
        final Player opponent = game.getPlayers().get(0);
        final Player ai = game.getPlayers().get(1);
        final Player otherOpponent = game.getPlayers().get(2);
        opponent.setTeam(0);
        ai.setTeam(1);
        otherOpponent.setTeam(2);

        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateEliminations(
                ai, List.of(opponent)), 1667);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateEliminations(
                ai, List.of(opponent, otherOpponent)),
                PlayerResourceValueEvaluator.TERMINAL_GAME_VALUE);
    }
}
