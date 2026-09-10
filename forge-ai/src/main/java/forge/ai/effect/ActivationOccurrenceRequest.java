package forge.ai.effect;

/** Situational inputs needed to estimate current and near-future activation opportunities. */
record ActivationOccurrenceRequest(int currentMana, int nextTurnMana, int manaCost,
        boolean hasTapCost, boolean sourceTapped, boolean canPayNow,
        double nextLandProbability, double willingness, int outcomeValue,
        int averageCardPlayValue, boolean outcomeSupported, boolean supported, String reason) {
    static ActivationOccurrenceRequest unsupported(final String reason) {
        return new ActivationOccurrenceRequest(0, 0, 0, false, false, false,
                0, 0, 0, 0, false, false, reason);
    }
}
