package forge.adventure;

import forge.adventure.util.SecondWind;
import forge.ai.AITest;
import forge.game.Game;
import forge.game.GameType;
import forge.game.event.GameEventGameStarted;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

public class AdventureSecondWindTest extends AITest {
    @Test
    public void opponentGainsLifeAndDrawsOnceAtHalfStartingLife() {
        Game game = initAndCreateGame();
        Player adventurer = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);
        opponent.setStartingLife(31);
        addCardToZone("Forest", opponent, ZoneType.Library);
        addCardToZone("Forest", opponent, ZoneType.Library);
        startTracking(game, adventurer, new SecondWind(true));

        opponent.setLife(16, null);
        game.getAction().checkStateEffects(true);
        game.getStack().addAllTriggeredAbilitiesToStack();
        Assert.assertTrue(game.getStack().isEmpty());

        opponent.setLife(15, null);
        game.getAction().checkStateEffects(true);
        game.getStack().addAllTriggeredAbilitiesToStack();
        Assert.assertEquals(game.getStack().size(), 1);
        game.getStack().resolveStack();
        Assert.assertEquals(opponent.getLife(), 20);
        Assert.assertEquals(opponent.getCardsIn(ZoneType.Hand).size(), 2);

        opponent.setLife(15, null);
        game.getAction().checkStateEffects(true);
        game.getStack().addAllTriggeredAbilitiesToStack();
        Assert.assertTrue(game.getStack().isEmpty());
    }

    @Test
    public void lethalDamageDoesNotTriggerSecondWindAndLifeAtGameStartCounts() {
        Game game = initAndCreateGame();
        Player adventurer = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);
        adventurer.setStartingLife(5);
        SecondWind secondWind = new SecondWind(true);
        startTracking(game, adventurer, secondWind);

        opponent.setLife(0, null);
        game.getAction().checkStaticAbilities();
        Assert.assertFalse(game.getStack().hasSimultaneousStackEntries());
        game.getAction().checkStateEffects(true);
        Assert.assertFalse(game.getStack().hasSimultaneousStackEntries());
        Assert.assertTrue(game.getStack().isEmpty());
        Assert.assertEquals(opponent.getLife(), 0);
        Assert.assertTrue(secondWind.wonAtLowLife());
    }

    @Test
    public void protectionFromLosingAllowsSecondWindAtZeroAndBelow() {
        for (int life : new int[] { 0, -2 }) {
            Game game = initAndCreateGame();
            Player adventurer = game.getPlayers().get(0);
            Player opponent = game.getPlayers().get(1);
            addCard("Platinum Angel", opponent);
            addCardToZone("Forest", opponent, ZoneType.Library);
            addCardToZone("Forest", opponent, ZoneType.Library);
            startTracking(game, adventurer, new SecondWind(true));

            opponent.setLife(life, null);
            game.getAction().checkStateEffects(true);
            Assert.assertFalse(game.isGameOver());
            game.getStack().addAllTriggeredAbilitiesToStack();
            Assert.assertEquals(game.getStack().size(), 1);
            game.getStack().resolveStack();
            Assert.assertEquals(opponent.getLife(), life + 5);
            Assert.assertEquals(opponent.getCardsIn(ZoneType.Hand).size(), 2);
        }
    }

    @Test
    public void onlyWonGamesCountAndLifeCanRecoverBeforeWinning() {
        SecondWind secondWind = new SecondWind(false);
        Game lostGame = initAndCreateGame();
        Player losingAdventurer = lostGame.getPlayers().get(0);
        startTracking(lostGame, losingAdventurer, secondWind);
        losingAdventurer.setLife(0, null);
        lostGame.getAction().checkStateEffects(true);
        Assert.assertFalse(secondWind.wonAtLowLife());

        Game wonGame = initAndCreateGame();
        Player winningAdventurer = wonGame.getPlayers().get(0);
        startTracking(wonGame, winningAdventurer, secondWind);
        winningAdventurer.setLife(5, null);
        winningAdventurer.setLife(10, null);
        wonGame.getPlayers().get(1).setLife(0, null);
        wonGame.getAction().checkStateEffects(true);
        Assert.assertTrue(secondWind.wonAtLowLife());
    }

    private static void startTracking(Game game, Player adventurer, SecondWind secondWind) {
        secondWind.trackGame(game, adventurer.getRegisteredPlayer());
        game.fireEvent(new GameEventGameStarted(GameType.Adventure, adventurer, game.getPlayers()));
    }
}
