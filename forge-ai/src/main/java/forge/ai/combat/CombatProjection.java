package forge.ai.combat;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Frozen short-combat result, not a stack simulation or a certificate of optimal play. */
public record CombatProjection(boolean supported, boolean available, List<String> reasons,
        Set<Integer> lostCreatures, Map<Integer, Survivor> survivors,
        Map<Integer, Integer> playerLifeAfter, List<DamageBatch> batches, Terminal terminal,
        List<CombatEventBatch> eventBatches, CombatOutcomeResolution outcomes) {
    public CombatProjection {
        reasons = List.copyOf(reasons);
        lostCreatures = Set.copyOf(lostCreatures);
        survivors = Map.copyOf(survivors);
        playerLifeAfter = Map.copyOf(playerLifeAfter);
        batches = List.copyOf(batches);
        eventBatches = List.copyOf(eventBatches);
        if (outcomes == null) { throw new IllegalArgumentException("Explicit outcome coverage required"); }
    }

    /** Synthetic continuations without concrete provenance must not invent combat events. */
    public CombatProjection(final boolean supported, final boolean available, final List<String> reasons,
            final Set<Integer> lostCreatures, final Map<Integer, Survivor> survivors,
            final Map<Integer, Integer> playerLifeAfter, final List<DamageBatch> batches, final Terminal terminal) {
        this(supported, available, reasons, lostCreatures, survivors, playerLifeAfter, batches, terminal,
                List.of(), CombatOutcomeResolution.empty());
    }

    public enum Terminal { NONE, WIN, LOSS, DRAW }
    public record Survivor(int markedDamage, boolean tapped, boolean markedDeathtouch, int shieldCounters) {
        public Survivor(final int markedDamage, final boolean tapped, final boolean markedDeathtouch) {
            this(markedDamage, tapped, markedDeathtouch, 0);
        }
    }
    public record DamageBatch(boolean firstStrike, Map<Integer, Integer> creatureDamage,
            Map<Integer, Integer> playerDamage, Map<Integer, Integer> lifeGained) {
        public DamageBatch {
            creatureDamage = Map.copyOf(creatureDamage);
            playerDamage = Map.copyOf(playerDamage);
            lifeGained = Map.copyOf(lifeGained);
        }
    }

    static CombatProjection rejected(final boolean supported, final List<String> reasons) {
        return new CombatProjection(supported, false, reasons, Set.of(), Map.of(), Map.of(), List.of(), Terminal.NONE);
    }
}
