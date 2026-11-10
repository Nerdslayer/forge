package forge.ai.effect;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import forge.game.GameActionUtil;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTap;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.zone.ZoneType;
import forge.ai.ComputerUtilMana;
import forge.game.replacement.ReplacementType;

/** Read-only impossibility checks, not a replacement for final targeting/payment checks. */
public final class EarlyActionAffordability {
    public enum Result { UNAVAILABLE, NEEDS_NORMAL_CHECK }

    private EarlyActionAffordability() { }

    public static Result assess(final Player ai, final SpellAbility ability) {
        return assess(ai, ability, () -> manaUpperBound(ai));
    }

    /** Check possibilities without scoring or choosing an optional cost. */
    public static boolean noPriorityActionBeforeOrdering(final Player ai, final List<SpellAbility> candidates,
            final boolean skipCounter) {
        final Supplier<OptionalLong> mana = Suppliers.memoize(() -> manaUpperBound(ai));
        for (final SpellAbility candidate : candidates) {
            if (couldPlayAnnouncement(ai, candidate, skipCounter, mana)) {
                return false;
            }
            for (final SpellAbility additional : GameActionUtil.getAdditionalCostSpell(candidate)) {
                if (couldPlayAnnouncement(ai, additional, skipCounter, mana)
                        || GameActionUtil.getAlternativeCosts(additional, ai, false).stream()
                                .anyMatch(alt -> couldPlayAnnouncement(ai, alt, skipCounter, mana))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean couldPlayAnnouncement(final Player ai, final SpellAbility candidate,
            final boolean skipCounter, final Supplier<OptionalLong> mana) {
        if (skipCounter && candidate.getApi() == ApiType.Counter) {
            return false;
        }
        return GameActionUtil.couldCastWithOptionalTiming(candidate, ai)
                && assess(ai, candidate, mana) != Result.UNAVAILABLE;
    }

    /** Candidates must already include the chooser's alternative and optional announcements. */
    public static boolean noPriorityAction(final Player ai, final List<SpellAbility> candidates,
            final boolean skipCounter) {
        final Supplier<OptionalLong> mana = Suppliers.memoize(() -> manaUpperBound(ai));
        return candidates.stream().allMatch(candidate ->
                (skipCounter && candidate.getApi() == ApiType.Counter) || !candidate.canCastTiming(ai)
                        || assess(ai, candidate, mana) == Result.UNAVAILABLE);
    }

    private static Result assess(final Player ai, final SpellAbility ability, final Supplier<OptionalLong> mana) {
        // TODO: Admit spells, target-dependent costs, colored feasibility and adjustments only
        // after their read-only admission semantics are established. Unknown is not unaffordable.
        if (ability == null || !ability.isActivatedAbility() || !ability.getHostCard().isInPlay()
                || ability.getHostCard().getController() != ai || ability.getPayCosts() == null
                || ability.isPwAbility() || !ability.getParamOrDefault("Cost", "").matches(
                        "(?:[0-9]+|[WUBRGC]|T|Sac<1/CARDNAME>)(?: (?:[0-9]+|[WUBRGC]|T|Sac<1/CARDNAME>))*")) {
            return Result.NEEDS_NORMAL_CHECK;
        }
        for (final CostPart part : ability.getPayCosts().getCostParts()) {
            if (part instanceof CostTap && !part.canPay(ability, ai, false)) {
                return Result.UNAVAILABLE;
            }
            if (!(part instanceof CostPartMana) && !(part instanceof CostTap)
                    && !(part instanceof CostSacrifice sacrifice && sacrifice.payCostFromSource()
                            && "1".equals(sacrifice.getAmount()))) {
                return Result.NEEDS_NORMAL_CHECK;
            }
        }
        if (!ability.getPayCosts().hasManaCost() || hasPossibleReduction(ai, ability)) {
            return Result.NEEDS_NORMAL_CHECK;
        }
        final OptionalLong capacity = mana.get();
        return capacity.isPresent() && ability.getPayCosts().getTotalMana().getCMC() > capacity.getAsLong()
                ? Result.UNAVAILABLE : Result.NEEDS_NORMAL_CHECK;
    }

    private static OptionalLong manaUpperBound(final Player ai) {
        if (hasUnmodeledMana(ai)
                || ai.getCardsActivatableInExternalZones(true).stream()
                        .anyMatch(card -> !card.getManaAbilities().isEmpty())) {
            return OptionalLong.empty();
        }
        final ActionDecisionSnapshot snapshot = ActionDecisionSnapshot.captureForAffordability(ai);
        if (!snapshot.manaResourcesComplete() || snapshot.hasResourceReservation()
                || snapshot.manaSources().stream().anyMatch(ActionManaSource::uncertain)) {
            return OptionalLong.empty();
        }
        // Count an upper bound, not the legacy available-mana estimate. Different activations
        // of one tap source are alternatives; floating mana units are independent resources.
        // Ignoring colors and the activation's own tap/sac cost can only overestimate capacity.
        final Map<Card, Integer> capacities = new IdentityHashMap<>();
        long upperBound = 0;
        for (final ActionManaSource source : snapshot.manaSources()) {
            if (source.isFloating()) {
                upperBound += source.outputMasks().size();
            } else {
                capacities.merge(source.source(), source.outputMasks().size(), Math::max);
            }
        }
        for (final int capacity : capacities.values()) {
            upperBound += capacity;
        }
        return OptionalLong.of(upperBound);
    }

    private static boolean hasPossibleReduction(final Player ai, final SpellAbility ability) {
        if (Set.of("ReduceCost", "SetCost", "RaiseCost", "TapCreaturesForMana").stream()
                .anyMatch(ability::hasParam) || ability.getMaxWaterbend() != null) {
            return true;
        }
        for (final ZoneType zone : Set.of(ZoneType.Battlefield, ZoneType.Command, ZoneType.Stack)) {
            for (final Card card : ai.getGame().getCardsIn(zone)) {
                if (card.getStaticAbilities().stream().anyMatch(s -> s.checkMode(StaticAbilityMode.ReduceCost)
                        || s.checkMode(StaticAbilityMode.SetCost))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasUnmodeledMana(final Player ai) {
        for (final Card card : ai.getCardsIn(ZoneType.Battlefield)) {
            if (ComputerUtilMana.getAIPlayableMana(card).size() != card.getManaAbilities().size()
                    || card.getManaAbilities().stream().anyMatch(a -> !"1".equals(a.getParamOrDefault("Amount", "1")))) {
                return true;
            }
        }
        for (final ZoneType zone : Set.of(ZoneType.Battlefield, ZoneType.Command)) {
            for (final Card card : ai.getGame().getCardsIn(zone)) {
                if (card.getReplacementEffects().stream().anyMatch(r -> r.getMode() == ReplacementType.ProduceMana)
                        || card.getTriggers().stream().anyMatch(t -> Set.of("TapsForMana", "ManaAdded")
                                .contains(t.getMode().name()))) {
                    return true;
                }
            }
        }
        return false;
    }
}
