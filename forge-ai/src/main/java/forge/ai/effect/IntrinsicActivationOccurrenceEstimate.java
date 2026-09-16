package forge.ai.effect;

/** Bounded intrinsic-use estimate for simple mana and tap activated abilities. */
public record IntrinsicActivationOccurrenceEstimate(double expectedOccurrences,
        double currentTurnUses, double expectedUsesPerFutureTurn,
        int futureTurnsEvaluated, boolean supported, String reason) {
    public IntrinsicActivationOccurrenceEstimate {
        if (!Double.isFinite(expectedOccurrences) || expectedOccurrences < 0
                || !Double.isFinite(currentTurnUses) || currentTurnUses < 0
                || !Double.isFinite(expectedUsesPerFutureTurn) || expectedUsesPerFutureTurn < 0
                || futureTurnsEvaluated < 0 || reason == null) {
            throw new IllegalArgumentException("Invalid intrinsic activation estimate");
        }
    }

    static IntrinsicActivationOccurrenceEstimate unsupported(final String reason) {
        return new IntrinsicActivationOccurrenceEstimate(0, 0, 0, 0, false, reason);
    }
}
