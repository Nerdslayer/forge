package forge.ai;

import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.staticability.StaticAbilityCantCrew;
import forge.game.trigger.TriggerType;

/**
 * Estimates whether a creature will attack on its controller's next combat.
 *
 * <p>The controller's own AI may use its complete predicted combat. Predictions for another
 * player deliberately use only public battlefield information available to the observer.</p>
 */
public final class AttackLikelihoodEvaluator {
    public enum Likelihood {
        IMPOSSIBLE,
        UNLIKELY,
        LIKELY,
        FORCED;

        public boolean isExpected() {
            return this == LIKELY || this == FORCED;
        }
    }

    private AttackLikelihoodEvaluator() {
    }

    public static Likelihood estimateNextTurn(final Player observer, final Card attacker) {
        if (observer == null || attacker == null || attacker.getController() == null) {
            return Likelihood.IMPOSSIBLE;
        }

        final Player attackingPlayer = attacker.getController();
        if (attackingPlayer == observer && attackingPlayer.getController().isAI()
                && attacker.isCreature()) {
            final AiController ai = ((PlayerControllerAi) attackingPlayer.getController()).getAi();
            return ai.getPredictedCombatNextTurn().isAttacking(attacker)
                    ? Likelihood.LIKELY : Likelihood.UNLIKELY;
        }

        return estimateFromPublicInformation(observer, attacker);
    }

    private static Likelihood estimateFromPublicInformation(final Player observer,
            final Card attacker) {
        if (!attacker.getController().isOpponentOf(observer)
                || !canAttackObserverNextTurn(attacker, observer)) {
            return Likelihood.IMPOSSIBLE;
        }

        // TODO(ai combat prediction): Derive mandatory attacks from AttackRequirements rather
        // than relying on the legacy hint, and distinguish which defender must be attacked.
        if ("True".equalsIgnoreCase(attacker.getSVar("MustAttack"))) {
            return Likelihood.FORCED;
        }

        final boolean hasAttackIncentive = attacker.hasKeyword(Keyword.ANNIHILATOR)
                || "TRUE".equalsIgnoreCase(attacker.getSVar("HasAttackEffect"))
                || attacker.getTriggers().stream().anyMatch(trigger ->
                        trigger.getMode() == TriggerType.Attacks
                        && !trigger.isSuppressed()
                        && trigger.zonesCheck(attacker.getZone())
                        && trigger.requirementsCheck(attacker.getGame()));
        if (hasAttackIncentive || isPotentiallyLethal(attacker, observer)) {
            return Likelihood.LIKELY;
        }

        boolean canBeBlocked = false;
        boolean canBeKilledByBlocker = false;
        boolean tradesAtLeastEvenly = true;
        final int attackerValue = ComputerUtilCard.evaluateCreature(attacker);
        for (final Card blocker : observer.getCreaturesInPlay()) {
            if (!CombatUtil.canBlock(attacker, blocker, true)) {
                continue;
            }
            canBeBlocked = true;
            if (ComputerUtilCombat.canDestroyAttacker(
                    attacker.getController(), attacker, blocker, null, true)) {
                canBeKilledByBlocker = true;
                if (ComputerUtilCard.evaluateCreature(blocker) < attackerValue) {
                    tradesAtLeastEvenly = false;
                }
            }
        }

        if (!canBeBlocked || !canBeKilledByBlocker || tradesAtLeastEvenly) {
            return Likelihood.LIKELY;
        }

        // TODO(ai combat prediction): Add public whole-combat considerations such as attacks that
        // overload blockers, preserving blockers against the observer's crackback, attack taxes,
        // multiplayer defender choice, and uncertain but visible activated combat abilities.
        return Likelihood.UNLIKELY;
    }

    private static boolean canAttackObserverNextTurn(final Card attacker, final Player observer) {
        if (attacker.isCreature()) {
            return ComputerUtilCombat.canAttackNextTurn(attacker, observer);
        }
        if (!attacker.getType().hasSubtype("Vehicle") || !attacker.hasKeyword(Keyword.CREW)
                || !CombatUtil.canAttackNextTurn(attacker, observer)) {
            return false;
        }

        int availablePower = 0;
        for (final Card creature : attacker.getController().getCreaturesInPlay()) {
            if (creature != attacker && canCrewNextTurn(creature)) {
                availablePower += Math.max(0, creature.getNetPower());
            }
        }
        return availablePower >= attacker.getKeywordMagnitude(Keyword.CREW);
    }

    private static boolean canCrewNextTurn(final Card creature) {
        return !creature.isPhasedOut()
                && !StaticAbilityCantCrew.cantCrew(creature)
                && (!creature.isTapped() || (creature.getCounters(CounterEnumType.STUN) == 0
                        && creature.canUntap(creature.getController(), true)));
    }

    private static boolean isPotentiallyLethal(final Card attacker, final Player observer) {
        if (observer.cantLoseForZeroOrLessLife() || !observer.canLoseLife()) {
            return false;
        }
        return ComputerUtilCombat.damageIfUnblocked(attacker, observer, null, true)
                >= observer.getLife();
    }
}
