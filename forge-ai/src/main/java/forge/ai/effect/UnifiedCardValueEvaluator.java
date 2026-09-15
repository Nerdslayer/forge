package forge.ai.effect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilCard;
import forge.game.card.Card;
import forge.game.player.Player;

/**
 * Shared entry point for situational card valuation.
 *
 * <p>The initial implementation adapts the existing removal evaluators. It deliberately keeps
 * the current relationship and intrinsic calculations intact while placing their result beside
 * the card's current presence value. Casting, hand, and combat actions can add their own
 * transition and access components through this same breakdown later.</p>
 */
public final class UnifiedCardValueEvaluator {
    private UnifiedCardValueEvaluator() {
    }

    /** Raw relationship and intrinsic values retained for removal diagnostics. */
    public record RemovalCandidateEvaluation(CardValueBreakdown breakdown,
            int relationshipValue, int intrinsicValue) {
        public RemovalCandidateEvaluation {
            if (breakdown == null) {
                throw new IllegalArgumentException("A card value breakdown is required");
            }
        }
    }

    /**
     * Evaluates removal candidates using the shared card-value breakdown.
     *
     * <p>The score is intentionally equivalent to the previous removal calculation:
     * permanent value plus removal-priority adjustment plus the configured weighted relationship
     * and intrinsic values. The new context and breakdown make those components reusable without
     * changing their calibration.</p>
     */
    public static Map<Card, RemovalCandidateEvaluation> evaluateRemovalCandidates(final Player ai,
            final Iterable<Card> candidates, final ValuationContext context,
            final EffectAnalysisTrace trace) {
        return evaluateRemovalCandidates(ai, candidates, context, RemovalActionKind.PERMANENT_REMOVAL,
                trace);
    }

    /**
     * Evaluates removal candidates for the proposed action. The default overload preserves the
     * existing permanent-removal behavior; bounce applies its retained-hand adjustment here.
     */
    public static Map<Card, RemovalCandidateEvaluation> evaluateRemovalCandidates(final Player ai,
            final Iterable<Card> candidates, final ValuationContext context,
            final RemovalActionKind actionKind, final EffectAnalysisTrace trace) {
        if (ai == null || candidates == null) {
            return Map.of();
        }
        if (context == null || context.mode() != ValuationMode.SITUATIONAL
                || context.decision() != ValuationDecision.REMOVAL_TARGET) {
            throw new IllegalArgumentException("Removal candidates require a situational removal context");
        }

        final List<Card> candidateList = new ArrayList<>();
        candidates.forEach(candidate -> {
            if (candidate != null) {
                candidateList.add(candidate);
            }
        });
        final Map<Card, PermanentAbilityValueEvaluator.Breakdown> abilityValues =
                PermanentAbilityValueEvaluator.evaluateRemovalAbilities(ai, candidateList, trace,
                        context.intrinsicWeightPercent() > 0,
                        context.relationshipWeightPercent() > 0);
        final Map<Card, RemovalCandidateEvaluation> result = new HashMap<>();
        for (final Card candidate : candidateList) {
            final PermanentAbilityValueEvaluator.Breakdown abilityValue = abilityValues.get(candidate);
            final int relationshipValue = abilityValue == null ? 0 : abilityValue.relationshipValue();
            final int intrinsicValue = abilityValue == null ? 0 : abilityValue.intrinsicValue();
            final int weightedFuture = add(
                    applyWeight(relationshipValue, context.relationshipWeightPercent()),
                    applyWeight(intrinsicValue, context.intrinsicWeightPercent()));
            final List<String> reasons = abilityValue == null
                    ? List.of() : abilityValue.reasons();
            final ValuationCompleteness completeness = weightedFuture == 0 && reasons.isEmpty()
                    ? ValuationCompleteness.COMPLETE : ValuationCompleteness.PARTIAL;
            final CardValueBreakdown permanentRemovalValue = new CardValueBreakdown(
                    ComputerUtilCard.evaluatePermanent(ai, candidate), weightedFuture, 0, 0,
                    removalContextAdjustment(ai, candidate), completeness, reasons);
            final CardValueBreakdown breakdown = RemovalActionEvaluator.evaluate(candidate,
                    permanentRemovalValue, actionKind);
            result.put(candidate, new RemovalCandidateEvaluation(breakdown,
                    relationshipValue, intrinsicValue));
        }
        return result;
    }

    /** Preserves the existing base removal priority for callers that do not enable analysis. */
    public static int evaluateRemovalTargetPriority(final Player ai, final Card candidate) {
        if (ai == null || candidate == null) {
            return 0;
        }
        return add(ComputerUtilCard.evaluatePermanent(ai, candidate),
                removalContextAdjustment(ai, candidate));
    }

    private static int removalContextAdjustment(final Player ai, final Card candidate) {
        int value = candidate.isToken() ? 30 : 0;
        if (candidate.getController() != null && candidate.getController().isOpponentOf(ai)) {
            value = add(value, ComputerUtil.evaluateBoardPosition(ai, candidate.getController()) / 4);
        }
        return value;
    }

    private static int applyWeight(final int value, final int percentage) {
        final long product = (long) value * percentage;
        final long weighted = (product + (product >= 0 ? 50 : -50)) / 100;
        return weighted > Integer.MAX_VALUE ? Integer.MAX_VALUE
                : weighted < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) weighted;
    }

    private static int add(final int left, final int right) {
        final long result = (long) left + right;
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE
                : result < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) result;
    }
}
