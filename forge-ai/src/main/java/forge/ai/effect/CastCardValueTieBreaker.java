package forge.ai.effect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import forge.ai.ComputerUtilAbility;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Applies known-card valuation only to exact legacy ties in spell selection. */
public final class CastCardValueTieBreaker {
    private CastCardValueTieBreaker() {
    }

    /**
     * Preserves the existing spell ordering except when all members of an exact legacy tie are
     * supported cards from the deciding AI's hand. Unsupported cards and abilities from other
     * zones remain in their original order.
     */
    public static void apply(final Player ai, final List<SpellAbility> abilities) {
        if (ai == null || abilities == null || abilities.size() < 2) {
            return;
        }

        final ValuationContext context = ValuationContext.forCast(ai, true);
        int start = 0;
        while (start < abilities.size()) {
            final SpellAbility first = abilities.get(start);
            int end = start + 1;
            while (end < abilities.size()
                    && ComputerUtilAbility.saEvaluator.compare(first, abilities.get(end)) == 0) {
                end++;
            }
            if (end - start > 1) {
                reorderSupportedTie(ai, abilities, start, end, context);
            }
            start = end;
        }
    }

    private static void reorderSupportedTie(final Player ai, final List<SpellAbility> abilities,
            final int start, final int end, final ValuationContext context) {
        final List<SpellAbility> tied = new ArrayList<>(abilities.subList(start, end));
        if (!tied.stream().allMatch(sa -> isSupportedCastCandidate(ai, sa))) {
            return;
        }

        final Map<Card, CardValueBreakdown> values = new IdentityHashMap<>();
        for (final SpellAbility sa : tied) {
            final Card card = sa.getHostCard();
            final CardValueBreakdown value = values.computeIfAbsent(card,
                    candidate -> UnifiedCardValueEvaluator.evaluateCard(candidate, context));
            if (!value.isComplete()) {
                return;
            }
        }

        final int firstValue = values.get(tied.get(0).getHostCard()).netValue();
        if (tied.stream().allMatch(sa -> values.get(sa.getHostCard()).netValue() == firstValue)) {
            return;
        }
        tied.sort(Comparator.comparingInt(
                (SpellAbility sa) -> values.get(sa.getHostCard()).netValue()).reversed());
        for (int i = 0; i < tied.size(); i++) {
            abilities.set(start + i, tied.get(i));
        }
    }

    private static boolean isSupportedCastCandidate(final Player ai, final SpellAbility sa) {
        if (sa == null || sa.getHostCard() == null || !sa.getHostCard().isInZone(ZoneType.Hand)) {
            return false;
        }
        final Card host = sa.getHostCard();
        return host.getOwner() == ai || host.getController() == ai;
    }
}
