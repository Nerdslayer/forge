package forge.ai.effect;

/** Environment-independent occurrence factors for a triggered or otherwise bounded ability. */
record AbilityOccurrenceRequest(double opportunityOccurrences, double conditionLikelihood,
        double feasibilityLikelihood, double willingness, double survivalMultiplier,
        double horizonDiscount, boolean supported, String reason) {
    static AbilityOccurrenceRequest unsupported(final String reason) {
        return new AbilityOccurrenceRequest(0, 0, 0, 0, 0, 0, false, reason);
    }
}
