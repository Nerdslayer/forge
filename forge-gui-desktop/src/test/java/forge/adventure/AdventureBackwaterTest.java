package forge.adventure;

import forge.adventure.util.Backwater;
import forge.ai.AITest;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameType;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.event.GameEventGameStarted;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.Test;

public class AdventureBackwaterTest extends AITest {
    @Test
    public void onlyTheAdventurersFirstLandEntersTappedEachGame() {
        for (int gameNumber = 0; gameNumber < 2; gameNumber++) {
            Game game = initAndCreateGame();
            Player adventurer = game.getPlayers().get(0);
            Player opponent = game.getPlayers().get(1);
            Backwater.trackGame(game, adventurer.getRegisteredPlayer());
            Card startingLand = addCard("Mountain", adventurer);
            game.fireEvent(new GameEventGameStarted(GameType.Adventure, adventurer, game.getPlayers()));
            Assert.assertFalse(startingLand.isTapped());

            Card opposingLand = addCardToZone("Island", opponent, ZoneType.Hand);
            Assert.assertFalse(opponent.playLand(opposingLand, null).isTapped());

            Card creature = addCardToZone("Runeclaw Bear", adventurer, ZoneType.Hand);
            Assert.assertFalse(game.getAction().moveToPlay(creature, null, AbilityKey.newMap()).isTapped());

            Card fetchedLand = addCardToZone("Forest", adventurer, ZoneType.Library);
            Assert.assertTrue(game.getAction().moveToPlay(fetchedLand, null, AbilityKey.newMap()).isTapped());

            Card nextLand = addCardToZone("Forest", adventurer, ZoneType.Hand);
            Assert.assertFalse(adventurer.playLand(nextLand, null).isTapped());
        }
    }

    @Test
    public void commonsOnlyUnlockAllowsLandsOfAnyRarity() {
        var cards = FModel.getMagicDb().getCommonCards();
        var common = cards.getCard("Runeclaw Bear", "M10");
        var rareLand = cards.getCard("Temple Garden", "RTR");
        var rareCreature = cards.getCard("Glorybringer", "AKH");
        Deck deck = new Deck();
        deck.getMain().add(common, 4);
        deck.getMain().add(rareLand, 4);
        Assert.assertTrue(Backwater.qualifies(deck));

        deck.getMain().add(rareCreature);
        Assert.assertFalse(Backwater.qualifies(deck));
        deck.getMain().remove(rareCreature);

        deck.getOrCreate(DeckSection.Sideboard).add(rareCreature);
        Assert.assertFalse(Backwater.qualifies(deck));
        deck.get(DeckSection.Sideboard).clear();

        deck.getOrCreate(DeckSection.Commander).add(rareCreature);
        Assert.assertFalse(Backwater.qualifies(deck));
    }
}
