package forge.game;

import forge.ai.AITest;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import org.testng.Assert;
import org.testng.annotations.Test;

public class DrawOutcomeTest extends AITest {
    @Test
    public void spellDrawReportsTheEffectRatherThanInventingAnAgreement() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card source = addCard("Runeclaw Bear", player);
        SpellAbility ability = AbilityFactory.getAbility("DB$ GameDrawn", source);
        ability.setActivatingPlayer(player);
        AbilityUtils.resolve(ability);
        Assert.assertEquals(game.getOutcome().getWinCondition(), GameEndReason.Draw);
        for (Player participant : game.getPlayers()) {
            Assert.assertEquals(participant.getOutcome().lossState, GameLossReason.DrawEffect);
            Assert.assertEquals(participant.getOutcome().toString(), "has drawn due to the effect of Runeclaw Bear");
        }
    }

    @Test
    public void agreedDrawStillRecordsAgreement() {
        Game game = initAndCreateGame();
        for (Player player : game.getPlayers()) player.intentionalDraw();
        game.setGameOver(GameEndReason.Draw);
        Assert.assertTrue(game.getOutcome().isDraw());
        for (Player participant : game.getPlayers()) {
            Assert.assertEquals(participant.getOutcome().lossState, GameLossReason.IntentionalDraw);
            Assert.assertEquals(participant.getOutcome().toString(), "has accepted that the game is a draw");
        }
    }
}
