package forge.ai.effect;

import java.util.List;

import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerResourceValueEvaluator;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostTap;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Estimates bounded future activation opportunities without changing live AI decisions. */
final class ActivatedAbilityUseEvaluator {
    private static final int MAX_USES_PER_TURN = 4;
    private static final double LAND_PER_HAND_CARD = 0.40;
    private static final double NEXT_TURN_DISCOUNT = 0.75;
    private static final int FULL_HAND_COMPETITION_SIZE = 7;
    private static final double HAND_COMPETITION_PENALTY = 0.55;

    private ActivatedAbilityUseEvaluator() {
    }

    static ActivationUseEstimate estimate(final Card source, final SpellAbility ability) {
        if (source == null || ability == null || !source.isInPlay()
                || !ability.isActivatedAbility() || source.isPhasedOut()) {
            return ActivationUseEstimate.unsupported("Not an active activated ability");
        }
        final Cost cost = ability.getPayCosts();
        if (cost == null || !supportedCost(cost)) {
            return ActivationUseEstimate.unsupported("Unsupported activation cost");
        }

        final Player controller = source.getController();
        final SpellAbility copy = ability.copy(source, false);
        copy.setActivatingPlayer(controller);
        if (!copy.getRestrictions().checkZoneRestrictions(source, copy)
                || copy.getConditions() != null && !copy.getConditions().areMet(copy)) {
            return ActivationUseEstimate.unsupported("Current activation restrictions are not met");
        }

        final int manaCost = cost.getTotalMana().getCMC();
        final int currentMana = Math.max(0, ComputerUtilMana.getAvailableManaEstimate(controller, true));
        final int nextTurnMana = Math.max(0, ComputerUtilMana.getAvailableManaEstimate(controller, false)
                - controller.getManaPool().totalMana());
        final double landProbability = estimateAdditionalLandProbability(controller);
        final int nextTurnWithLand = EffectMath.add(nextTurnMana, 1);

        final boolean canPayNow = ComputerUtilCost.canPayCost(copy, controller, false);
        final int currentUses = canPayNow
                ? usesForTurn(source, cost, currentMana, manaCost, false) : 0;
        final int noLandUses = usesForTurn(source, cost, nextTurnMana, manaCost, true);
        final int withLandUses = usesForTurn(source, cost, nextTurnWithLand, manaCost, true);
        final double expectedNextTurnUses = (1 - landProbability) * noLandUses
                + landProbability * withLandUses;
        // A free untapped activation has no resource signal from which to infer safe future
        // repeatability. Keep the historical one-use estimate; the outcome/willingness model
        // still discounts whether the controller is likely to choose it.
        final double expectedUses = manaCost <= 0 && !cost.hasTapCost()
                ? currentUses : currentUses + NEXT_TURN_DISCOUNT * expectedNextTurnUses;
        if (expectedUses <= 0) {
            return ActivationUseEstimate.unsupported("No expected activation in the analysis horizon");
        }
        final Willingness willingness = estimateWillingness(source, copy, manaCost);
        final double chosenUses = expectedUses * willingness.multiplier();
        return new ActivationUseEstimate(chosenUses, expectedUses, currentUses, noLandUses,
                withLandUses, Math.max(noLandUses, withLandUses), landProbability,
                willingness.multiplier(), willingness.outcomeValue(),
                willingness.averageCardPlayValue(), willingness.outcomeSupported(), true,
                willingness.reason());
    }

    static double estimateAdditionalLandProbability(final Player player) {
        if (player == null) {
            return 0;
        }
        final int handSize = player.getCardsIn(ZoneType.Hand).size();
        return 1 - Math.pow(1 - LAND_PER_HAND_CARD, handSize);
    }

    private static int usesForTurn(final Card source, final Cost cost,
            final int availableMana, final int manaCost, final boolean assumeUntapped) {
        if (cost.hasTapCost()) {
            // A tap ability can be used once between untaps. Current availability is checked
            // separately by canPayCost; a future opportunity assumes the source untaps.
            return Math.min(MAX_USES_PER_TURN,
                    availableMana >= manaCost && (assumeUntapped || !source.isTapped()) ? 1 : 0);
        }
        if (manaCost <= 0) {
            // Without a mana or tap resource, the first pass cannot infer an activation limit.
            // Keep one expected use until the outcome/willingness model can establish safe
            // repeatability.
            return 1;
        }
        return Math.min(MAX_USES_PER_TURN, availableMana / manaCost);
    }

    private static boolean supportedCost(final Cost cost) {
        // TODO: Add bounded estimates for sacrifice, discard, life, counter, X, and other
        // non-mana costs. These must consume shared resources rather than being treated as free.
        if (cost.getTotalMana().countX() > 0) {
            return false;
        }
        final List<CostPart> parts = cost.getCostParts();
        return parts.isEmpty() ? cost.getTotalMana().getCMC() == 0 : parts.stream().allMatch(part ->
                part instanceof CostPartMana || part instanceof CostTap);
    }

    /**
     * Estimates whether the controller will choose the activation when it has other cards and
     * mana uses available. This is deliberately bounded: it compares one projected resolution
     * with a medium-hand average card play and applies a small hand-size competition penalty.
     */
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
            // whose other value is represented by a separate production/consequence relationship
            // (for example, discarding to enable a payoff). Keep the legacy occurrence estimate
            // until costs and payoffs can be evaluated together.
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
