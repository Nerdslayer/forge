package forge.adventure.util;

import com.google.common.eventbus.Subscribe;
import forge.Forge;
import forge.card.GamePieceType;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.event.GameEventGameOutcome;
import forge.game.event.GameEventGameStarted;
import forge.game.event.GameEventPlayerLivesChanged;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;

public final class SecondWind {
    private final boolean enabled;
    private volatile boolean wonAtLowLife;

    public SecondWind(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean wonAtLowLife() {
        return wonAtLowLife;
    }

    public void trackGame(Game game, RegisteredPlayer adventurer) {
        for (Player player : game.getPlayers()) {
            if (player.getRegisteredPlayer() == adventurer) {
                game.subscribeToEvents(new GameTracker(player));
                return;
            }
        }
    }

    private final class GameTracker {
        private final Player adventurer;
        private boolean reachedLowLife;

        private GameTracker(Player adventurer) {
            this.adventurer = adventurer;
        }

        @Subscribe
        public void onGameStarted(GameEventGameStarted event) {
            reachedLowLife = adventurer.getLife() <= 5;
            if (enabled) {
                for (Player opponent : adventurer.getOpponents()) {
                    addEffect(opponent);
                }
            }
        }

        @Subscribe
        public void onLifeChanged(GameEventPlayerLivesChanged event) {
            if (event.player().getId() == adventurer.getId() && event.newLives() <= 5) {
                reachedLowLife = true;
            }
        }

        @Subscribe
        public void onOutcome(GameEventGameOutcome event) {
            if (reachedLowLife && adventurer.getGame().getOutcome().isWinner(adventurer.getRegisteredPlayer())) {
                wonAtLowLife = true;
            }
        }
    }

    private static void addEffect(Player opponent) {
        Game game = opponent.getGame();
        Card effect = new Card(game.nextCardId(), game);
        effect.setOwner(opponent);
        effect.setName(Forge.getLocalizer().getMessage("lblAdventureChallengeSecondWind"));
        effect.setGamePieceType(GamePieceType.EFFECT);
        effect.setGameTimestamp(game.getNextTimestamp());
        effect.setSVar("SecondWindLife", "Count$YourLifeTotal");
        effect.setSVar("SecondWindDraw", "DB$ Draw | Defined$ You | NumCards$ 2");
        var trigger = TriggerHandler.parseTrigger("Mode$ Always | TriggerZones$ Command | OneOff$ True"
                + " | CheckSVar$ SecondWindLife | SVarCompare$ LE" + (opponent.getStartingLife() / 2)
                + " | Condition$ ControllerSurvivesLifeTotal"
                + " | TriggerDescription$ When your life total is at or below half your starting life, gain 5 life and draw two cards.",
                effect, true);
        trigger.setOverridingAbility(AbilityFactory.getAbility(
                "DB$ GainLife | Defined$ You | LifeAmount$ 5 | SubAbility$ SecondWindDraw", effect));
        effect.addTrigger(trigger);
        effect.updateStateForView();

        var command = opponent.getZone(ZoneType.Command);
        command.add(effect);
        game.getTriggerHandler().registerActiveTrigger(effect, false);
        opponent.updateZoneForView(command);
    }
}
