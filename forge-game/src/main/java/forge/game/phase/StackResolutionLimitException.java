package forge.game.phase;

/** An execution safeguard was reached; this is not a Magic draw or player consent. */
public final class StackResolutionLimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StackResolutionLimitException(final String message) {
        super(message);
    }
}
