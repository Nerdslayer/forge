package forge.ai.effect;

import java.util.List;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Applies shared activation-action valuation only to exact legacy ties. */
public final class ActivateAbilityValueTieBreaker {
    private ActivateAbilityValueTieBreaker() {
    }

    /**
     * Preserves the existing activation ordering except when all members of an exact legacy tie
     * are currently payable abilities from permanents controlled by the deciding AI.
     */
    public static void apply(final Player ai, final List<SpellAbility> abilities) {
        if (ai == null || abilities == null || abilities.size() < 2) {
            return;
        }

        ActionValueTieBreaker.apply(ai, abilities, ValuationContext.forActivation(ai, true),
                sa -> isSupportedActivationCandidate(ai, sa),
                sa -> new ActivateValuationAction(sa.getHostCard(), sa));
    }

    private static boolean isSupportedActivationCandidate(final Player ai,
            final SpellAbility sa) {
        if (sa == null || !sa.isActivatedAbility() || sa.getHostCard() == null) {
            return false;
        }
        final Card host = sa.getHostCard();
        return host.isInZone(ZoneType.Battlefield) && host.getController() == ai;
    }
}
