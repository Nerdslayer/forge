package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.ai.ComputerUtilCombat;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/** Derives life-loss productions from supported damage dealt to players. */
final class DamageLifeLossProductionDeriver {
    // TODO(effect analysis): Support blocked/trample and other complex combat, mixed/permanent
    // targets, improve replacement prediction instead of rejecting applicable LifeReduced effects,
    // and account for damage redirection, damage that changes recipients or sources, optional
    // prevention, and other damage-to-life-loss rule variants.
    private DamageLifeLossProductionDeriver() {
    }

    static List<EffectProduction> derive(final EffectProduction production) {
        if (production.type() != EffectType.DAMAGE_DEALT) {
            return List.of();
        }

        final List<EffectEvent> events = new ArrayList<>();
        final Set<Player> alreadyLostLife = new HashSet<>();
        for (final EffectEvent damageEvent : production.events()) {
            final Object sourceValue = damageEvent.triggerParameters().get(AbilityKey.DamageSource);
            final Object targetValue = damageEvent.triggerParameters().get(AbilityKey.DamageTarget);
            final Object amountValue = damageEvent.triggerParameters().get(AbilityKey.DamageAmount);
            final Object combatValue = damageEvent.triggerParameters().get(AbilityKey.IsCombatDamage);
            if (!(sourceValue instanceof Card damageSource)
                    || !(targetValue instanceof Player recipient)
                    || !(amountValue instanceof Integer nominalAmount)
                    || !(combatValue instanceof Boolean isCombat)
                    || nominalAmount <= 0
                    || !recipient.canLoseLife()
                    || damageSource.isInfectDamage(recipient)) {
                continue;
            }

            final int predictedAmount = damageEvent.triggerParameters().containsKey(
                    AbilityKey.PreventedAmount) ? nominalAmount
                    : ComputerUtilCombat.predictDamageTo(
                            recipient, nominalAmount, damageSource, isCombat);
            if (predictedAmount <= 0
                    || LifeLossPrediction.hasApplicableLifeReductionReplacement(
                            recipient, predictedAmount, true)) {
                continue;
            }

            final Map<AbilityKey, Object> triggerParameters = new EnumMap<>(AbilityKey.class);
            triggerParameters.put(AbilityKey.Player, recipient);
            triggerParameters.put(AbilityKey.LifeAmount, predictedAmount);
            triggerParameters.put(AbilityKey.FirstTime,
                    recipient.getLifeLostThisTurn() == 0 && alreadyLostLife.add(recipient));
            final Object cause = damageEvent.triggerParameters().get(AbilityKey.Cause);
            if (cause instanceof SpellAbility spellAbility) {
                triggerParameters.put(AbilityKey.SpellAbility, spellAbility);
            }
            events.add(new EffectEvent(EffectType.LIFE_LOST, recipient,
                    List.of(new EffectEvent.Subject(recipient, 1)), triggerParameters));
        }
        return events.isEmpty() ? List.of() : List.of(new EffectProduction(
                production.source(), EffectType.LIFE_LOST, events,
                production.expectedBatches()));
    }

}
