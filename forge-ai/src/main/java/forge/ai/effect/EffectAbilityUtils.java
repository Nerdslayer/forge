package forge.ai.effect;

import java.util.Set;

import forge.ai.ComputerUtilCost;
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

    static SpellAbility copyPayableActivatedAbility(final Card source,
            final SpellAbility ability) {
        // TODO(effect analysis): Replace this one-use/current-payability gate with occurrence and
        // likelihood modeling for repeatable activations, competing costs/tap uses, timing,
        // alternative resources, and future legal targets.
        if (!source.isInPlay() || !ability.isActivatedAbility()) {
            return null;
        }
        final SpellAbility copied = ability.copy(source, false);
        copied.setActivatingPlayer(source.getController());
        if (!copied.getRestrictions().checkZoneRestrictions(source, copied)
                || !copied.getRestrictions().checkOtherRestrictions(
                        source, copied, source.getController())
                || (copied.getConditions() != null && !copied.getConditions().areMet(copied))
                || !ComputerUtilCost.canPayCost(copied, source.getController(), false)) {
            return null;
        }
        return copied;
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
