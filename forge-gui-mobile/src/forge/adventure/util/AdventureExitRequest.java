package forge.adventure.util;

/** Holds one exit/replacement request until the user cancels, discards, or saves successfully. */
public final class AdventureExitRequest {
    private Runnable continuation;

    public boolean isPending() { return continuation != null; }

    public boolean begin(Runnable action) {
        if (isPending()) return false;
        continuation = action;
        return true;
    }

    public void cancel() { continuation = null; }

    public void proceed() {
        Runnable action = continuation;
        continuation = null;
        if (action != null) action.run();
    }

    public void saved(boolean success) {
        if (success) proceed();
    }
}
