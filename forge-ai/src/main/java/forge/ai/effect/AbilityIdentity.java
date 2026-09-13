package forge.ai.effect;

import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

/** Stable, best-effort identity for one printed ability in a live card state. */
record AbilityIdentity(String path, boolean mapped) {
    static AbilityIdentity synthetic(final Card source, final String kind) {
        return new AbilityIdentity(face(source) + "/synthetic:" + kind, false);
    }

    static AbilityIdentity forTrigger(final Card source, final Trigger trigger) {
        if (source != null && trigger != null) {
            int index = 0;
            for (final Trigger candidate : source.getTriggers()) {
                if (candidate == trigger || candidate.equals(trigger)) {
                    return new AbilityIdentity(face(source) + "/trigger:" + index, true);
                }
                index++;
            }
        }
        return new AbilityIdentity(face(source) + "/unmapped-trigger", false);
    }

    static AbilityIdentity forSpellAbility(final Card source, final SpellAbility ability) {
        if (source != null && ability != null) {
            int index = 0;
            for (final SpellAbility candidate : source.getSpellAbilities()) {
                if (candidate == ability || candidate.equals(ability)) {
                    return new AbilityIdentity(face(source) + "/ability:" + index, true);
                }
                index++;
            }
        }
        return new AbilityIdentity(face(source) + "/unmapped-ability", false);
    }

    static AbilityIdentity forStaticAbility(final Card source,
            final forge.game.staticability.StaticAbility ability) {
        if (source != null && ability != null) {
            int index = 0;
            for (final forge.game.staticability.StaticAbility candidate : source.getStaticAbilities()) {
                if (candidate == ability || candidate.equals(ability)) {
                    return new AbilityIdentity(face(source) + "/static:" + index, true);
                }
                index++;
            }
            index = 0;
            for (final forge.game.staticability.StaticAbility candidate : source.getHiddenStaticAbilities()) {
                if (candidate == ability || candidate.equals(ability)) {
                    return new AbilityIdentity(face(source) + "/hidden-static:" + index, true);
                }
                index++;
            }
        }
        return new AbilityIdentity(face(source) + "/unmapped-static", false);
    }

    private static String face(final Card source) {
        return source == null || source.getCurrentStateName() == null
                ? "unknown-face" : source.getCurrentStateName().name();
    }
}
