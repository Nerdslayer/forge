package forge.ai;

import forge.game.player.Player;

/** Absolute work allowances for the resolved Mastermind profile. */
public enum MastermindEffort {
    LOW(1), MEDIUM(2), HIGH(4), MAX(8);

    private static volatile MastermindEffort configured = LOW;
    private final int multiplier;

    MastermindEffort(final int multiplier) {
        this.multiplier = multiplier;
    }

    public static MastermindEffort parse(final String value) {
        for (final var effort : values()) {
            if (effort.name().equalsIgnoreCase(value)) { return effort; }
        }
        return LOW;
    }

    public static void configure(final String value) {
        configured = parse(value);
    }

    public static MastermindEffort forPlayer(final Player player) {
        return player != null && player.getLobbyPlayer() instanceof LobbyPlayerAi ai
                && "Mastermind".equals(ai.getAiProfile()) ? configured : LOW;
    }

    public String localizedLabel() {
        return forge.util.Localizer.getInstance().getMessage("mastermindEffort" + name());
    }

    public static java.util.Map<String, String> localizedOptions() {
        final java.util.Map<String, String> options = new java.util.LinkedHashMap<>();
        for (final var effort : values()) { options.put(effort.localizedLabel(), effort.name()); }
        return options;
    }

    public int scale(final int base) {
        return (int) Math.min(Integer.MAX_VALUE, (long) base * multiplier);
    }
}
