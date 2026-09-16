package forge.ai.effect;

import java.util.function.Predicate;

import forge.ai.ComputerUtilCard;
import forge.ai.ability.FightAi;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Selects one public battlefield target using the ability controller's perspective. */
final class EffectCardTargetSelector {
    // TODO(effect analysis): Include relationship value, zone-aware value, beneficial self-targets,
    // temporary versus permanent movement, commanders, revealed alternate-zone utility, and
    // uncertainty or deliberate non-use. This initial selector models permanent departure only.
    private EffectCardTargetSelector() {
    }

    static Card chooseBestDepartureTarget(final SpellAbility ability,
            final Predicate<Card> additionalFilter) {
        if (!AffectedCardResolver.supportsSingleBattlefieldTarget(ability)) {
            return null;
        }
        final Player activator = ability.getActivatingPlayer();
        if (activator == null) {
            return null;
        }

        Card best = null;
        int bestValue = Integer.MIN_VALUE;
        for (final Card candidate : ability.getHostCard().getGame()
                .getCardsIn(ZoneType.Battlefield)) {
            final SpellAbility targetCheck = ability.copy(ability.getHostCard(), false);
            targetCheck.setActivatingPlayer(activator);
            targetCheck.resetTargets();
            if (!targetCheck.canTarget(candidate) || !additionalFilter.test(candidate)) {
                continue;
            }
            final int permanentValue = ComputerUtilCard.evaluatePermanent(activator, candidate);
            final int departureValue = candidate.getController().isOpponentOf(activator)
                    ? permanentValue : EffectMath.negate(permanentValue);
            if (best == null || departureValue > bestValue) {
                best = candidate;
                bestValue = departureValue;
            }
        }
        return best;
    }

    static Card chooseBestControlChangeTarget(final SpellAbility ability,
            final Player newController, final Predicate<Card> additionalFilter) {
        if (!AffectedCardResolver.supportsSingleBattlefieldTarget(ability)
                || newController == null) {
            return null;
        }
        final Player activator = ability.getActivatingPlayer();
        if (activator == null) {
            return null;
        }

        Card best = null;
        int bestValue = Integer.MIN_VALUE;
        for (final Card candidate : ability.getHostCard().getGame()
                .getCardsIn(ZoneType.Battlefield)) {
            final SpellAbility targetCheck = ability.copy(ability.getHostCard(), false);
            targetCheck.setActivatingPlayer(activator);
            targetCheck.resetTargets();
            if (!targetCheck.canTarget(candidate) || !additionalFilter.test(candidate)) {
                continue;
            }
            final int permanentValue = ComputerUtilCard.evaluatePermanent(activator, candidate);
            final int changeValue = candidate.getController() == newController
                    ? 0 : candidate.getController().isOpponentOf(newController)
                            ? permanentValue : EffectMath.negate(permanentValue);
            if (best == null || changeValue > bestValue) {
                best = candidate;
                bestValue = changeValue;
            }
        }
        return best;
    }

    static Card chooseBestFightTarget(final SpellAbility ability, final Card fighter) {
        if (!AffectedCardResolver.supportsSingleBattlefieldTarget(ability)
                || fighter == null || !fighter.isCreature()) {
            return null;
        }
        final Player activator = ability.getActivatingPlayer();
        if (activator == null) {
            return null;
        }

        Card best = null;
        int bestValue = Integer.MIN_VALUE;
        for (final Card candidate : ability.getHostCard().getGame()
                .getCardsIn(ZoneType.Battlefield)) {
            if (!candidate.isCreature()) {
                continue;
            }
            final SpellAbility targetCheck = ability.copy(ability.getHostCard(), false);
            targetCheck.setActivatingPlayer(activator);
            targetCheck.resetTargets();
            if (!targetCheck.canTarget(candidate)) {
                continue;
            }
            final int candidateValue = ComputerUtilCard.evaluatePermanent(activator, candidate);
            int fightValue = candidate.getController().isOpponentOf(activator)
                    ? candidateValue : EffectMath.negate(candidateValue);
            if (FightAi.canKill(fighter, candidate, 0)) {
                fightValue = EffectMath.add(fightValue, 1000);
                if (!FightAi.canKill(candidate, fighter, 0)) {
                    fightValue = EffectMath.add(fightValue, 1000);
                }
            }
            if (best == null || fightValue > bestValue) {
                best = candidate;
                bestValue = fightValue;
            }
        }
        return best;
    }
}
