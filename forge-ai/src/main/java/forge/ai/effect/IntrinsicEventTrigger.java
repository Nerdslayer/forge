package forge.ai.effect;

/** Normalized event trigger used by intrinsic occurrence estimation. */
public record IntrinsicEventTrigger(IntrinsicReferenceModel.EventType eventType,
        TurnScope turnScope, boolean atMostOncePerTurn, double occurrenceMultiplier) {
    public enum TurnScope {
        ANY_TURN,
        CONTROLLER_TURN,
        OPPONENT_TURN
    }

    public IntrinsicEventTrigger {
        if (eventType == null || turnScope == null || !Double.isFinite(occurrenceMultiplier)
                || occurrenceMultiplier < 0) {
            throw new IllegalArgumentException("Invalid intrinsic event trigger");
        }
    }

    public IntrinsicEventTrigger(final IntrinsicReferenceModel.EventType eventType,
            final TurnScope turnScope, final boolean atMostOncePerTurn) {
        this(eventType, turnScope, atMostOncePerTurn, 1);
    }
}
