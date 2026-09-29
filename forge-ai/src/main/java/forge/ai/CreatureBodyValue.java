package forge.ai;

/**
 * Shared creature characteristic values for unified and Mastermind-only evaluation.
 * Combat values use the full generated P/T distribution; the smaller reference profiles used for
 * intrinsic outcome sampling do not define these thresholds.
 */
public final class CreatureBodyValue {
    private static final int CREATURE_EXISTENCE_VALUE = 10;
    private static final int BLOCKING_VALUE = 20;
    private static final int FLYING_BLOCK_VALUE = 8;
    private static final int POINTS_PER_POWER = 10;
    private static final int POINTS_PER_TOUGHNESS = 5;
    private static final int CREATURE_KILLING_VALUE = 40;
    private static final int COMBAT_SURVIVAL_VALUE = 40;
    private static final int DAMAGE_REMOVAL_SURVIVAL_VALUE = 20;

    private CreatureBodyValue() {
    }

    public static int base(final int toughness) {
        return toughness > 0 ? CREATURE_EXISTENCE_VALUE : 0;
    }

    /** Values potential damage to a player, which requires that the creature can attack. */
    public static int power(final int power) {
        return power(power, true);
    }

    public static int power(final int power, final boolean canAttack) {
        return canAttack ? saturatedMultiply(Math.max(0, power), POINTS_PER_POWER) : 0;
    }

    /** Gives each point of toughness a continuing value beyond the sampled combat thresholds. */
    public static int toughness(final int toughness) {
        return saturatedMultiply(Math.max(0, toughness), POINTS_PER_TOUGHNESS);
    }

    public static int creatureKilling(final int power, final boolean deathtouch,
            final boolean participatesInCombat) {
        if (power <= 0 || !participatesInCombat) {
            return 0;
        }
        if (deathtouch) {
            // TODO: Discount targets that are indestructible, protected, or otherwise ignore damage.
            return CREATURE_KILLING_VALUE;
        }
        return weighted(CREATURE_KILLING_VALUE,
                CreatureCombatDistribution.fractionWithToughnessAtMost(power));
    }

    public static int combatSurvival(final int toughness, final boolean participatesInCombat) {
        if (toughness <= 0 || !participatesInCombat) {
            return 0;
        }
        return weighted(COMBAT_SURVIVAL_VALUE,
                CreatureCombatDistribution.fractionWithPowerBelow(toughness));
    }

    public static int blocking(final boolean canBlock, final boolean flyingOrReach) {
        return canBlock ? BLOCKING_VALUE + (flyingOrReach ? FLYING_BLOCK_VALUE : 0) : 0;
    }

    public static int damageRemovalSurvival(final int toughness, final boolean ignoresDamage) {
        if (toughness <= 0 || ignoresDamage) {
            return 0;
        }
        // TODO: Replace the creature-power proxy with the distribution of actual damage-removal amounts.
        return weighted(DAMAGE_REMOVAL_SURVIVAL_VALUE,
                CreatureCombatDistribution.fractionWithDamageBelow(toughness));
    }

    /** Values a vanilla creature that can attack and block. */
    public static int body(final int power, final int toughness) {
        return body(power, toughness, true, true, false, false, false, false);
    }

    /**
     * Values a body and its direct combat traits. Other keyword values, such as evasion and trample,
     * remain the responsibility of the caller.
     */
    public static int body(final int power, final int toughness, final boolean canAttack,
            final boolean canBlock, final boolean flying, final boolean reach,
            final boolean deathtouch, final boolean indestructible) {
        if (toughness <= 0) {
            return 0;
        }
        final boolean participatesInCombat = canAttack || canBlock;
        int value = base(toughness);
        value = saturatedAdd(value, power(power, canAttack));
        value = saturatedAdd(value, toughness(toughness));
        value = saturatedAdd(value, creatureKilling(power, deathtouch, participatesInCombat));
        value = saturatedAdd(value, combatSurvival(toughness, participatesInCombat));
        value = saturatedAdd(value, blocking(canBlock, flying || reach));
        value = saturatedAdd(value, damageRemovalSurvival(toughness, indestructible));
        return value;
    }

    public static int indestructible(final int power) {
        final long value = 60L + 10L * Math.max(0, power);
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static int weighted(final int maximumValue, final double probability) {
        final double value = maximumValue * Math.max(0, Math.min(1, probability));
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.round(value);
    }

    private static int saturatedMultiply(final int value, final int multiplier) {
        final long result = (long) value * multiplier;
        return result >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static int saturatedAdd(final int left, final int right) {
        final long value = (long) left + right;
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
