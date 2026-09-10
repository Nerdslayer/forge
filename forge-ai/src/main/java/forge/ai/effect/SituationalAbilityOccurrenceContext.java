package forge.ai.effect;

import java.util.List;
import java.util.Set;

import forge.ai.AttackLikelihoodEvaluator;
import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerResourceValueEvaluator;
import forge.game.card.Card;
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
                    || !isSupportedPhase(trigger.getParam("Phase"))
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
        final forge.game.cost.Cost cost = ability.getPayCosts();
        if (cost == null || !supportedCost(cost)) {
            return ActivationOccurrenceRequest.unsupported("Unsupported activation cost");
        }

        final Player controller = source.getController();
        final SpellAbility copy = ability.copy(source, false);
        copy.setActivatingPlayer(controller);
        if (!copy.getRestrictions().checkZoneRestrictions(source, copy)
                || copy.getConditions() != null && !copy.getConditions().areMet(copy)) {
            return ActivationOccurrenceRequest.unsupported("Current activation restrictions are not met");
        }

        final int manaCost = cost.getTotalMana().getCMC();
        final int currentMana = Math.max(0, ComputerUtilMana.getAvailableManaEstimate(controller, true));
        final int nextTurnMana = Math.max(0, ComputerUtilMana.getAvailableManaEstimate(controller, false)
                - controller.getManaPool().totalMana());
        final double landProbability = AbilityOccurrenceEstimator.estimateAdditionalLandProbability(controller);
        final boolean canPayNow = ComputerUtilCost.canPayCost(copy, controller, false);
        final Willingness willingness = estimateWillingness(source, copy, manaCost);
        return new ActivationOccurrenceRequest(currentMana, nextTurnMana, manaCost,
                cost.hasTapCost(), source.isTapped(), canPayNow, landProbability,
                willingness.multiplier(), willingness.outcomeValue(),
                willingness.averageCardPlayValue(), willingness.outcomeSupported(), true,
                willingness.reason());
    }

    static double estimateAdditionalLandProbability(final Player player) {
        return AbilityOccurrenceEstimator.estimateAdditionalLandProbability(player);
    }

    private static AbilityOccurrenceRequest supportedTrigger(final double opportunity,
            final String reason) {
        return new AbilityOccurrenceRequest(opportunity, 1, 1, 1, 1, 1, true, reason);
    }

    private static boolean isSupportedPhase(final String phase) {
        return "Upkeep".equalsIgnoreCase(phase) || "End of Turn".equalsIgnoreCase(phase);
    }

    private static boolean supportedCost(final forge.game.cost.Cost cost) {
        // TODO: Add bounded estimates for sacrifice, discard, life, counter, X, and other
        // non-mana costs. These must consume shared resources rather than being treated as free.
        if (cost.getTotalMana().countX() > 0) {
            return false;
        }
        final List<forge.game.cost.CostPart> parts = cost.getCostParts();
        return parts.isEmpty() ? cost.getTotalMana().getCMC() == 0 : parts.stream().allMatch(part ->
                part instanceof forge.game.cost.CostPartMana
                        || part instanceof forge.game.cost.CostTap);
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
