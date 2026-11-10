package forge.ai.effect;

/** A live preview could not restore the board; continuing priority is unsafe. */
public final class LiveGameRestorationException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    LiveGameRestorationException(final RuntimeException cause) {
        super("Could not restore live game after attachment analysis", cause);
    }
}
