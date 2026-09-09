package forge.ai.effect;

import java.util.List;

import forge.game.ability.AbilityUtils;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;

/** Resolves fixed player recipients and unambiguous opponent targets in two-player games. */
final class PlayerRecipientResolver {
    private PlayerRecipientResolver() {
    }

    static boolean hasSupportedTargetShape(final SpellAbility ability) {
        if (!ability.usesTargeting()) {
            return true;
        }
        final TargetRestrictions restrictions = ability.getTargetRestrictions();
        return restrictions != null
                && "1".equals(restrictions.getMinTargets())
                && "1".equals(restrictions.getMaxTargets());
    }

    static List<Player> resolve(final SpellAbility ability, final OutcomeEvaluationContext context) {
        if (context.state() != null && ability.usesTargeting()) {
            final List<Player> players = new java.util.ArrayList<>();
            ability.getTargets().getTargetPlayers().forEach(players::add);
            return players;
        }
        return resolve(ability);
    }

    static List<Player> resolve(final SpellAbility ability) {
        if (!ability.usesTargeting()) {
            return AbilityUtils.getDefinedPlayers(ability.getHostCard(),
                    ability.getParamOrDefault("Defined", "You"), ability);
        }

        final Player activator = ability.getActivatingPlayer();
        if (activator == null || activator.getGame().getPlayers().size() != 2
                || activator.getOpponents().size() != 1) {
            return List.of();
        }
        final Player opponent = activator.getOpponents().get(0);
        return ability.canTarget(opponent) ? List.of(opponent) : List.of();
    }
}
