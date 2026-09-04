package forge.ai;

import java.util.List;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** Builds a next-combat prediction from information available to an observing player. */
public final class NextCombatPrediction {
    private NextCombatPrediction() {
    }

    public static Combat predict(final Player observer, final Player attackingPlayer,
            final Player defendingPlayer) {
        if (observer == null || attackingPlayer == null || defendingPlayer == null
                || attackingPlayer.getGame().getPhaseHandler().getPhase() == null
                || !attackingPlayer.isOpponentOf(defendingPlayer)) {
            return null;
        }
        return AiCache.getCached("nextCombatPrediction",
                () -> build(observer, attackingPlayer, defendingPlayer),
                List.of(AiCache::identity, AiCache::identity, AiCache::identity),
                observer, attackingPlayer, defendingPlayer);
    }

    private static Combat build(final Player observer, final Player attackingPlayer,
            final Player defendingPlayer) {
        final Combat combat = new Combat(attackingPlayer);
        if (attackingPlayer == observer && attackingPlayer.getController().isAI()) {
            final Combat completePrediction = ((PlayerControllerAi) attackingPlayer.getController())
                    .getAi().getPredictedCombatNextTurn();
            for (final Card attacker : completePrediction.getAttackers()) {
                final GameEntity defender = completePrediction.getDefenderByAttacker(attacker);
                if (isDefendedBy(defender, defendingPlayer)) {
                    combat.addAttacker(attacker, defender);
                }
            }
        } else {
            for (final Card attacker : attackingPlayer.getCardsIn(ZoneType.Battlefield)) {
                if (AttackLikelihoodEvaluator.estimateNextTurn(observer, attacker).isExpected()) {
                    combat.addAttacker(attacker, defendingPlayer);
                }
            }
        }

        if (combat.getAttackers().isEmpty()) {
            return combat;
        }
        new AiBlockController(defendingPlayer, defendingPlayer != observer)
                .assignBlockersForCombat(combat);
        return combat;
    }

    private static boolean isDefendedBy(final GameEntity defender,
            final Player defendingPlayer) {
        if (defender == defendingPlayer) {
            return true;
        }
        if (!(defender instanceof Card card)) {
            return false;
        }
        return card.isBattle()
                ? card.getProtectingPlayer() == defendingPlayer
                : card.getController() == defendingPlayer;
    }
}
