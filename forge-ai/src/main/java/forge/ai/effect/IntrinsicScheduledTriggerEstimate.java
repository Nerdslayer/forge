package forge.ai.effect;

import java.util.List;

/** Expected scheduled-trigger opportunities with their checkpoint-specific survival factors. */
public record IntrinsicScheduledTriggerEstimate(double expectedOccurrences,
        int opportunitiesEvaluated, List<Opportunity> opportunities,
        PermanentSurvivalEstimate survival) {
    public IntrinsicScheduledTriggerEstimate {
        if (!Double.isFinite(expectedOccurrences) || expectedOccurrences < 0
                || opportunitiesEvaluated < 0 || opportunities == null || survival == null) {
            throw new IllegalArgumentException("Invalid scheduled trigger estimate");
        }
        opportunities = List.copyOf(opportunities);
    }

    public record Opportunity(SurvivalCheckpoint checkpoint, double survivalProbability,
            double horizonDiscount, double expectedContribution) {
        public Opportunity {
            if (checkpoint == null || !validProbability(survivalProbability)
                    || !validProbability(horizonDiscount) || !Double.isFinite(expectedContribution)
                    || expectedContribution < 0) {
                throw new IllegalArgumentException("Invalid scheduled opportunity");
            }
        }

        private static boolean validProbability(final double value) {
            return Double.isFinite(value) && value >= 0 && value <= 1;
        }
    }
}
