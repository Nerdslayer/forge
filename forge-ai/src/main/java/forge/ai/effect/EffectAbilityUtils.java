package forge.ai.effect;

import java.util.Set;

import forge.game.ability.AbilityFactory;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

/** Shared safe inspection helpers for parsed effect abilities. */
final class EffectAbilityUtils {
    private EffectAbilityUtils() {
    }

    static boolean isActiveBattlefieldTrigger(final Card source, final Trigger trigger) {
        return source.isInPlay()
                && !source.isPhasedOut()
                && !trigger.isSuppressed()
                && !source.getGame().getTriggerHandler().isTriggerSuppressed(trigger.getMode())
                && trigger.zonesCheck(source.getZone())
                && trigger.requirementsCheck(source.getGame());
    }

    static SpellAbility copyTriggerOutcome(final Card source, final Trigger trigger) {
        SpellAbility outcome = trigger.getOverridingAbility();
        if (outcome == null && trigger.hasParam("Execute")) {
            outcome = AbilityFactory.getAbility(source, trigger.getParam("Execute"), trigger);
        }
        return outcome == null ? null : outcome.copy(source, false);
    }

    static SpellAbility findOutcome(final SpellAbility root, final ApiType api) {
        SpellAbility current = root;
        while (current != null) {
            if (current.getApi() == api) {
                return current;
            }
            current = current.getSubAbility();
        }
        return null;
    }

    static boolean hasOnlyParams(final Trigger trigger, final Set<String> allowed) {
        return allowed.containsAll(trigger.getMapParams().keySet());
    }
}
