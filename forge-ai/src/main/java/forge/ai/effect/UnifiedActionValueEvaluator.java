package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;

import forge.ai.CardResourceValueEvaluator;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Shared action-value boundary that composes a card value with an action transition. */
public final class UnifiedActionValueEvaluator {
    private UnifiedActionValueEvaluator() {
    }

    /** Evaluates an action without enabling diagnostic tracing. */
    public static CardValueBreakdown evaluate(final ValuationAction action,
            final ValuationContext context) {
        return evaluate(action, context, EffectAnalysisTrace.disabled());
    }

    /**
     * Evaluates one supported action from the supplied context.
     *
     * <p>Removal is the first supported action adapter. Cast, activation, combat, and other
     * action kinds should add dedicated value components here rather than teaching the shared
     * card evaluator about every decision type.</p>
     *
     * TODO(unified valuation): Add spell-cast, activation, attack, block, discard, and other
     * action records as their cost and outcome semantics become explicit.
     */
    public static CardValueBreakdown evaluate(final ValuationAction action,
            final ValuationContext context, final EffectAnalysisTrace trace) {
        if (action == null || context == null) {
            return CardValueBreakdown.unavailable("An action and valuation context are required.");
        }
        if (action instanceof RemovalValuationAction removal) {
            if (context.decision() != ValuationDecision.REMOVAL_TARGET) {
                return CardValueBreakdown.unsupported(
                        "Removal actions require a removal-target valuation context.");
            }
            final CardValueBreakdown permanentValue = UnifiedCardValueEvaluator.evaluatePermanent(
                    removal.target(), context, trace);
            return RemovalActionEvaluator.evaluate(context.evaluatingAi(), removal.target(),
                    permanentValue, removal.actionKind());
        }
        if (action instanceof CastValuationAction cast) {
            if (context.decision() != ValuationDecision.CAST || context.evaluatingAi() == null) {
                return CardValueBreakdown.unsupported(
                        "Cast actions require a situational cast valuation context.");
            }
            return evaluateCast(cast, context);
        }
        return CardValueBreakdown.unsupported("This action type is not supported yet.");
    }

    private static CardValueBreakdown evaluateCast(final CastValuationAction cast,
            final ValuationContext context) {
        // TODO: Include non-mana additional costs, alternative costs, X values, timing/flash
        // availability, and entry effects as the cast-action adapter becomes more complete.
        final Card card = cast.subject();
        if (!card.isInZone(ZoneType.Hand)) {
            return CardValueBreakdown.unavailable("Casting valuation requires a card in hand.");
        }
        final Player ai = context.evaluatingAi();
        final SpellAbility ability = cast.spellAbility().copy(card, ai, false);
        if (!ability.isSpell()) {
            return CardValueBreakdown.unsupported("The action is not a spell cast.");
        }

        final int manaCost = ability.getPayCosts() == null || ability.getPayCosts().getTotalMana() == null
                ? card.getCMC() : ability.getPayCosts().getTotalMana().getCMC();
        final int accessCost = EffectMath.add(
                CardResourceValueEvaluator.evaluateCardOpportunityCost(),
                CardResourceValueEvaluator.evaluateManaInvestment(manaCost));
        final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(ability, ai);
        final List<String> reasons = new ArrayList<>();
        reasons.add("Cast action consumes one card and " + manaCost + " mana.");
        if (!plan.reason().isBlank()) {
            reasons.add("Cast outcome plan: " + plan.reason());
        }

        if (!card.isPermanent()) {
            if (plan.unavailable()) {
                return CardValueBreakdown.unavailable("Cast outcome is unavailable: " + plan.reason());
            }
            if (!plan.supported()) {
                return CardValueBreakdown.unsupported("Cast outcome is unsupported: " + plan.reason());
            }
            reasons.add("Immediate outcome benefit: " + EffectMath.negate(safeScore(plan.value())));
            return new CardValueBreakdown(0, 0, EffectMath.negate(safeScore(plan.value())), accessCost,
                    0, planCompleteness(plan), reasons);
        }

        final CardValueBreakdown cardValue = UnifiedCardValueEvaluator.evaluateCard(card, context);
        final CardValueBreakdown permanentValue = replaceAccessCost(cardValue, accessCost, reasons);
        if (plan.unavailable()) {
            return CardValueBreakdown.unavailable("Cast outcome is unavailable: " + plan.reason());
        }
        if (!plan.supported()) {
            // A normal permanent spell's battlefield value is already represented by the card
            // evaluator even when its SpellPermanent wrapper is not an atomic outcome. Keep that
            // known value, but mark casts with other unresolved results as incomplete.
            if (ability.getApi() == ApiType.PermanentCreature
                    || ability.getApi() == ApiType.PermanentNoncreature) {
                return permanentValue;
            }
            return withCompleteness(permanentValue, ValuationCompleteness.PARTIAL,
                    "Cast outcome is unsupported: " + plan.reason());
        }

        return permanentValue.plus(new CardValueBreakdown(0, 0,
                EffectMath.negate(safeScore(plan.value())), 0, 0,
                planCompleteness(plan), List.of("Immediate outcome benefit: "
                        + EffectMath.negate(safeScore(plan.value())))));
    }

    private static CardValueBreakdown replaceAccessCost(final CardValueBreakdown value,
            final int accessCost, final List<String> reasons) {
        final List<String> combined = new ArrayList<>(value.reasons());
        combined.addAll(reasons);
        return new CardValueBreakdown(value.currentPresenceValue(), value.futurePotentialValue(),
                value.transitionValue(), accessCost, value.contextAdjustment(), value.completeness(),
                combined);
    }

    private static CardValueBreakdown withCompleteness(final CardValueBreakdown value,
            final ValuationCompleteness completeness, final String reason) {
        final List<String> reasons = new ArrayList<>(value.reasons());
        reasons.add(reason);
        return new CardValueBreakdown(value.currentPresenceValue(), value.futurePotentialValue(),
                value.transitionValue(), value.accessCost(), value.contextAdjustment(),
                ValuationCompleteness.combine(value.completeness(), completeness), reasons);
    }

    private static ValuationCompleteness planCompleteness(final OutcomePlan<OutcomeState> plan) {
        return switch (plan.completeness()) {
        case COMPLETE -> ValuationCompleteness.COMPLETE;
        case PARTIAL -> ValuationCompleteness.PARTIAL;
        case UNAVAILABLE -> ValuationCompleteness.UNAVAILABLE;
        case UNSUPPORTED -> ValuationCompleteness.UNSUPPORTED;
        };
    }

    private static int safeScore(final double value) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE
                : value <= Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) Math.round(value);
    }
}
