package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;

/** Derives predicted unblocked combat-damage productions from attack predictions. */
final class CombatDamageProductionDeriver {
    // TODO(effect analysis): Support trample and other blocked damage to players, damage assignment
    // variants, blockers that leave combat, combat damage to planeswalkers and battles, multiplayer
    // attacks not aimed at the evaluating AI, and changes to power/keywords before damage.
    private CombatDamageProductionDeriver() {
    }

    static List<EffectProduction> derive(final EffectProduction production) {
        if (production.type() != EffectType.ATTACKED_OR_BLOCKED) {
            return List.of();
        }

        final Card attacker = production.source();
        if (!attacker.isCreature() || attacker.getNetCombatDamage() <= 0) {
            return List.of();
        }
        for (final EffectEvent attackEvent : production.events()) {
            final Map<AbilityKey, Object> parameters = attackEvent.triggerParameters();
            // The disposition event has Defender but not Attacked. A Blockers entry means that
            // combat prediction expects this attacker to be blocked.
            if (parameters.containsKey(AbilityKey.Attacked)
                    || parameters.containsKey(AbilityKey.Blockers)
                    || !(parameters.get(AbilityKey.Defender) instanceof Player defender)) {
                continue;
            }

            final int damageSteps = attacker.hasDoubleStrike() ? 2 : 1;
            final List<EffectProduction> result = new ArrayList<>();
            for (int i = 0; i < damageSteps; i++) {
                final EffectEvent damageEvent = DamageEventFactory.create(
                        attacker, defender, attacker.getNetCombatDamage(), true, null, defender);
                if (damageEvent != null) {
                    // Each combat-damage step is a separate batch, which matters for
                    // DamageDoneOnce and DamageDealtOnce triggers with double strike.
                    result.add(new EffectProduction(attacker, EffectType.DAMAGE_DEALT,
                            List.of(damageEvent), production.expectedBatches()));
                }
            }
            return result;
        }
        return List.of();
    }
}
