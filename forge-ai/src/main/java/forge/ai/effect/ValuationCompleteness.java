package forge.ai.effect;

/** Coverage status for a shared card or action valuation. */
public enum ValuationCompleteness {
    COMPLETE,
    PARTIAL,
    UNAVAILABLE,
    UNSUPPORTED;

    /**
     * Combines component coverage while preserving the most restrictive status. An unavailable
     * component takes precedence over an unsupported one, matching the distinction used by the
     * existing removal transition adapter.
     */
    public static ValuationCompleteness combine(final ValuationCompleteness first,
            final ValuationCompleteness second) {
        if (first == null || second == null) {
            throw new IllegalArgumentException("Valuation completeness is required");
        }
        if (first == UNAVAILABLE || second == UNAVAILABLE) {
            return UNAVAILABLE;
        }
        if (first == UNSUPPORTED || second == UNSUPPORTED) {
            return UNSUPPORTED;
        }
        if (first == PARTIAL || second == PARTIAL) {
            return PARTIAL;
        }
        return COMPLETE;
    }
}
