package forge.ai.effect;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCard;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;

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

    @Test
    public void mastermindUsesCardValueOnlyForOwnDiscardCmcTies() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card shock = addCardToZone("Shock", ai, ZoneType.Hand);
        final Card lavaSpike = addCardToZone("Lava Spike", ai, ZoneType.Hand);
        final CardCollection validCards = new CardCollection(ai.getCardsIn(ZoneType.Hand));

        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Mastermind");
        final Card legacyChoice = ComputerUtilCard.getWorstAI(validCards);
        final Card expected = UnifiedActionValueEvaluator.evaluate(
                new DiscardValuationAction(shock, ai), ValuationContext.forDiscard(ai, true))
                .netValue() >= UnifiedActionValueEvaluator.evaluate(
                new DiscardValuationAction(lavaSpike, ai), ValuationContext.forDiscard(ai, true))
                .netValue() ? shock : lavaSpike;

        final CardCollection result = ((PlayerControllerAi) ai.getController()).getAi()
                .getCardsToDiscard(1, 1, validCards, null);

        Assert.assertEquals(result.size(), 1);
        Assert.assertSame(result.get(0), expected);
        Assert.assertNotSame(result.get(0), legacyChoice);
    }

    @Test
    public void castValueTieBreakerPreservesLegacyTieScope() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card lowerValue = addCardToZone("Craw Wurm", ai, ZoneType.Hand);
        final Card higherValue = addCardToZone("Colossal Dreadmaw", ai, ZoneType.Hand);
        final List<SpellAbility> abilities = new ArrayList<>(ComputerUtilAbility.getSpellAbilities(
                new CardCollection(List.of(lowerValue, higherValue)), ai));

        Assert.assertEquals(ComputerUtilAbility.saEvaluator.compare(abilities.get(0),
                abilities.get(1)), 0);
        CastCardValueTieBreaker.apply(ai, abilities);

        Assert.assertSame(abilities.get(0).getHostCard(), higherValue);
    }
}
