package forge.adventure.util;

import com.google.common.eventbus.Subscribe;
import forge.Forge;
import forge.card.GamePieceType;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventGameStarted;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.replacement.ReplacementHandler;
import forge.game.zone.ZoneType;
import forge.item.PaperCardPredicates;

public final class Backwater {
    private final Player adventurer;
    private Card effect;

    private Backwater(Player adventurer) {
        this.adventurer = adventurer;
    }

    public static boolean qualifies(Deck deck) {
        return !deck.getMain().isEmpty() && deck.getAllCardsInASinglePool().getFilteredPool(
                card -> !card.getRules().getType().isLand() && !PaperCardPredicates.IS_COMMON.test(card)).isEmpty();
    }

    public static void trackGame(Game game, RegisteredPlayer adventurer) {
        for (Player player : game.getPlayers()) {
            if (player.getRegisteredPlayer() == adventurer) {
                game.subscribeToEvents(new Backwater(player));
                return;
            }
        }
    }

    @Subscribe
    public void onGameStarted(GameEventGameStarted event) {
        Game game = adventurer.getGame();
        effect = new Card(game.nextCardId(), game);
        effect.setOwner(adventurer);
        effect.setName(Forge.getLocalizer().getMessage("lblAdventureChallengeBackwater"));
        effect.setGamePieceType(GamePieceType.EFFECT);
        effect.setGameTimestamp(game.getNextTimestamp());
        effect.setSVar("BackwaterTap", "DB$ Tap | ETB$ True | Defined$ ReplacedCard");
        var replacement = ReplacementHandler.parseReplacement("Event$ Moved | ActiveZones$ Command"
                + " | ValidCard$ Land.YouCtrl | Destination$ Battlefield | ReplaceWith$ BackwaterTap"
                + " | ReplacementResult$ Updated | Description$ "
                + Forge.getLocalizer().getMessage("lblAdventureChallengeBackwaterDescription"), effect, true);
        effect.addReplacementEffect(replacement);
        effect.updateStateForView();

        var command = adventurer.getZone(ZoneType.Command);
        command.add(effect);
        adventurer.updateZoneForView(command);
    }

    @Subscribe
    public void onCardChangedZone(GameEventCardChangeZone event) {
        if (effect == null || event.to() == null || event.to().zoneType() != ZoneType.Battlefield
                || !adventurer.getView().equals(event.to().player())
                || (event.from() != null && event.from().zoneType() == ZoneType.Battlefield)) {
            return;
        }
        // Consume the effect on entry so a redirected move leaves it available for the next land.
        if (adventurer.getCardsIn(ZoneType.Battlefield).stream()
                .anyMatch(card -> card.getId() == event.card().getId() && card.isLand())) {
            var command = adventurer.getZone(ZoneType.Command);
            command.remove(effect);
            adventurer.updateZoneForView(command);
            effect = null;
        }
    }
}
