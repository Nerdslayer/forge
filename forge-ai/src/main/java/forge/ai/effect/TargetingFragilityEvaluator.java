package forge.ai.effect;

import java.util.List;
import java.util.Set;

import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.game.card.Card;
import forge.game.player.Player;

/** One lifetime-usefulness discount, not a refund for a hypothetical future casualty. */
public final class TargetingFragilityEvaluator {
    public static final int DEFAULT_DISCOUNT_PERCENT = 30;
    private static final Set<String> TRIGGER_PARAMETERS = Set.of("Mode", "ValidTarget", "Execute",
            "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> OUTCOME_PARAMETERS = Set.of("DB", "SacValid", "Amount", "Defined");

    private TargetingFragilityEvaluator() { }

    public static boolean isTargetingSacrifice(final CardAbilityTraversal.AbilityDescription ability) {
        if (ability == null || ability.origin() != CardAbilityTraversal.Origin.TRIGGER
                || !TRIGGER_PARAMETERS.containsAll(ability.parameters().keySet())) { return false; }
        final var parameters = ability.parameters();
        final var outcome = ability.outcome();
        // TODO: Source-restricted, optional/conditional, destroy-instead-of-sacrifice and broader
        // target filters need separate vulnerability policies; do not give them this flat discount.
        return "BecomesTarget".equals(parameters.get("Mode"))
                && Set.of("Card.Self", "Creature.Self", "Permanent.Self").contains(parameters.getOrDefault("ValidTarget", ""))
                && "Battlefield".equalsIgnoreCase(parameters.getOrDefault("TriggerZones", "Battlefield"))
                && outcome != null && outcome.issue().isEmpty() && "Sacrifice".equals(outcome.api())
                && outcome.next() == null && outcome.choices().isEmpty()
                && OUTCOME_PARAMETERS.containsAll(outcome.parameters().keySet())
                && "Self".equals(outcome.parameters().getOrDefault("SacValid", "Self"))
                && "Self".equals(outcome.parameters().getOrDefault("Defined", "Self"))
                && "1".equals(outcome.parameters().getOrDefault("Amount", "1"));
    }

    public static boolean hasTargetingSacrifice(final List<CardAbilityTraversal.AbilityDescription> abilities) {
        return abilities.stream().anyMatch(TargetingFragilityEvaluator::isTargetingSacrifice);
    }

    static int discountPercent(final Player ai, final Card card) {
        final int configured = Math.max(0, Math.min(100,
                AiProfileUtil.getIntProperty(ai, AiProps.TARGETING_FRAGILITY_DISCOUNT_PERCENT)));
        if (configured == 0 || card.isFaceDown() || card.getTriggers().isEmpty()) { return 0; }
        return hasTargetingSacrifice(CardAbilityTraversal.inspectLive(ai, card, EffectAnalysisTrace.disabled()))
                ? configured : 0;
    }

    /** Reduce useful value only; never make independent drawbacks less negative. */
    public static int discountBenefit(final int value, final int discountPercent) {
        if (discountPercent < 0 || discountPercent > 100) { throw new IllegalArgumentException("Invalid discount"); }
        return value > 0 ? EffectMath.scalePercent(value, 100 - discountPercent) : value;
    }
}
