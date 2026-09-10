package forge.ai.effect;

/** Shared expected-occurrence result with factor details for diagnostics and later intrinsic use. */
record AbilityOccurrenceEstimate(double expectedOccurrences, double opportunityOccurrences,
        double conditionLikelihood, double feasibilityLikelihood, double willingness,
        double survivalMultiplier, double horizonDiscount, int currentUses, int noLandUses,
        int withLandUses, int nextTurnUses, double nextLandProbability, boolean supported,
        String reason) {
    static AbilityOccurrenceEstimate unsupported(final String reason) {
        return new AbilityOccurrenceEstimate(0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, false, reason);
    }
}
