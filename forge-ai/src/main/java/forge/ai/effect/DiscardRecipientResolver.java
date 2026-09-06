package forge.ai.effect;

import java.util.List;

import forge.game.ability.AbilityUtils;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;

/** Resolves fixed discard recipients and unambiguous opponent targets in two-player games. */
final class DiscardRecipientResolver {
    private DiscardRecipientResolver() {
    }

    static boolean hasSupportedTargetShape(final SpellAbility discard) {
        if (!discard.usesTargeting()) {
            return true;
        }
        final TargetRestrictions restrictions = discard.getTargetRestrictions();
        return restrictions != null
                && "1".equals(restrictions.getMinTargets())
                && "1".equals(restrictions.getMaxTargets());
    }

    static List<Player> resolve(final SpellAbility discard) {
        if (!discard.usesTargeting()) {
            return AbilityUtils.getDefinedPlayers(discard.getHostCard(),
                    discard.getParamOrDefault("Defined", "You"), discard);
        }

        final Player activator = discard.getActivatingPlayer();
        if (activator == null || activator.getGame().getPlayers().size() != 2
                || activator.getOpponents().size() != 1) {
            return List.of();
        }
        final Player opponent = activator.getOpponents().get(0);
        return discard.canTarget(opponent) ? List.of(opponent) : List.of();
    }
}
