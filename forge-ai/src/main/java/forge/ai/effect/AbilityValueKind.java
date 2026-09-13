package forge.ai.effect;

/** Kind of value attributed to a removal candidate. */
enum AbilityValueKind {
    CURRENT_STATIC,
    KNOWN_RELATIONSHIP,
    INTRINSIC_SCHEDULED,
    INTRINSIC_SELF_OPPORTUNITY,
    INTRINSIC_FUTURE_ALLOWANCE,
    SKIPPED
}
