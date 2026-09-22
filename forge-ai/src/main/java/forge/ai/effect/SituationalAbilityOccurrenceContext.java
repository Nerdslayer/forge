package forge.ai.effect;

import java.util.Optional;
import java.util.Set;

import forge.ai.AttackLikelihoodEvaluator;
import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerResourceValueEvaluator;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostDamage;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostExile;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostRemoveAnyCounter;
import forge.game.cost.CostRemoveCounter;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTap;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/** Provides the current-game assumptions used by relationship analysis. */
final class SituationalAbilityOccurrenceContext implements AbilityOccurrenceContext {
    private static final int FULL_HAND_COMPETITION_SIZE = 7;
    private static final double HAND_COMPETITION_PENALTY = 0.55;

    private static final Set<String> ATTACK_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> PHASE_TRIGGER_PARAMS = Set.of(
            "Mode", "Phase", "ValidPlayer", "Execute", "TriggerZones", "TriggerDescription", "Secondary");

    private final Player evaluatingAi;

    SituationalAbilityOccurrenceContext(final Player evaluatingAi) {
        this.evaluatingAi = evaluatingAi;
    }

    @Override
    public AbilityOccurrenceRequest triggerRequest(final Card source, final Trigger trigger) {
        if (source == null || trigger == null) {
            return AbilityOccurrenceRequest.unsupported("Missing trigger or source");
        }
        if (trigger.getMode() == TriggerType.Attacks) {
            if (!EffectAbilityUtils.hasOnlyParams(trigger, ATTACK_TRIGGER_PARAMS)
                    || !"Card.Self".equals(trigger.getParam("ValidCard"))) {
                return AbilityOccurrenceRequest.unsupported("Unsupported attack trigger");
            }
            final double opportunity = AttackLikelihoodEvaluator.estimateNextTurn(
                    evaluatingAi, source).isExpected() ? 1 : 0;
            return supportedTrigger(opportunity, "attack likelihood");
        }
        if (trigger.getMode() == TriggerType.Phase) {
            if (!EffectAbilityUtils.hasOnlyParams(trigger, PHASE_TRIGGER_PARAMS)
                    || ScheduledTriggerParser.parse(trigger.getMapParams()).isEmpty()
                    || (trigger.hasParam("ValidPlayer")
                            && !"You".equals(trigger.getParam("ValidPlayer")))) {
                return AbilityOccurrenceRequest.unsupported("Unsupported phase trigger");
            }
            return supportedTrigger(1, "supported phase");
        }
        return AbilityOccurrenceRequest.unsupported("Unsupported trigger type");
    }

    @Override
    public ActivationOccurrenceRequest activationRequest(final Card source,
            final SpellAbility ability) {
        if (source == null || ability == null || !source.isInPlay()
                || !ability.isActivatedAbility() || source.isPhasedOut()) {
            return ActivationOccurrenceRequest.unsupported("Not an active activated ability");
        }
        final Cost cost = ability.getPayCosts();
        final Optional<SupportedActivationCost> supportedCost = supportedActivationCost(cost, ability);
        if (supportedCost.isEmpty()) {
            return ActivationOccurrenceRequest.unsupported("Unsupported activation cost");
        }

        final Player controller = source.getController();
        final SpellAbility copy = ability.copy(source, false);
        copy.setActivatingPlayer(controller);
        if (!copy.getRestrictions().checkZoneRestrictions(source, copy)
                || copy.getConditions() != null && !copy.getConditions().areMet(copy)) {
            return ActivationOccurrenceRequest.unsupported("Current activation restrictions are not met");
        }

        final SupportedActivationCost activationCost = supportedCost.get();
        final int manaCost = activationCost.manaCost();
        final int currentMana = Math.max(0, ComputerUtilMana.getAvailableManaEstimate(controller, true));
        final int nextTurnMana = Math.max(0, ComputerUtilMana.getAvailableManaEstimate(controller, false)
                - controller.getManaPool().totalMana());
        final int currentLife = Math.max(0, controller.getLife());
        final double landProbability = AbilityOccurrenceEstimator.estimateAdditionalLandProbability(controller);
        final boolean canPayNow = ComputerUtilCost.canPayCost(copy, controller, false);
        final Willingness willingness = estimateWillingness(source, copy, manaCost);
        final String reason = willingness.reason()
                + (activationCost.lifeCost() == 0 ? ""
                        : "; fixed life payment=" + activationCost.lifeCost());
        return new ActivationOccurrenceRequest(currentMana, nextTurnMana, manaCost,
                activationCost.lifeCost(),
                currentLife, currentLife, cost.hasTapCost(), source.isTapped(), canPayNow, landProbability,
                willingness.multiplier(), willingness.outcomeValue(),
                willingness.averageCardPlayValue(), willingness.outcomeSupported(), true, reason);
    }

    static double estimateAdditionalLandProbability(final Player player) {
        return AbilityOccurrenceEstimator.estimateAdditionalLandProbability(player);
    }

    private static AbilityOccurrenceRequest supportedTrigger(final double opportunity,
            final String reason) {
        return new AbilityOccurrenceRequest(opportunity, 1, 1, 1, 1, 1, true, reason);
    }

    static Optional<SupportedActivationCost> supportedActivationCost(final Cost cost) {
        return supportedActivationCost(cost, null);
    }

    /**
     * Returns the narrow activation-cost model shared by occurrence and action valuation.
     *
     * <p>Source-bound sacrifice and exile are safe to represent because the source card is known
     * and can be included in the action resource footprint. Costs that choose another permanent,
     * a card from hand, or a counter remain unsupported until that chosen resource can be carried
     * through a combination. X and alternative costs are also intentionally rejected here.</p>
     */
    static Optional<SupportedActivationCost> supportedActivationCost(final Cost cost,
            final SpellAbility ability) {
        if (cost == null) {
            return Optional.empty();
        }
        final int xCount = cost.getTotalMana().countX();
        final Integer xPaid = ability == null ? null : ability.getXManaCostPaid();
        if (xCount > 0 && (xPaid == null || xPaid < 0)) {
            // An X cost is supported only after the caller has made the same announcement that
            // the live payment path will use.
            return Optional.empty();
        }
        int lifeCost = 0;
        boolean consumesSource = false;
        for (final CostPart part : cost.getCostParts()) {
            if (part instanceof CostPayLife || part instanceof CostDamage) {
                if (!part.getAmount().matches("\\d+")) {
                    return Optional.empty();
                }
                try {
                    lifeCost = Math.addExact(lifeCost, Integer.parseInt(part.getAmount()));
                } catch (final ArithmeticException | NumberFormatException invalidAmount) {
                    return Optional.empty();
                }
            } else if (part instanceof CostPartMana || part instanceof CostTap) {
                continue;
            } else if (part instanceof CostSacrifice || part instanceof CostExile
                    || part instanceof CostDiscard || part instanceof CostRemoveCounter
                    || part instanceof CostRemoveAnyCounter) {
                if (!part.getAmount().matches("\\d+")) {
                    return Optional.empty();
                }
                if (ability != null && part.payCostFromSource()
                        && (part instanceof CostSacrifice || part instanceof CostExile)
                        && "1".equals(part.getAmount())) {
                    consumesSource = true;
                }
            } else {
                return Optional.empty();
            }
        }
        final int manaCost = cost.getTotalMana().getCMC() + (xPaid == null ? 0 : xPaid * xCount);
        return Optional.of(new SupportedActivationCost(manaCost, lifeCost,
                cost.hasTapCost(), consumesSource));
    }

    record SupportedActivationCost(int manaCost, int lifeCost, boolean hasTapCost,
            boolean consumesSource) {
    }

    /** Estimates whether the controller will choose the activation in the current position. */
    private static Willingness estimateWillingness(final Card source, final SpellAbility ability,
            final int manaCost) {
        final Player controller = source.getController();
        final Player perspective = controller.getOpponents().stream().findFirst().orElse(null);
        if (perspective == null || !SpellAbilityOutcomePlanner.supports(ability)) {
            return Willingness.unmodeled("outcome not safely evaluable");
        }

        final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(ability, perspective);
        if (!plan.supported()) {
            return Willingness.unmodeled("outcome planner unsupported: " + plan.reason());
        }

        final int outcomeValue = safeScore(plan.value());
        if (outcomeValue <= 0) {
            // A negative planned value may be the visible cost portion of an activated ability
            // whose other value is represented by a separate production/consequence relationship.
            return Willingness.unmodeled("non-positive outcome; preserve cost/payoff occurrence");
        }
        final int averageCardPlayValue = PlayerResourceValueEvaluator.evaluateAverageCardPlay(manaCost);
        final double quality = activationQuality(plan.value(), averageCardPlayValue);
        final int handSize = controller.getCardsIn(ZoneType.Hand).size();
        final double handCompetition = Math.min(1.0, Math.max(0.0,
                (handSize - 1.0) / (FULL_HAND_COMPETITION_SIZE - 1.0)));
        final double willingness = clamp(quality
                - (1.0 - quality) * HAND_COMPETITION_PENALTY * handCompetition);
        return new Willingness(willingness, outcomeValue, averageCardPlayValue,
                true, "outcome/card comparison; hand competition="
                        + String.format("%.2f", handCompetition));
    }

    private static double activationQuality(final double outcomeValue,
            final int averageCardPlayValue) {
        if (outcomeValue <= 0 || averageCardPlayValue <= 0) {
            return 0;
        }
        final double ratio = outcomeValue / averageCardPlayValue;
        if (ratio < 1.0) {
            return 0.25 + 0.50 * ratio;
        }
        return Math.min(1.0, 0.75 + 0.25 * (ratio - 1.0) / 2.0);
    }

    private static double clamp(final double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static int safeScore(final double value) {
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE
                : value <= Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) Math.round(value);
    }

    private record Willingness(double multiplier, int outcomeValue, int averageCardPlayValue,
            boolean outcomeSupported, String reason) {
        private static Willingness unmodeled(final String reason) {
            // Keep the prior occurrence estimate if the shared outcome algebra cannot safely
            // evaluate this ability yet. Unsupported outcome forms must not erase production
            // support that already existed for their activation costs.
            return new Willingness(1, 0, 0, false, reason);
        }
    }
}
