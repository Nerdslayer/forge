package forge.ai.effect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Shared interpretation of scheduled script fields; never checks current game conditions. */
public final class ScheduledTriggerParser {
    private static final Set<String> PARAMETERS = Set.of("Mode", "Phase", "ValidPlayer",
            "Execute", "TriggerZones", "TriggerDescription", "Secondary");

    private ScheduledTriggerParser() { }

    public record Schedule(Timing timing, PlayerScope playerScope) { }
    public enum Timing { UPKEEP, END_STEP, TURN_BEGIN }
    public enum PlayerScope { CONTROLLER, OPPONENT, EACH_PLAYER }

    public static Optional<Schedule> parse(final Map<String, String> parameters) {
        // TODO: Conditional, delayed, limited and additional phase forms need explicit descriptors.
        if (!PARAMETERS.containsAll(parameters.keySet())) { return Optional.empty(); }
        final Timing timing;
        if ("TurnBegin".equals(parameters.get("Mode"))) {
            timing = Timing.TURN_BEGIN;
        } else if ("Phase".equals(parameters.get("Mode"))) {
            final String phase = parameters.getOrDefault("Phase", "");
            if ("Upkeep".equalsIgnoreCase(phase)) { timing = Timing.UPKEEP; }
            else if ("End of Turn".equalsIgnoreCase(phase)) { timing = Timing.END_STEP; }
            else { return Optional.empty(); }
        } else { return Optional.empty(); }
        final String player = parameters.getOrDefault("ValidPlayer", "");
        final PlayerScope scope;
        if (player.isBlank() || "Any".equalsIgnoreCase(player) || "Each".equalsIgnoreCase(player)) {
            scope = PlayerScope.EACH_PLAYER;
        } else if ("You".equalsIgnoreCase(player) || "Controller".equalsIgnoreCase(player)) {
            scope = PlayerScope.CONTROLLER;
        } else if ("Opponent".equalsIgnoreCase(player)) {
            scope = PlayerScope.OPPONENT;
        } else { return Optional.empty(); }
        return Optional.of(new Schedule(timing, scope));
    }
}
