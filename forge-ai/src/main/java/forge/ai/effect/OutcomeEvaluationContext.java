package forge.ai.effect;

import forge.game.player.Player;

/** Public game-state context needed to value one consequence resolution. */
record OutcomeEvaluationContext(Player evaluatingAi, EffectEvent event) {
}
