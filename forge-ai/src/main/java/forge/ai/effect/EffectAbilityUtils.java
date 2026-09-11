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
        return isActiveBattlefieldTriggerIgnoringRequirements(source, trigger)
                && trigger.requirementsCheck(source.getGame());
    }

    static boolean isActiveBattlefieldTriggerIgnoringRequirements(
            final Card source, final Trigger trigger) {
        return source.isInPlay()
                && !source.isPhasedOut()
                && !trigger.isSuppressed()
                && !source.getGame().getTriggerHandler().isTriggerSuppressed(trigger.getMode())
                && trigger.zonesCheck(source.getZone());
    }

    static SpellAbility copyTriggerOutcome(final Card source, final Trigger trigger) {
        final SpellAbility outcome = resolveTriggerOutcome(source, trigger);
        return outcome == null ? null : outcome.copy(source, false);
    }

    /** Structural readers can inspect this root without copying or changing it. */
    static SpellAbility resolveTriggerOutcome(final Card source, final Trigger trigger) {
        SpellAbility outcome = trigger.getOverridingAbility();
        if (outcome == null && trigger.hasParam("Execute")) {
            outcome = AbilityFactory.getAbility(source, trigger.getParam("Execute"), trigger);
        }
        return outcome;
    }

    static SpellAbility copyActivatedAbility(final Card source,
            final SpellAbility ability) {
        // Payability and expected-use estimation are intentionally separate. An ability that is
        // not payable this moment may still be usable after the next untap or land drop.
        if (!source.isInPlay() || !ability.isActivatedAbility()) {
            return null;
        }
        final SpellAbility copied = ability.copy(source, false);
        copied.setActivatingPlayer(source.getController());
        if (!copied.getRestrictions().checkZoneRestrictions(source, copied)
                || !copied.getRestrictions().checkOtherRestrictions(
                        source, copied, source.getController())
                || (copied.getConditions() != null && !copied.getConditions().areMet(copied))
                || copied.getPayCosts() == null) {
            return null;
        }
        return copied;
    }

    static SpellAbility copyPayableActivatedAbility(final Card source,
            final SpellAbility ability) {
        final SpellAbility copied = copyActivatedAbility(source, ability);
        return copied != null && ComputerUtilCost.canPayCost(
                copied, source.getController(), false) ? copied : null;
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

    static boolean hasUnsupportedControlFlow(final SpellAbility ability) {
        for (final String param : ability.getMapParams().keySet()) {
            if (param.startsWith("Condition") || param.startsWith("Unless")
                    || "Optional".equals(param) || "Radiance".equals(param)) {
                return true;
            }
        }
        return false;
    }
}
