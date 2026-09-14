package forge.ai.effect;

/** Relative turn boundaries used by bounded intrinsic survival estimates. */
public enum SurvivalCheckpoint {
    END_OF_FIRST_TURN(1, false),
    START_OF_SECOND_TURN(2, true),
    END_OF_SECOND_TURN(2, false),
    START_OF_THIRD_TURN(3, true),
    END_OF_THIRD_TURN(3, false),
    START_OF_FOURTH_TURN(4, true),
    END_OF_FOURTH_TURN(4, false),
    START_OF_FIFTH_TURN(5, true),
    END_OF_FIFTH_TURN(5, false),
    START_OF_SIXTH_TURN(6, true),
    END_OF_SIXTH_TURN(6, false);

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

    /** Returns the controller's turn number represented by this checkpoint. */
    public int controllerTurnNumber(final EntryTiming entryTiming) {
        if (entryTiming == null) {
            throw new IllegalArgumentException("Entry timing is required");
        }
        return entryTiming.firstTurnIsControllerTurn() ? (turnNumber + 1) / 2 : turnNumber / 2;
    }

    /** Returns the opponent's turn number represented by this checkpoint. */
    public int opponentTurnNumber(final EntryTiming entryTiming) {
        if (entryTiming == null) {
            throw new IllegalArgumentException("Entry timing is required");
        }
        return entryTiming.firstTurnIsControllerTurn() ? turnNumber / 2 : (turnNumber + 1) / 2;
    }
}
