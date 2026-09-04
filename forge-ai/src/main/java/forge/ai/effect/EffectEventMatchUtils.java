package forge.ai.effect;

import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.staticability.StaticAbilityDisableTriggers;
import forge.game.trigger.Trigger;

/** Shared checks for matching normalized events against live Forge triggers. */
final class EffectEventMatchUtils {
    private EffectEventMatchUtils() {
    }

    static boolean passes(final EffectConsequence consequence,
            final Map<AbilityKey, Object> runParams) {
        return passes(consequence, consequence.trigger(), runParams);
    }

    static boolean passes(final EffectConsequence consequence, final Trigger trigger,
            final Map<AbilityKey, Object> runParams) {
        try {
            return passesCommon(consequence, trigger, runParams)
                    && trigger.performTest(runParams);
        } catch (final RuntimeException ignored) {
            return false;
        }
    }

    static boolean passesCommon(final EffectConsequence consequence,
            final Map<AbilityKey, Object> runParams) {
        return passesCommon(consequence, consequence.trigger(), runParams);
    }

    private static boolean passesCommon(final EffectConsequence consequence,
            final Trigger trigger, final Map<AbilityKey, Object> runParams) {
        try {
            return trigger.checkActivationLimit()
                    && trigger.meetsRequirementsOnTriggeredObjects(
                            consequence.source().getGame(), runParams)
                    && !StaticAbilityDisableTriggers.disabled(
                            consequence.source().getGame(), trigger, runParams);
        } catch (final RuntimeException ignored) {
            return false;
        }
    }
}
