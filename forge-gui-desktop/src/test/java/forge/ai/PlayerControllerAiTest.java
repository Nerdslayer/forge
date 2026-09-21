package forge.ai;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/** Regression coverage for failed AI action handling. */
public class PlayerControllerAiTest extends AITest {
    @Test
    public void failedActionIsReportedAndSuppressedUntilTheGameChanges() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card source = addCard("Grizzly Bears", ai);
        final SpellAbility gainLife = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ 1 | Defined$ You | LifeAmount$ 1", source);
        gainLife.setActivatingPlayer(ai);
        final PlayerControllerAi controller = (PlayerControllerAi) ai.getController();

        Assert.assertFalse(controller.playChosenSpellAbility(gainLife));
        Assert.assertTrue(controller.isFailedActionSuppressed(gainLife));
        Assert.assertFalse(controller.playChosenSpellAbility(gainLife));
    }
}
