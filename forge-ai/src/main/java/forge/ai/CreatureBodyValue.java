package forge.ai;

/**
 * Shared creature characteristic values for unified and Mastermind-only evaluation.
 * The first two points in each stat are worth the most; neither stat has a flat base value.
 */
public final class CreatureBodyValue {
    private CreatureBodyValue() {
    }

    public static int power(final int power) {
        return tieredValue(power, 30, 20, 15);
    }

    public static int toughness(final int toughness) {
        return tieredValue(toughness, 25, 15, 10);
    }

    public static int body(final int power, final int toughness) {
        return saturatedAdd(power(power), toughness(toughness));
    }

    public static int indestructible(final int power) {
        final long value = 60L + 10L * Math.max(0, power);
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static int tieredValue(final int size, final int first, final int middle,
            final int remaining) {
        final long positive = Math.max(0L, size);
        final long value = Math.min(positive, 2) * first
                + Math.min(Math.max(0L, positive - 2), 2) * middle
                + Math.max(0L, positive - 4) * remaining;
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static int saturatedAdd(final int left, final int right) {
        final long value = (long) left + right;
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
