package forge.ai.effect;

import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.staticability.StaticAbilityDisableTriggers;

/** Shared checks for matching normalized events against live Forge triggers. */
final class EffectEventMatchUtils {
    private EffectEventMatchUtils() {
    }

    static boolean passes(final EffectConsequence consequence,
            final Map<AbilityKey, Object> runParams) {
        try {
            return consequence.trigger().checkActivationLimit()
                    && consequence.trigger().meetsRequirementsOnTriggeredObjects(
                            consequence.source().getGame(), runParams)
                    && consequence.trigger().performTest(runParams)
                    && !StaticAbilityDisableTriggers.disabled(
                            consequence.source().getGame(), consequence.trigger(), runParams);
        } catch (final RuntimeException ignored) {
            return false;
        }
    }
}
