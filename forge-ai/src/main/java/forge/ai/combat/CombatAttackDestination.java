package forge.ai.combat;

/** Typed public destination. The legacy scalar maps use disjoint, checked key namespaces. */
public record CombatAttackDestination(Kind kind, int id) {
    public enum Kind { PLAYER, PLANESWALKER }

    public CombatAttackDestination {
        if (kind == null || id < 0) { throw new IllegalArgumentException("Nonnegative public destination ID required"); }
    }

    public int key() { return kind == Kind.PLAYER ? id : ~id; }

    public static CombatAttackDestination fromKey(final int key) {
        return new CombatAttackDestination(key >= 0 ? Kind.PLAYER : Kind.PLANESWALKER, key >= 0 ? key : ~key);
    }
}
