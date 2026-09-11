package forge.ai.effect;

/** Approximate point in the turn at which a permanent enters the battlefield. */
public enum EntryTiming {
    /** A normal-speed permanent entering during the controller's current turn. */
    NORMAL_SPEED(true, 1.0),
    /** A permanent entering late in the opponent's turn, such as a flash permanent. */
    FLASH_LATE_TURN(false, .25);

    private final boolean firstTurnIsControllerTurn;
    private final double firstTurnExposure;

    EntryTiming(final boolean firstTurnIsControllerTurn, final double firstTurnExposure) {
        this.firstTurnIsControllerTurn = firstTurnIsControllerTurn;
        this.firstTurnExposure = firstTurnExposure;
    }

    boolean firstTurnIsControllerTurn() {
        return firstTurnIsControllerTurn;
    }

    double firstTurnExposure() {
        return firstTurnExposure;
    }
}
