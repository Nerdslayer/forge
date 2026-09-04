package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.trigger.TriggerType;

/** Matches predicted attack events against supported Attacks triggers. */
final class AttackEventMatcher implements EffectEventMatcher {
    static final AttackEventMatcher INSTANCE = new AttackEventMatcher();

    private AttackEventMatcher() {
    }

    @Override
    public List<EffectMatch> match(final EffectProduction production,
            final EffectConsequence consequence) {
        if (production.type() != EffectType.ATTACKED_OR_BLOCKED
                || consequence.observedType() != EffectType.ATTACKED_OR_BLOCKED) {
            return List.of();
        }

        final List<EffectMatch> matches = new ArrayList<>();
        for (final EffectEvent event : production.events()) {
            if (!matchesEventKind(consequence, event)) {
                continue;
            }
            final Map<AbilityKey, Object> runParams = new EnumMap<>(AbilityKey.class);
            runParams.putAll(event.triggerParameters());
            if (EffectEventMatchUtils.passes(consequence, runParams)) {
                matches.add(new EffectMatch(new EffectEvent(event.type(), event.player(),
                        event.subjects(), runParams), 1));
            }
        }
        return matches;
    }

    private static boolean matchesEventKind(final EffectConsequence consequence,
            final EffectEvent event) {
        final Map<AbilityKey, Object> params = event.triggerParameters();
        final TriggerType mode = consequence.trigger().getMode();
        if (mode == TriggerType.Attacks) {
            return params.containsKey(AbilityKey.Attacked);
        }
        if (mode == TriggerType.Blocks) {
            return params.containsKey(AbilityKey.Blocker)
                    && params.containsKey(AbilityKey.Attackers);
        }
        if (mode == TriggerType.AttackerBlocked) {
            return params.containsKey(AbilityKey.Attacker)
                    && params.containsKey(AbilityKey.Blockers);
        }
        if (mode == TriggerType.AttackerBlockedByCreature) {
            return params.containsKey(AbilityKey.Attacker)
                    && params.containsKey(AbilityKey.Blocker)
                    && !params.containsKey(AbilityKey.Attackers);
        }
        return mode == TriggerType.AttackerUnblocked
                && params.containsKey(AbilityKey.Attacker)
                && params.containsKey(AbilityKey.Defender)
                && !params.containsKey(AbilityKey.Attacked)
                && !params.containsKey(AbilityKey.Blockers);
    }
}
