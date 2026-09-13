package forge.ai.effect;

import forge.game.card.Card;

/** One explainable, signed contribution to a card's removal value. */
record AbilityValueContribution(Card candidate, Card source, AbilityIdentity sourceAbility,
        Card relatedSource, AbilityIdentity relatedAbility, AbilityValueKind kind, int value,
        String opportunityKey, boolean counted, AbilityValueCompleteness completeness,
        String reason) {
    static AbilityValueContribution counted(final Card candidate, final Card source,
            final AbilityIdentity sourceAbility, final Card relatedSource,
            final AbilityIdentity relatedAbility, final AbilityValueKind kind, final int value,
            final String opportunityKey, final String reason) {
        return new AbilityValueContribution(candidate, source, sourceAbility, relatedSource,
                relatedAbility, kind, value, opportunityKey, true,
                kind == AbilityValueKind.KNOWN_RELATIONSHIP
                        || kind == AbilityValueKind.CURRENT_STATIC
                        ? AbilityValueCompleteness.SUPPORTED_SUBTOTAL
                        : AbilityValueCompleteness.COMPLETE,
                reason);
    }

    static AbilityValueContribution skipped(final Card candidate, final Card source,
            final AbilityIdentity sourceAbility, final AbilityValueKind kind,
            final String opportunityKey, final String reason) {
        return new AbilityValueContribution(candidate, source, sourceAbility, null, null, kind, 0,
                opportunityKey, false, AbilityValueCompleteness.UNSUPPORTED, reason);
    }

    static AbilityValueContribution duplicate(final Card candidate, final AbilityIdentity ability,
            final String reason) {
        return new AbilityValueContribution(candidate, candidate, ability, null, null,
                AbilityValueKind.SKIPPED, 0, ability.path(), false,
                AbilityValueCompleteness.COMPLETE, reason);
    }
}
