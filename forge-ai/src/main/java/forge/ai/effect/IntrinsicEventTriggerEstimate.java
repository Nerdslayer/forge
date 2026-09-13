package forge.ai.effect;

import java.util.List;

/** Expected event-trigger opportunities with checkpoint-specific survival factors. */
public record IntrinsicEventTriggerEstimate(double expectedOccurrences,
        boolean supported, String reason, List<Opportunity> opportunities) {
    public IntrinsicEventTriggerEstimate {
        if (!Double.isFinite(expectedOccurrences) || expectedOccurrences < 0
                || reason == null || opportunities == null) {
            throw new IllegalArgumentException("Invalid event trigger estimate");
        }
        opportunities = List.copyOf(opportunities);
    }

    static IntrinsicEventTriggerEstimate unsupported(final String reason) {
        return new IntrinsicEventTriggerEstimate(0, false, reason, List.of());
    }

    public record Opportunity(SurvivalCheckpoint checkpoint, double eventRate,
            double survivalProbability, double horizonDiscount, double expectedContribution) {
        public Opportunity {
            if (checkpoint == null || !validRate(eventRate)
                    || !validProbability(survivalProbability) || !validProbability(horizonDiscount)
                    || !Double.isFinite(expectedContribution) || expectedContribution < 0) {
                throw new IllegalArgumentException("Invalid event opportunity");
            }
        }

        private static boolean validRate(final double value) {
            return Double.isFinite(value) && value >= 0;
        }

        private static boolean validProbability(final double value) {
            return Double.isFinite(value) && value >= 0 && value <= 1;
        }
    }
}
