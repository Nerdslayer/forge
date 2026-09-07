package forge.ai.effect;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.ai.ComputerUtilCombat;
import forge.game.GameEntity;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/** Builds normalized damage events using Forge's current damage forecast. */
final class DamageEventFactory {
    private DamageEventFactory() {
    }

    static EffectEvent create(final Card source, final GameEntity recipient,
            final int nominalAmount, final boolean combatDamage,
            final SpellAbility cause, final Player defendingPlayer) {
        final int predictedAmount = ComputerUtilCombat.predictDamageTo(
                recipient, nominalAmount, source, combatDamage);
        if (predictedAmount <= 0) {
            return null;
        }

        final Map<AbilityKey, Object> triggerParameters = new EnumMap<>(AbilityKey.class);
        triggerParameters.put(AbilityKey.DamageSource, source);
        triggerParameters.put(AbilityKey.DamageTarget, recipient);
        triggerParameters.put(AbilityKey.DamageAmount, predictedAmount);
        // Presence of this key also tells derived life-loss analysis that damage prediction has
        // already been applied. Its value mirrors the useful portion of Forge's event data.
        triggerParameters.put(AbilityKey.PreventedAmount,
                Math.max(0, nominalAmount - predictedAmount));
        triggerParameters.put(AbilityKey.IsCombatDamage, combatDamage);
        if (cause != null) {
            triggerParameters.put(AbilityKey.Cause, cause);
        }
        if (defendingPlayer != null) {
            triggerParameters.put(AbilityKey.DefendingPlayer, defendingPlayer);
        }
        return new EffectEvent(EffectType.DAMAGE_DEALT, source.getController(),
                List.of(new EffectEvent.Subject(recipient, 1)), triggerParameters);
    }
}
