package forge.ai.effect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import forge.ai.CardDefinitionValueEvaluator;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilCard;
import forge.card.CardEdition;
import forge.card.CardRules;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.IPaperCard;

/**
 * Shared entry point for situational card valuation.
 *
 * <p>The initial implementation adapts the existing removal evaluators. It deliberately keeps
 * the current relationship and intrinsic calculations intact while placing their result beside
 * the card's current presence value. Casting, hand, and combat actions can add their own
 * transition and access components through this same breakdown later.</p>
 */
public final class UnifiedCardValueEvaluator {
    private static final CardDefinitionValueEvaluator DEFINITION_EVALUATOR =
            new CardDefinitionValueEvaluator();

    private UnifiedCardValueEvaluator() {
    }

    /**
     * Evaluates a card through the shared context-aware entry point.
     *
     * <p>General intrinsic contexts use the definition evaluator. Hand-selection contexts use the
     * supplied information boundary to distinguish a complete hand from one known card in an
     * otherwise hidden hand. A live permanent still needs a future situational adapter so current
     * presence is not confused with a definition-only estimate.</p>
     */
    public static CardValueBreakdown evaluateCard(final Card card, final ValuationContext context) {
        if (card == null || context == null) {
            return CardValueBreakdown.unavailable("A card and valuation context are required.");
        }
        if (context.mode() == ValuationMode.SITUATIONAL
                && (context.decision() == ValuationDecision.HAND_SELECTION
                        || context.decision() == ValuationDecision.CAST)) {
            return evaluateHandCard(card, context);
        }
        if (context.mode() == ValuationMode.SITUATIONAL
                && context.decision() == ValuationDecision.REMOVAL_TARGET) {
            return evaluatePermanent(card, context);
        }
        if (context.mode() != ValuationMode.INTRINSIC_REFERENCE
                || context.decision() != ValuationDecision.GENERAL_CARD) {
            return CardValueBreakdown.unsupported(
                    "This entry point only evaluates general intrinsic card definitions.");
        }

        final IPaperCard paperCard = card.getPaperCard();
        if (paperCard == null || paperCard.getRules() == null) {
            return CardValueBreakdown.unavailable("The card has no evaluable definition.");
        }
        return evaluateCard(paperCard.getRules(), paperCard.getEdition(), context);
    }

    /** Evaluates a card definition with the default unknown edition context. */
    public static CardValueBreakdown evaluateCard(final CardRules rules,
            final ValuationContext context) {
        return evaluateCard(rules, CardEdition.UNKNOWN_CODE, context);
    }

    /** Evaluates a card definition without requiring a live game card. */
    public static CardValueBreakdown evaluateCard(final CardRules rules, final String editionCode,
            final ValuationContext context) {
        if (rules == null || context == null) {
            return CardValueBreakdown.unavailable("A card definition and valuation context are required.");
        }
        if (context.mode() != ValuationMode.INTRINSIC_REFERENCE
                || context.decision() != ValuationDecision.GENERAL_CARD) {
            return CardValueBreakdown.unsupported(
                    "Definition valuation requires a general intrinsic-card context.");
        }
        try {
            return DEFINITION_EVALUATOR.evaluate(rules, editionCode)
                    .toCardValueBreakdown();
        } catch (final RuntimeException ex) {
            return CardValueBreakdown.unsupported(
                    "The card definition could not be evaluated safely.");
        }
    }

    /** Evaluates a known card in a hand through the shared card valuation entry point. */
    public static CardValueBreakdown evaluateCard(final Card card,
            final HandValuationContext context) {
        return HandCardValueEvaluator.evaluateKnownCard(card, context);
    }

    /**
     * Evaluates one live permanent through a situational context. The initial live adapter is
     * intentionally limited to removal-target valuation; later decisions can add their own
     * action-specific components without changing the intrinsic definition entry point.
     */
    public static CardValueBreakdown evaluatePermanent(final Card candidate,
            final ValuationContext context) {
        return evaluatePermanent(candidate, context, EffectAnalysisTrace.disabled());
    }

    /**
     * Evaluates one live permanent with optional effect-analysis diagnostics.
     *
     * <p>Callers that do not need diagnostics should use the two-argument overload so the
     * evaluator remains a simple reusable API and tracing stays an explicit concern.</p>
     */
    public static CardValueBreakdown evaluatePermanent(final Card candidate,
            final ValuationContext context, final EffectAnalysisTrace trace) {
        if (candidate == null || context == null) {
            return CardValueBreakdown.unavailable("A permanent and valuation context are required.");
        }
        if (context.mode() != ValuationMode.SITUATIONAL
                || context.decision() != ValuationDecision.REMOVAL_TARGET
                || context.evaluatingAi() == null) {
            return CardValueBreakdown.unsupported(
                    "Live permanent valuation currently requires a removal-target context.");
        }
        if (!candidate.isInZone(ZoneType.Battlefield)) {
            return CardValueBreakdown.unavailable(
                    "Removal-target valuation requires a permanent on the battlefield.");
        }
        final EffectAnalysisTrace effectiveTrace = trace == null
                ? EffectAnalysisTrace.disabled() : trace;
        final Map<Card, PermanentAbilityValueEvaluator.Breakdown> abilityValues =
                PermanentAbilityValueEvaluator.evaluateRemovalAbilities(context.evaluatingAi(),
                        List.of(candidate), effectiveTrace, context.intrinsicWeightPercent() > 0,
                        context.relationshipWeightPercent() > 0);
        return buildPermanentBreakdown(context.evaluatingAi(), candidate, context,
                abilityValues.get(candidate));
    }

    private static CardValueBreakdown evaluateHandCard(final Card card,
            final ValuationContext context) {
        if (!card.isInZone(ZoneType.Hand)) {
            return CardValueBreakdown.unavailable(
                    "Hand or casting valuation requires a card in hand.");
        }
        final Player handOwner = card.getOwner() == null ? card.getController() : card.getOwner();
        if (handOwner == null) {
            return CardValueBreakdown.unavailable("The hand owner is unavailable.");
        }
        final HandValuationContext handContext = context.completeInformation()
                ? HandValuationContext.fullHand(context.evaluatingAi(), handOwner)
                : HandValuationContext.knownCardOnly(context.evaluatingAi(), handOwner, card,
                        handOwner.getCardsIn(ZoneType.Hand).size());
        return evaluateCard(card, handContext);
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
            final CardValueBreakdown permanentRemovalValue = buildPermanentBreakdown(ai, candidate,
                    context, abilityValue);
            final int relationshipValue = abilityValue == null ? 0 : abilityValue.relationshipValue();
            final int intrinsicValue = abilityValue == null ? 0 : abilityValue.intrinsicValue();
            final CardValueBreakdown breakdown = RemovalActionEvaluator.evaluate(ai, candidate,
                    permanentRemovalValue, actionKind);
            result.put(candidate, new RemovalCandidateEvaluation(breakdown,
                    relationshipValue, intrinsicValue));
        }
        return result;
    }

    private static CardValueBreakdown buildPermanentBreakdown(final Player ai, final Card candidate,
            final ValuationContext context,
            final PermanentAbilityValueEvaluator.Breakdown abilityValue) {
        final int relationshipValue = abilityValue == null ? 0 : abilityValue.relationshipValue();
        final int intrinsicValue = abilityValue == null ? 0 : abilityValue.intrinsicValue();
        final int weightedFuture = add(
                applyWeight(relationshipValue, context.relationshipWeightPercent()),
                applyWeight(intrinsicValue, context.intrinsicWeightPercent()));
        final List<String> reasons = abilityValue == null
                ? List.of() : abilityValue.reasons();
        final ValuationCompleteness completeness = weightedFuture == 0 && reasons.isEmpty()
                ? ValuationCompleteness.COMPLETE : ValuationCompleteness.PARTIAL;
        return new CardValueBreakdown(ComputerUtilCard.evaluatePermanent(ai, candidate), weightedFuture,
                0, 0, removalContextAdjustment(ai, candidate), completeness, reasons);
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
