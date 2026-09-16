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

/** Applies shared activation-action valuation only to exact legacy ties. */
public final class ActivateAbilityValueTieBreaker {
    private ActivateAbilityValueTieBreaker() {
    }

    /**
     * Preserves the existing activation ordering except when all members of an exact legacy tie
     * are currently payable abilities from permanents controlled by the deciding AI.
     */
    public static void apply(final Player ai, final List<SpellAbility> abilities) {
        if (ai == null || abilities == null || abilities.size() < 2) {
            return;
        }

        final ValuationContext context = ValuationContext.forActivation(ai, true);
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
                reorderSupportedTie(ai, abilities, start, end, context, values);
            }
            start = end;
        }
    }

    private static void reorderSupportedTie(final Player ai, final List<SpellAbility> abilities,
            final int start, final int end, final ValuationContext context,
            final Map<SpellAbility, CardValueBreakdown> values) {
        final List<SpellAbility> tied = new ArrayList<>(abilities.subList(start, end));
        if (!tied.stream().allMatch(sa -> isSupportedActivationCandidate(ai, sa))) {
            return;
        }

        for (final SpellAbility sa : tied) {
            final CardValueBreakdown value = values.computeIfAbsent(sa,
                    candidate -> UnifiedActionValueEvaluator.evaluate(
                            new ActivateValuationAction(candidate.getHostCard(), candidate), context));
            if (!value.isComplete()) {
                return;
            }
        }

        final int firstValue = values.get(tied.get(0)).netValue();
        if (tied.stream().allMatch(sa -> values.get(sa).netValue() == firstValue)) {
            return;
        }
        tied.sort(Comparator.comparingInt(
                (SpellAbility sa) -> values.get(sa).netValue()).reversed());
        for (int i = 0; i < tied.size(); i++) {
            abilities.set(start + i, tied.get(i));
        }
    }

    private static boolean isSupportedActivationCandidate(final Player ai,
            final SpellAbility sa) {
        if (sa == null || !sa.isActivatedAbility() || sa.getHostCard() == null) {
            return false;
        }
        final Card host = sa.getHostCard();
        return host.isInZone(ZoneType.Battlefield) && host.getController() == ai;
    }
}
