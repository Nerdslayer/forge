package forge.view;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.Match;
import forge.game.phase.StackResolutionLimitException;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Collections;

public class SimulationStackSafetyTest extends AITest {
    @Test
    public void safetyTerminationPropagatesWithoutRecordingADraw() {
        Game game = initAndCreateGame();
        Match match = new Match(game.getRules(), Collections.emptyList(), "Safety limit test") {
            @Override
            public Game createGame() {
                return game;
            }

            @Override
            public void startGame(final Game ignored) {
                throw new StackResolutionLimitException("Test resource limit");
            }
        };
        Assert.expectThrows(StackResolutionLimitException.class,
                () -> SimulateMatch.simulateSingleMatch(match, 0, false));
        Assert.assertFalse(game.isGameOver());
        Assert.assertNull(game.getOutcome());
    }
}
