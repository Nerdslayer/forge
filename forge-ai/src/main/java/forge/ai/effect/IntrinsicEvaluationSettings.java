package forge.ai.effect;

/** Bounded, explainable settings for standalone intrinsic ability evaluation. */
public record IntrinsicEvaluationSettings(int maximumExpectedOccurrencesPerAbility,
        int recurringTriggerResolutions, int maximumActivationUsesPerTurn,
        int maximumTraversalDepth, int maximumInteractionDepth, int maximumInteractionPaths,
        int maximumOutcomeSearchBudget, double minimumSurvivalMultiplier,
        double maximumSurvivalMultiplier, int lethalBaseValue, int lethalValuePerLife,
        int lethalValueCap) {
    public IntrinsicEvaluationSettings {
        if (maximumExpectedOccurrencesPerAbility <= 0 || recurringTriggerResolutions <= 0
                || maximumActivationUsesPerTurn <= 0 || maximumTraversalDepth <= 0
                || maximumInteractionDepth <= 0 || maximumInteractionPaths <= 0
                || maximumOutcomeSearchBudget <= 0) {
            throw new IllegalArgumentException("Intrinsic evaluation bounds must be positive");
        }
        if (!Double.isFinite(minimumSurvivalMultiplier)
                || !Double.isFinite(maximumSurvivalMultiplier)
                || minimumSurvivalMultiplier < 0
                || maximumSurvivalMultiplier < minimumSurvivalMultiplier) {
            throw new IllegalArgumentException("Invalid survival multiplier bounds");
        }
        if (lethalBaseValue < 0 || lethalValuePerLife < 0 || lethalValueCap <= 0) {
            throw new IllegalArgumentException("Invalid lethal-value settings");
        }
    }

    public static IntrinsicEvaluationSettings defaults() {
        return new IntrinsicEvaluationSettings(8, 2, 4, 24, 4, 32, 4096,
                0.75, 1.50, 200, 60, 500);
    }

    /** Returns the total intrinsic value of a lethal outcome at the given starting life. */
    public int intrinsicLethalValue(final int startingLife) {
        final long value = (long) lethalBaseValue + (long) lethalValuePerLife * Math.max(0, startingLife);
        return (int) Math.min(lethalValueCap, value);
    }
}
