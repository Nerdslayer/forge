package forge.ai.effect;

/** Relative turn boundaries used by bounded intrinsic survival estimates. */
public enum SurvivalCheckpoint {
    END_OF_FIRST_TURN(1, false),
    START_OF_SECOND_TURN(2, true),
    END_OF_SECOND_TURN(2, false),
    START_OF_THIRD_TURN(3, true),
    END_OF_THIRD_TURN(3, false),
    START_OF_FOURTH_TURN(4, true);

    private final int turnNumber;
    private final boolean turnStart;

    SurvivalCheckpoint(final int turnNumber, final boolean turnStart) {
        this.turnNumber = turnNumber;
        this.turnStart = turnStart;
    }

    public int turnNumber() {
        return turnNumber;
    }

    public boolean isTurnStart() {
        return turnStart;
    }

    public boolean isTurnEnd() {
        return !turnStart;
    }
}
