package forge.ai.effect;

import java.util.function.Predicate;

import forge.ai.ComputerUtilCard;
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
}
