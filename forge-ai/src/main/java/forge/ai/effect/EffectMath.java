package forge.ai.effect;

/** Overflow-safe arithmetic shared by effect analysis. */
final class EffectMath {
    private EffectMath() {
    }

    static int add(final int left, final int right) {
        final long result = (long) left + right;
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE
                : result < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) result;
    }

    static int subtract(final int left, final int right) {
        final long result = (long) left - right;
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE
                : result < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) result;
    }

    static int multiply(final int left, final int right) {
        final long result = (long) left * right;
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE
                : result < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) result;
    }

    static int negate(final int value) {
        return value == Integer.MIN_VALUE ? Integer.MAX_VALUE : -value;
    }
}
