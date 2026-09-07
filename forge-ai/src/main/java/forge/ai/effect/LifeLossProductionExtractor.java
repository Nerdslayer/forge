package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.CostPart;
import forge.game.cost.CostPayLife;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

/** Extracts direct life-loss productions with known recipients and amounts. */
final class LifeLossProductionExtractor implements EffectProductionExtractor {
    static final LifeLossProductionExtractor INSTANCE = new LifeLossProductionExtractor();

    // TODO(effect analysis): Support choice-dependent, repeated, multiple-part, non-activated, and
    // future life payments; exchange/set-life effects; spells and stack objects; dynamic recipients;
    // multiplayer or multi/optional targets, replacement-modified amounts, additional trigger
    // origins, multiple LoseLife steps, conditional forms, and later consequence chains.
    private static final Set<String> SUPPORTED_RECIPIENTS = Set.of(
            "You", "Opponent", "Player.Opponent");

    private LifeLossProductionExtractor() {
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final Trigger trigger) {
        final ProductionOpportunity opportunity = ProductionOpportunity.fromTrigger(
                evaluatingAi, source, trigger);
        if (opportunity == null) {
            return List.of();
        }
        final SpellAbility outcome = findSupportedLifeLossOutcome(opportunity.root());
        final EffectProduction production = outcome == null ? null
                : createProduction(source, outcome, opportunity.expectedBatches());
        return production == null ? List.of() : List.of(production);
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final SpellAbility ability) {
        final ProductionOpportunity opportunity =
                ProductionOpportunity.fromActivatedAbility(source, ability);
        if (opportunity == null) {
            return List.of();
        }
        final List<EffectProduction> productions = new ArrayList<>();
        final EffectProduction paymentProduction = createLifePaymentProduction(
                source, opportunity.root(), opportunity.expectedBatches());
        if (paymentProduction != null) {
            productions.add(paymentProduction);
        }
        final SpellAbility outcome = findSupportedLifeLossOutcome(opportunity.root());
        final EffectProduction production = outcome == null ? null
                : createProduction(source, outcome, opportunity.expectedBatches());
        if (production != null) {
            productions.add(production);
        }
        return productions;
    }

    private static SpellAbility findSupportedLifeLossOutcome(final SpellAbility root) {
        SpellAbility current = root;
        while (current != null) {
            if (EffectAbilityUtils.hasUnsupportedControlFlow(current)) {
                return null;
            }
            if (current.getApi() == ApiType.LoseLife) {
                if (!current.hasParam("LifeAmount")
                        || current.getParam("LifeAmount").isBlank()
                        || !PlayerRecipientResolver.hasSupportedTargetShape(current)
                        || (!current.usesTargeting() && !SUPPORTED_RECIPIENTS.contains(
                                current.getParamOrDefault("Defined", "You")))) {
                    return null;
                }
                return current;
            }
            current = current.getSubAbility();
        }
        return null;
    }

    private static EffectProduction createProduction(final Card source,
            final SpellAbility outcome, final int expectedBatches) {
        outcome.setActivatingPlayer(source.getController());
        final int amount = AbilityUtils.calculateAmount(
                source, outcome.getParam("LifeAmount"), outcome);
        if (amount <= 0) {
            return null;
        }

        final List<EffectEvent> events = new ArrayList<>();
        for (final Player recipient : PlayerRecipientResolver.resolve(outcome)) {
            if (!recipient.isInGame() || !recipient.canLoseLife()) {
                continue;
            }
            events.add(createLifeLossEvent(recipient, amount, outcome));
        }
        return events.isEmpty() ? null : new EffectProduction(
                source, EffectType.LIFE_LOST, events, expectedBatches);
    }

    private static EffectProduction createLifePaymentProduction(final Card source,
            final SpellAbility ability, final int expectedBatches) {
        if (ability.getPayCosts() == null) {
            return null;
        }
        CostPayLife lifePayment = null;
        for (final CostPart part : ability.getPayCosts().getCostParts()) {
            if (!(part instanceof CostPayLife candidate)) {
                continue;
            }
            if (lifePayment != null) {
                return null;
            }
            lifePayment = candidate;
        }
        if (lifePayment == null) {
            return null;
        }

        final Player payer = ability.getActivatingPlayer();
        final int amount = lifePayment.getAbilityAmount(ability);
        if (payer == null || amount <= 0 || !payer.canLoseLife()
                || (amount >= payer.getLife() && !payer.cantLoseForZeroOrLessLife())
                || LifeLossPrediction.hasApplicablePayLifeReplacement(payer, amount, ability)
                || LifeLossPrediction.hasApplicableLifeReductionReplacement(
                        payer, amount, false)) {
            return null;
        }
        return new EffectProduction(source, EffectType.LIFE_LOST,
                List.of(createLifeLossEvent(payer, amount, ability)), expectedBatches);
    }

    private static EffectEvent createLifeLossEvent(final Player recipient, final int amount,
            final SpellAbility cause) {
        final Map<AbilityKey, Object> triggerParameters = new EnumMap<>(AbilityKey.class);
        triggerParameters.put(AbilityKey.Player, recipient);
        triggerParameters.put(AbilityKey.LifeAmount, amount);
        triggerParameters.put(AbilityKey.FirstTime, recipient.getLifeLostThisTurn() == 0);
        triggerParameters.put(AbilityKey.SpellAbility, cause);
        return new EffectEvent(EffectType.LIFE_LOST, recipient,
                List.of(new EffectEvent.Subject(recipient, 1)), triggerParameters);
    }
}
