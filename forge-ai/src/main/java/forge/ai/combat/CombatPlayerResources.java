package forge.ai.combat;

/** Public counts only; never retains identities from a player's hand or library. */
public record CombatPlayerResources(int handSize, int librarySize) {
    public CombatPlayerResources {
        if (handSize < 0 || librarySize < 0) { throw new IllegalArgumentException("Nonnegative resource counts required"); }
    }
}
