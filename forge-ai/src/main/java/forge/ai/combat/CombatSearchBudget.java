package forge.ai.combat;

/** One caller-owned allowance shared by search, projection, and future nested reply work. */
public final class CombatSearchBudget {
    private final int limit;
    private final long started = System.nanoTime();
    private final long emergencyNanos;
    private int used;

    public CombatSearchBudget(final int nodes) {
        this(nodes, 30_000);
    }

    public CombatSearchBudget(final int nodes, final long emergencyMillis) {
        if (nodes < 0 || emergencyMillis <= 0 || emergencyMillis > Long.MAX_VALUE / 1_000_000) {
            throw new IllegalArgumentException("Nonnegative nodes and a positive, bounded time allowance are required");
        }
        limit = nodes;
        emergencyNanos = emergencyMillis * 1_000_000;
    }

    public boolean tryConsume() {
        if (remaining() == 0) { return false; }
        used++;
        return true;
    }

    public int used() { return used; }

    public int remaining() {
        return System.nanoTime() - started >= emergencyNanos ? 0 : limit - used;
    }

    public long elapsedMillis() { return (System.nanoTime() - started) / 1_000_000; }
}
