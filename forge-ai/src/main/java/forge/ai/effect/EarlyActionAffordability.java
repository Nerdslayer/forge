package forge.ai.effect;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

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
        if (!ability.getPayCosts().hasManaCost() || hasPossibleReduction(ai, ability)
                || hasUnmodeledMana(ai)
                || ai.getCardsActivatableInExternalZones(true).stream()
                        .anyMatch(card -> !card.getManaAbilities().isEmpty())) {
            return Result.NEEDS_NORMAL_CHECK;
        }
        final ActionDecisionSnapshot snapshot = ActionDecisionSnapshot.captureForAffordability(ai);
        if (!snapshot.manaResourcesComplete() || snapshot.hasResourceReservation()
                || snapshot.manaSources().stream().anyMatch(ActionManaSource::uncertain)) {
            return Result.NEEDS_NORMAL_CHECK;
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
        return ability.getPayCosts().getTotalMana().getCMC() > upperBound
                ? Result.UNAVAILABLE : Result.NEEDS_NORMAL_CHECK;
    }

    private static boolean hasPossibleReduction(final Player ai, final SpellAbility ability) {
        if (Set.of("ReduceCost", "SetCost", "RaiseCost").stream().anyMatch(ability::hasParam)) {
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
