package forge.ai.effect;

import forge.ai.ComputerUtilCard;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CounterEnumType;
import forge.game.cost.CostPart;
import forge.game.cost.CostPutCounter;
import forge.game.cost.CostRemoveCounter;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/** Shared signed value of a loyalty-counter change on a planeswalker. */
final class PlaneswalkerLoyaltyValue {
    private PlaneswalkerLoyaltyValue() {
    }

    /**
     * Returns the permanent-evaluation delta for changing the source's loyalty. A planeswalker
     * reduced to zero loyalty leaves the battlefield, so its resulting value is zero.
     */
    static int change(final Player evaluatingAi, final Card source, final int amount) {
        // TODO(planeswalker activation): Handle replacement/prevention effects and consider
        // whether loyalty has strategic value beyond the shared permanent-score delta.
        if (evaluatingAi == null || source == null || !source.isPlaneswalker()) {
            return 0;
        }
        final int before = ComputerUtilCard.evaluatePermanent(evaluatingAi, source);
        final Card changed = CardCopyService.getLKICopy(source);
        if (source.getZone() != null) {
            changed.setZone(source.getZone());
        }
        final int loyalty = Math.max(0, source.getCounters(CounterEnumType.LOYALTY) + amount);
        changed.setCounters(CounterEnumType.LOYALTY, loyalty);
        final int after = loyalty == 0 ? 0
                : ComputerUtilCard.evaluatePermanent(evaluatingAi, changed);
        return after - before;
    }

    static int abilityBenefit(final Player evaluatingAi, final SpellAbility ability) {
        if (ability == null || ability.getPayCosts() == null) {
            return 0;
        }
        final Card source = ability.getHostCard();
        int result = 0;
        for (final CostPart part : ability.getPayCosts().getCostParts()) {
            if (!(part instanceof CostPutCounter || part instanceof CostRemoveCounter)
                    || !part.payCostFromSource() || !part.getAmount().matches("\\d+")) {
                continue;
            }
            final int amount = Integer.parseInt(part.getAmount());
            if (part instanceof CostPutCounter put && put.getCounter() != null
                    && put.getCounter().is(CounterEnumType.LOYALTY)) {
                result += change(evaluatingAi, source, amount);
            } else if (part instanceof CostRemoveCounter remove && remove.counter != null
                    && remove.counter.is(CounterEnumType.LOYALTY)) {
                result += change(evaluatingAi, source, -amount);
            }
        }
        return result;
    }
}
