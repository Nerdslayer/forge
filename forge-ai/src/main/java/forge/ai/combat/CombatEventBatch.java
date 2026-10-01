package forge.ai.combat;

import java.util.List;
import java.util.Map;

/** Public, game-free event provenance for first-order outcome binding, not a trigger stack. */
public record CombatEventBatch(Stage stage, Map<Integer, CardState> battlefieldBefore,
        Map<Integer, Integer> playerLifeBefore, List<Event> events) {
    public CombatEventBatch {
        battlefieldBefore = Map.copyOf(battlefieldBefore);
        playerLifeBefore = Map.copyOf(playerLifeBefore);
        events = List.copyOf(events);
    }

    public enum Stage {
        ATTACK_DECLARATION, BLOCK_DECLARATION,
        FIRST_STRIKE_DAMAGE, FIRST_STRIKE_DEATHS, REGULAR_DAMAGE, REGULAR_DEATHS
    }

    /** Death batches retain every simultaneous casualty until the entire batch leaves play. */
    public record CardState(PublicCombatSnapshot.Creature characteristics, int markedDamage,
            boolean markedDeathtouch, boolean tapped) { }

    public sealed interface Event permits Attacks, Taps, Blocks, BecomesBlocked, Damage, LifeGain, LifeLoss, Dies, CountersRemoved { }
    public record Attacks(int cardId, int defenderId) implements Event { }
    public record Taps(int cardId) implements Event { }
    public record Blocks(int blockerId, int attackerId) implements Event { }
    public record BecomesBlocked(int attackerId, List<Integer> blockerIds) implements Event {
        public BecomesBlocked { blockerIds = List.copyOf(blockerIds); }
    }
    public enum Recipient { CREATURE, PLAYER }
    public record Damage(int sourceId, int recipientId, Recipient recipient, int amount) implements Event { }
    /** One lifelink gain per damage source, including damage to several recipients. */
    public record LifeGain(int sourceId, int playerId, int amount) implements Event { }
    public record LifeLoss(int playerId, int amount) implements Event { }
    public record Dies(int cardId) implements Event { }
    public record CountersRemoved(int cardId, String type, int amount) implements Event { }

    // Fixed self-attack/block, self combat-player-damage and self-death draws use replacement and
    // observed-declaration exclusions. TODO: Shared match tables and broader trigger resolution,
    // replacements, noncreature LKI and recursive outcomes. Other triggers remain unsupported.
}
