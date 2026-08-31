package forge.ai.effect;

/** One consequence match and the number of times its outcome is expected to resolve. */
record EffectMatch(EffectEvent event, int resolutions) {
    EffectMatch {
        if (event == null || resolutions <= 0) {
            throw new IllegalArgumentException("An effect match needs a positive resolution count");
        }
    }
}
