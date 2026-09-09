package forge.ai.effect;

import forge.game.player.Player;

/** Public game-state context needed to value one consequence resolution. */
record OutcomeEvaluationContext(Player evaluatingAi, EffectEvent event, OutcomeState state) {
    OutcomeEvaluationContext(final Player evaluatingAi, final EffectEvent event) {
        this(evaluatingAi, event, null);
    }

    int unsupported() {
        if (state != null) { state.unsupported = true; }
        return 0;
    }

    long timestamp(final forge.game.Game game) {
        return game.getTimestamp() + (state == null ? 1 : ++state.timestampOffset);
    }
}
