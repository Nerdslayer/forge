package forge.ai.effect;

/** Normalized scheduled trigger used by intrinsic occurrence estimation. */
public record IntrinsicScheduledTrigger(Schedule schedule, PlayerScope playerScope) {
    public enum Schedule {
        UPKEEP,
        END_STEP,
        TURN_BEGIN
    }

    public enum PlayerScope {
        CONTROLLER,
        OPPONENT,
        EACH_PLAYER
    }

    public IntrinsicScheduledTrigger {
        if (schedule == null || playerScope == null) {
            throw new IllegalArgumentException("Scheduled trigger fields are required");
        }
    }
}
