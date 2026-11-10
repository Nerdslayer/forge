package forge.ai.effect;

import forge.ai.MastermindEffort;
import forge.util.AITimeoutTracker;
import java.util.function.LongSupplier;

/** Cooperative wall-clock budget shared by one bounded effect-analysis evaluation. */
final class EffectEvaluationBudget {
    private final long deadlineNanos;

    private final MastermindEffort effort;
    private final int timeoutMillis;
    private final LongSupplier clock;

    private EffectEvaluationBudget(final long deadlineNanos, final MastermindEffort effort, final int timeoutMillis,
            final LongSupplier clock) {
        this.clock = clock;
        this.timeoutMillis = timeoutMillis;
        this.effort = effort;
        this.deadlineNanos = deadlineNanos;
    }

    static EffectEvaluationBudget fromTimeoutMillis(final int timeoutMillis) {
        return fromTimeoutMillis(timeoutMillis, MastermindEffort.LOW);
    }

    static EffectEvaluationBudget fromTimeoutMillis(final int timeoutMillis, final MastermindEffort effort) {
        return fromTimeoutMillis(timeoutMillis, effort, System::nanoTime);
    }

    static EffectEvaluationBudget fromTimeoutMillis(final int timeoutMillis, final MastermindEffort effort, final LongSupplier clock) {
        final int effectiveMillis = effort.scale(Math.max(1, timeoutMillis));
        final long timeoutNanos = effectiveMillis * 1_000_000L;
        final long now = clock.getAsLong();
        final long deadline = Long.MAX_VALUE - now < timeoutNanos
                ? Long.MAX_VALUE : now + timeoutNanos;
        return new EffectEvaluationBudget(deadline, effort, effectiveMillis, clock);
    }

    int scale(final int base) { return effort.scale(base); }

    String limits() { return "effort=" + effort + ", timeoutMs=" + timeoutMillis; }

    void check() {
        if (Thread.currentThread().isInterrupted() || clock.getAsLong() >= deadlineNanos
                || AITimeoutTracker.remainingNanos() <= 0) {
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
