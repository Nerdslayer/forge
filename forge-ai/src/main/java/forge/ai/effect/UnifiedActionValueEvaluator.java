package forge.ai.effect;

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
        return CardValueBreakdown.unsupported("This action type is not supported yet.");
    }
}
