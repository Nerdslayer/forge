package forge.ai.effect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import org.tinylog.Logger;

import forge.ai.ComputerUtilAbility;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/** Shared stable exact-tie handling for action-specific valuation adapters. */
public final class ActionValueTieBreaker {
    private ActionValueTieBreaker() {
    }

    /**
     * Ranks a pre-grouped tie using a supported action adapter. The original list is returned when
     * a candidate is unavailable, unsupported, incomplete, or has the same value as every other
     * candidate; callers therefore retain their legacy fallback in all of those cases.
     */
    public static <T> List<T> rankSupportedTie(final List<T> candidates,
            final ValuationContext context, final Predicate<T> supportedCandidate,
            final Function<T, ValuationAction> actionFactory) {
        if (candidates == null || candidates.size() < 2 || context == null
                || supportedCandidate == null || actionFactory == null
                || !candidates.stream().allMatch(supportedCandidate)) {
            return candidates;
        }

        final Map<T, CardValueBreakdown> values = new IdentityHashMap<>();
        for (final T candidate : candidates) {
            final CardValueBreakdown value = UnifiedActionValueEvaluator.evaluate(
                    actionFactory.apply(candidate), context);
            if (!value.isComplete()) {
                return candidates;
            }
            values.put(candidate, value);
        }

        final int firstValue = values.get(candidates.get(0)).netValue();
        if (candidates.stream().allMatch(candidate -> values.get(candidate).netValue() == firstValue)) {
            return candidates;
        }
        final List<T> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator.comparingInt(
                (T candidate) -> values.get(candidate).netValue()).reversed());
        return ranked;
    }

    static void apply(final Player ai, final List<SpellAbility> abilities,
            final ValuationContext context, final Predicate<SpellAbility> supportedCandidate,
            final Function<SpellAbility, ValuationAction> actionFactory) {
        if (ai == null || abilities == null || abilities.size() < 2) {
            return;
        }

        final Map<SpellAbility, CardValueBreakdown> values = new IdentityHashMap<>();
        int start = 0;
        while (start < abilities.size()) {
            final SpellAbility first = abilities.get(start);
            int end = start + 1;
            while (end < abilities.size()
                    && ComputerUtilAbility.saEvaluator.compare(first, abilities.get(end)) == 0) {
                end++;
            }
            if (end - start > 1) {
                reorderSupportedTie(ai, abilities, start, end, context,
                        supportedCandidate, actionFactory, values);
            }
            start = end;
        }
    }

    private static void reorderSupportedTie(final Player ai, final List<SpellAbility> abilities,
            final int start, final int end, final ValuationContext context,
            final Predicate<SpellAbility> supportedCandidate,
            final Function<SpellAbility, ValuationAction> actionFactory,
            final Map<SpellAbility, CardValueBreakdown> values) {
        final List<SpellAbility> tied = new ArrayList<>(abilities.subList(start, end));
        if (!tied.stream().allMatch(supportedCandidate)) {
            return;
        }

        for (final SpellAbility ability : tied) {
            final CardValueBreakdown value = values.computeIfAbsent(ability,
                    candidate -> UnifiedActionValueEvaluator.evaluate(
                            actionFactory.apply(candidate), context));
            if (!value.isComplete()) {
                return;
            }
        }

        final int firstValue = values.get(tied.get(0)).netValue();
        if (tied.stream().allMatch(ability -> values.get(ability).netValue() == firstValue)) {
            return;
        }
        tied.sort(Comparator.comparingInt(
                (SpellAbility ability) -> values.get(ability).netValue()).reversed());
        for (int i = 0; i < tied.size(); i++) {
            abilities.set(start + i, tied.get(i));
        }
        logReordering(context, tied, values);
    }

    private static void logReordering(final ValuationContext context,
            final List<SpellAbility> ordered, final Map<SpellAbility, CardValueBreakdown> values) {
        if (!Boolean.parseBoolean(System.getProperty(EffectAnalysisTrace.ENABLE_PROPERTY, "true"))) {
            return;
        }
        final StringBuilder details = new StringBuilder("[AI Effect Analysis] Action tie-break: ")
                .append("decision=").append(context.decision()).append(", ordered=");
        for (int i = 0; i < ordered.size(); i++) {
            if (i > 0) {
                details.append(", ");
            }
            final SpellAbility ability = ordered.get(i);
            details.append(ability.getHostCard().getName()).append("=")
                    .append(values.get(ability).netValue());
        }
        Logger.info(details.toString());
    }
}
