package forge.ai.effect;

enum EffectType {
    // TODO(effect analysis): Add the remaining normalized event families documented in the
    // effect-analysis backlog; only a small subset is modeled today.
    TOKEN_CREATED,
    COUNTER_ADDED,
    LIFE_GAINED,
    CARD_DRAWN,
    DAMAGE_DEALT,
    ATTACKED_OR_BLOCKED,
    ZONE_CHANGED
}
