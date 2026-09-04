package forge.ai;

import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

public class AiLoopSafetyTest extends AITest {

    @Test(timeOut = 30000)
    public void optionalBlinkLoopIsDeclinedAtResolutionLimit() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        PhaseType startingPhase = game.getPhaseHandler().getPhase();

        addCard("Wispweaver Angel", ai);
        game.getAction().checkStateEffects(true);
        Card secondAngel = addCardToZone("Wispweaver Angel", ai, ZoneType.Hand);
        game.getAction().moveTo(ZoneType.Battlefield, secondAngel, null, null);

        int steps = 0;
        while (!game.isGameOver() && game.getPhaseHandler().is(startingPhase) && steps++ < 1000) {
            game.getPhaseHandler().mainLoopStep();
        }

        AssertJUnit.assertTrue("the setup should exercise the 200-resolution safeguard", steps >= 400);
        AssertJUnit.assertFalse("the AI should decline the optional trigger instead of drawing", game.isGameOver());
        AssertJUnit.assertFalse("the loop should end and allow the phase to advance",
                game.getPhaseHandler().is(startingPhase));
        AssertJUnit.assertEquals("both Angels should remain on the battlefield", 2,
                countCardsWithName(game, "Wispweaver Angel"));
    }

    @Test(timeOut = 30000)
    public void mandatoryResolutionLoopEndsInDraw() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card source = addCard("Runeclaw Bear", ai);

        for (int i = 0; i <= 200; i++) {
            SpellAbility sa = AbilityFactory.getAbility(
                    "AB$ GainLife | Cost$ 0 | Defined$ You | LifeAmount$ 0", source);
            sa.setActivatingPlayer(ai);
            game.getStack().add(sa);
        }
        game.getPhaseHandler().onStackResolved();

        int steps = 0;
        while (!game.isGameOver() && steps++ < 1000) {
            game.getPhaseHandler().mainLoopStep();
        }

        AssertJUnit.assertTrue("a mandatory chain continuing beyond the limit should draw", game.isGameOver());
        AssertJUnit.assertEquals(GameEndReason.Draw, game.getOutcome().getWinCondition());
    }
}
