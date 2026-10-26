package forge.game.trigger;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.phase.StackResolutionLimitException;
import forge.game.ability.AbilityKey;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.AssertJUnit;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;

public class MandatoryLoopTest extends AITest {

    @DataProvider
    public Object[][] playerCounts() {
        return new Object[][] {{2}, {3}};
    }

    @Test(dataProvider = "playerCounts", timeOut = 30000)
    public void unverifiedMandatoryLoopStopsHeadlessExecutionWithoutInventingConsent(final int players) {
        Game game = players == 2 ? initAndCreateGame() : initAndCreateThreePlayerGame();
        Player p = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        addCard("Gremlin Tamer", p);
        addCard("Secret Arcade", p);
        addCard("Night of Souls' Betrayal", p);
        game.getAction().checkStateEffects(true);

        game.getAction().moveToPlay(addCardToZone("Sterling Grove", p, ZoneType.Hand), null, AbilityKey.newMap());
        Assert.expectThrows(StackResolutionLimitException.class, () -> {
            for (int i = 0; i < 10000 && !game.isGameOver(); i++) {
                game.getPhaseHandler().mainLoopStep();
            }
        });
        AssertJUnit.assertFalse(game.isGameOver());
        for (Player player : game.getPlayers()) {
            AssertJUnit.assertNull("a safety limit is not agreement to a draw", player.getOutcome());
        }
    }
}
