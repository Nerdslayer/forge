package forge.ai.combat;

import java.util.function.LongSupplier;

/** One caller-owned allowance shared by search, projection, and future nested reply work. */
public final class CombatSearchBudget {
    private final int limit;
    private final long emergencyNanos;
    private final Usage usage;

    private static final class Usage {
        private final LongSupplier clock;
        private final long started;
        private int used;

        private Usage(final LongSupplier clock0) {
            clock = clock0;
            started = clock.getAsLong();
        }
    }

    public CombatSearchBudget(final int nodes) {
        this(nodes, 30_000);
    }

    public CombatSearchBudget(final int nodes, final long emergencyMillis) {
        this(nodes, emergencyMillis, System::nanoTime);
    }

    /** Injectable monotonic clock for deterministic allowance tests. */
    CombatSearchBudget(final int nodes, final long emergencyMillis, final LongSupplier clock) {
        if (nodes < 0 || emergencyMillis <= 0 || emergencyMillis > Long.MAX_VALUE / 1_000_000) {
            throw new IllegalArgumentException("Nonnegative nodes and a positive, bounded time allowance are required");
        }
        limit = nodes;
        emergencyNanos = emergencyMillis * 1_000_000;
        if (clock == null) { throw new IllegalArgumentException("A monotonic clock is required"); }
        usage = new Usage(clock);
    }

    private CombatSearchBudget(final int nodes, final long nanos, final Usage usage0) {
        limit = nodes;
        emergencyNanos = nanos;
        usage = usage0;
    }

    /** Shared accounting, not a fresh clock: leave 20% of both limits for live validation.
     * TODO: Size the reserve using measured snapshot/declaration costs on larger battlefields.
     * Uninterruptible engine helpers can still overrun either cooperative deadline. */
    public CombatSearchBudget planningAllowance() {
        final int reservedNodes = limit == 0 ? 0 : Math.max(1, limit / 5);
        return new CombatSearchBudget(limit - reservedNodes, emergencyNanos - Math.max(1, emergencyNanos / 5), usage);
    }

    public boolean tryConsume() {
        if (remaining() == 0) { return false; }
        usage.used++;
        return true;
    }

    public int used() { return usage.used; }

    public int remaining() {
        return usage.clock.getAsLong() - usage.started >= emergencyNanos ? 0 : Math.max(0, limit - usage.used);
    }

    public long elapsedMillis() { return (usage.clock.getAsLong() - usage.started) / 1_000_000; }
}
