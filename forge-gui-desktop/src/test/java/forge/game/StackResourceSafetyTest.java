package forge.game;

import forge.ai.AITest;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.phase.StackResolutionLimitException;
import forge.game.phase.StackResolutionTracker;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import org.testng.Assert;
import org.testng.annotations.Test;

public class StackResourceSafetyTest extends AITest {
    @Test
    public void pendingTriggersReachTheBoundaryBeforeResourceHandling() {
        Game game = initAndCreateGame();
        SpellAbility ability = ability(game);
        for (int i = 0; i < StackResolutionTracker.STACK_SIZE_WARNING; i++) {
            game.getStack().addSimultaneousStackEntry(ability);
        }
        Assert.assertEquals(game.getStack().getResourceWarningSize(), StackResolutionTracker.STACK_SIZE_WARNING);
        StackResolutionLimitException error = Assert.expectThrows(StackResolutionLimitException.class,
                () -> game.getPhaseHandler().mainLoopStep());
        Assert.assertTrue(error.getMessage().contains("game-loop boundary"));
        Assert.assertTrue(game.getStack().hasSimultaneousStackEntries(), "no trigger may be discarded to enforce a limit");
        Assert.assertFalse(game.getStack().isFrozen());
        Assert.assertFalse(game.getStack().isResolving());
        Assert.assertFalse(game.isGameOver());
        for (Player player : game.getPlayers()) Assert.assertNull(player.getOutcome());
    }

    @Test
    public void continuingAllowsAWindowOfGrowthAndDrainingRearmsTheWarning() {
        Game game = initAndCreateGame();
        SpellAbility ability = ability(game);
        for (int i = 0; i < StackResolutionTracker.STACK_SIZE_WARNING; i++) {
            game.getStack().addSimultaneousStackEntry(ability);
        }
        game.getStack().acknowledgeResourceWarning();
        Assert.assertEquals(game.getStack().getResourceWarningSize(), 0);
        for (int i = 0; i < StackResolutionTracker.WARNING_INTERVAL - 1; i++) {
            game.getStack().addSimultaneousStackEntry(ability);
        }
        Assert.assertEquals(game.getStack().getResourceWarningSize(), 0);
        game.getStack().addSimultaneousStackEntry(ability);
        Assert.assertEquals(game.getStack().getResourceWarningSize(),
                StackResolutionTracker.STACK_SIZE_WARNING + StackResolutionTracker.WARNING_INTERVAL);
        game.getStack().acknowledgeResourceWarning();
        game.getStack().clearSimultaneousStack();
        Assert.assertEquals(game.getStack().getResourceWarningSize(), 0);
        for (int i = 0; i < StackResolutionTracker.STACK_SIZE_WARNING; i++) {
            game.getStack().addSimultaneousStackEntry(ability);
        }
        Assert.assertEquals(game.getStack().getResourceWarningSize(), StackResolutionTracker.STACK_SIZE_WARNING);
    }

    @Test
    public void emergencyCapIncludesFrozenEntriesAndNeverCreatesAnOutcome() {
        Game game = initAndCreateGame();
        SpellAbility ability = ability(game);
        game.getStack().freezeStack(null);
        for (int i = 0; i < StackResolutionTracker.MAX_STACK_SIZE; i++) game.getStack().add(ability);
        Assert.expectThrows(StackResolutionLimitException.class,
                () -> game.getStack().addSimultaneousStackEntry(ability));
        Assert.assertFalse(game.isGameOver());
        for (Player player : game.getPlayers()) Assert.assertNull(player.getOutcome());
    }

    private SpellAbility ability(final Game game) {
        Player player = game.getPlayers().get(1);
        Card source = addCard("Runeclaw Bear", player);
        SpellAbility ability = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ 0 | Defined$ You | LifeAmount$ 0", source);
        ability.setActivatingPlayer(player);
        return ability;
    }
}
