package forge.ai.effect;

import java.util.List;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Applies shared cast-action valuation only to exact legacy ties in spell selection. */
public final class CastCardValueTieBreaker {
    private CastCardValueTieBreaker() {
    }

    /**
     * Preserves the existing spell ordering except when all members of an exact legacy tie are
     * supported cards from the deciding AI's hand. Unsupported cards and abilities from other
     * zones remain in their original order.
     */
    public static void apply(final Player ai, final List<SpellAbility> abilities) {
        if (ai == null || abilities == null || abilities.size() < 2) {
            return;
        }

        ActionValueTieBreaker.apply(ai, abilities, ValuationContext.forCast(ai, true),
                sa -> isSupportedCastCandidate(ai, sa), CastValuationAction::new);
    }

    private static boolean isSupportedCastCandidate(final Player ai, final SpellAbility sa) {
        if (sa == null || sa.getHostCard() == null || !sa.getHostCard().isInZone(ZoneType.Hand)) {
            return false;
        }
        final Card host = sa.getHostCard();
        return host.getOwner() == ai || host.getController() == ai;
    }
}
