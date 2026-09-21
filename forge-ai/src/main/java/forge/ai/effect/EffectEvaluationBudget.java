package forge.ai.effect;

/** Cooperative wall-clock budget shared by one bounded effect-analysis evaluation. */
final class EffectEvaluationBudget {
    private final long deadlineNanos;

    private EffectEvaluationBudget(final long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    static EffectEvaluationBudget fromTimeoutMillis(final int timeoutMillis) {
        final long timeoutNanos = Math.max(1L, timeoutMillis) * 1_000_000L;
        final long now = System.nanoTime();
        final long deadline = Long.MAX_VALUE - now < timeoutNanos
                ? Long.MAX_VALUE : now + timeoutNanos;
        return new EffectEvaluationBudget(deadline);
    }

    void check() {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadlineNanos) {
            throw new Exceeded();
        }
    }

    /** Raised internally so the caller can abandon the partial analysis safely. */
    static final class Exceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private Exceeded() {
            super(null, null, false, false);
        }
    }
}
