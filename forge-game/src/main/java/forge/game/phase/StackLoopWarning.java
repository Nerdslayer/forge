package forge.game.phase;

/** Public information for a possible loop; does not expose hidden zones. */
public record StackLoopWarning(int stackSize, int resolutionsWithoutProgress, boolean resourcePressure) {
    public StackLoopWarning(final int stackSize, final int resolutionsWithoutProgress) {
        this(stackSize, resolutionsWithoutProgress, false);
    }
}
