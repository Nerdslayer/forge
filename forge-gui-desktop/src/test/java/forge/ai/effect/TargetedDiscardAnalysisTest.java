package forge.ai.effect;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.ComputerUtil;
import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Regression coverage for Mastermind's targeted-discard valuation tie-breaker. */
public class TargetedDiscardAnalysisTest extends AITest {
    @Test
    public void mastermindUsesCardValueOnlyForLegacyCmcTies() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        final Card source = addCard("Mind Rot", ai);
        final Card lowValue = addCardToZone("Craw Wurm", opponent, ZoneType.Hand);
        final Card highValue = addCardToZone("Colossal Dreadmaw", opponent, ZoneType.Hand);
        final CardCollection validCards = new CardCollection(opponent.getCardsIn(ZoneType.Hand));
        final CardCollection visibleToChooser = new CardCollection(opponent.getCardsIn(ZoneType.Hand));
        final SpellAbility discard = AbilityFactory.getAbility(
                "DB$ Discard | Defined$ Opponent | Mode$ YouChoose | NumCards$ 1", source);

        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Mastermind");
        final CardCollection result = ComputerUtil.getCardsToDiscardFromOpponent(ai, opponent,
                discard, validCards, 1, 1, visibleToChooser);

        Assert.assertEquals(result.size(), 1);
        Assert.assertSame(result.get(0), highValue);
        Assert.assertNotSame(result.get(0), lowValue);
    }

    @Test
    public void targetedDiscardAnalysisIsOnlyEnabledInMastermindProfile() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final LobbyPlayerAi lobby = (LobbyPlayerAi) ai.getLobbyPlayer();

        lobby.setAiProfile("Default");
        Assert.assertFalse(AiProfileUtil.getBoolProperty(ai, AiProps.ENABLE_TARGETED_DISCARD_ANALYSIS));

        lobby.setAiProfile("Mastermind");
        Assert.assertTrue(AiProfileUtil.getBoolProperty(ai, AiProps.ENABLE_TARGETED_DISCARD_ANALYSIS));
    }
}
