package forge.ai;

import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.phase.StackResolutionLimitException;
import forge.game.phase.StackResolutionTracker;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.AssertJUnit;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;

public class AiLoopSafetyTest extends AITest {

    @Test(timeOut = 30000)
    public void finiteChainMayCreateAdditionalMandatoryTriggers() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card warden = addCard("Soul Warden", ai);
        game.getAction().checkStateEffects(true);
        int initialLife = ai.getLife();
        for (int i = 0; i < 250; i++) {
            SpellAbility token = AbilityFactory.getAbility(
                    "AB$ Token | Cost$ 0 | TokenScript$ w_1_1_soldier | TokenAmount$ 1", warden);
            token.setActivatingPlayer(ai);
            game.getStack().add(token);
        }
        resolveCurrentPhase(game, 2000);
        Assert.assertFalse(game.isGameOver());
        Assert.assertEquals(ai.getLife(), initialLife + 250);
        Assert.assertEquals(countCardsWithName(game, "Soldier Token"), 250);
    }

    @Test(timeOut = 30000)
    public void largeLandfallBatchFinishesWhileTheBattlefieldGrows() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        addCards("Forest", 6, ai);
        addCards("Scute Swarm", 256, ai);
        game.getAction().checkStateEffects(true);
        game.getAction().moveToPlay(addCardToZone("Forest", ai, ZoneType.Hand), null, AbilityKey.newMap());
        resolveCurrentPhase(game, 2000);
        Assert.assertFalse(game.isGameOver());
        Assert.assertEquals(countCardsWithName(game, "Scute Swarm"), 512);
    }

    @Test(timeOut = 30000)
    public void excessivePendingTriggersAreAResourceFailureRatherThanADraw() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card source = addCard("Runeclaw Bear", ai);
        SpellAbility ability = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ 0 | Defined$ You | LifeAmount$ 0", source);
        ability.setActivatingPlayer(ai);
        for (int i = 0; i < StackResolutionTracker.MAX_STACK_SIZE; i++) {
            game.getStack().addSimultaneousStackEntry(ability);
        }
        Assert.expectThrows(StackResolutionLimitException.class,
                () -> game.getStack().addSimultaneousStackEntry(ability));
        Assert.assertFalse(game.isGameOver());
        Assert.assertNull(ai.getOutcome());
    }

    private void resolveCurrentPhase(final Game game, final int maxSteps) {
        PhaseType initialPhase = game.getPhaseHandler().getPhase();
        int steps = 0;
        while (!game.isGameOver() && game.getPhaseHandler().is(initialPhase) && steps++ < maxSteps) {
            game.getPhaseHandler().mainLoopStep();
        }
        Assert.assertFalse(game.getPhaseHandler().is(initialPhase), "the finite chain must let the phase advance");
    }

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

    @DataProvider
    public Object[][] finiteStackSizes() {
        return new Object[][] {{201}, {1500}};
    }

    @Test(dataProvider = "finiteStackSizes", timeOut = 30000)
    public void largeFiniteStackResolvesWithoutDrawing(final int stackSize) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card source = addCard("Runeclaw Bear", ai);

        for (int i = 0; i < stackSize; i++) {
            SpellAbility sa = AbilityFactory.getAbility(
                    "AB$ GainLife | Cost$ 0 | Defined$ You | LifeAmount$ 0", source);
            sa.setActivatingPlayer(ai);
            game.getStack().add(sa);
        }
        game.getPhaseHandler().onStackResolved();

        int steps = 0;
        while (!game.isGameOver() && !game.getStack().isEmpty() && steps++ < stackSize * 3) {
            game.getPhaseHandler().mainLoopStep();
        }

        AssertJUnit.assertFalse("a finite stack must never be declared a draw", game.isGameOver());
        AssertJUnit.assertTrue("every queued ability should resolve", game.getStack().isEmpty());
        AssertJUnit.assertNull(ai.getOutcome());
    }
}
