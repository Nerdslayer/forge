package forge.ai.combat;

/** A public source and its traversed script identity, not a card-name-specific heuristic. */
public record CombatAbilityKey(int sourceId, String path) {
    public CombatAbilityKey {
        if (path == null || path.isBlank()) { throw new IllegalArgumentException("An ability path is required"); }
    }
}
