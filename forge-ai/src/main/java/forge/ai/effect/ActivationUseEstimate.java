package forge.ai.effect;

/** Expected near-term uses of one activated ability, separate from value per resolution. */
record ActivationUseEstimate(double expectedUses, double opportunityUses, int currentUses,
        int noLandUses, int withLandUses, int nextTurnUses, double nextLandProbability,
        double willingness, int outcomeValue,
        int averageCardPlayValue, boolean outcomeSupported, boolean supported, String reason) {
    static ActivationUseEstimate unsupported(final String reason) {
        return new ActivationUseEstimate(0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                false, false, reason);
    }
}
