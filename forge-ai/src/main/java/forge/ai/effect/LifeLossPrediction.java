package forge.ai.effect;

import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Conservative checks for replacements that can invalidate a predicted life-loss amount. */
final class LifeLossPrediction {
    private LifeLossPrediction() {
    }

    static boolean hasApplicableLifeReductionReplacement(
            final Player recipient, final int amount, final boolean isDamage) {
        final Map<AbilityKey, Object> runParams = AbilityKey.mapFromAffected(recipient);
        runParams.put(AbilityKey.Amount, amount);
        runParams.put(AbilityKey.IsDamage, isDamage);
        return hasApplicableReplacement(recipient, ReplacementType.LifeReduced, runParams);
    }

    static boolean hasApplicablePayLifeReplacement(final Player payer, final int amount,
            final SpellAbility cause) {
        final Map<AbilityKey, Object> runParams = AbilityKey.mapFromAffected(payer);
        runParams.put(AbilityKey.Amount, amount);
        runParams.put(AbilityKey.Cause, cause);
        runParams.put(AbilityKey.EffectOnly, false);
        return hasApplicableReplacement(payer, ReplacementType.PayLife, runParams);
    }

    private static boolean hasApplicableReplacement(final Player player,
            final ReplacementType type, final Map<AbilityKey, Object> runParams) {
        for (final Card card : player.getGame().getCardsIn(
                ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (final ReplacementEffect replacement : card.getReplacementEffects()) {
                if (replacement.getMode() != type || replacement.isSuppressed()) {
                    continue;
                }
                try {
                    if (replacement.zonesCheck(card.getZone())
                            && replacement.requirementsCheck(player.getGame())
                            && replacement.canReplace(runParams)) {
                        return true;
                    }
                } catch (final RuntimeException ignored) {
                    // An active replacement that cannot be predicted safely makes the resulting
                    // life-loss amount unknown, so fail closed.
                    return true;
                }
            }
        }
        return false;
    }
}
