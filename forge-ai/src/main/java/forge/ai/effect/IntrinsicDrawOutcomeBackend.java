package forge.ai.effect;

/** Initial pure reference backend. Each resolution projects its own hand changes. */
public final class IntrinsicDrawOutcomeBackend implements OutcomeDescriptionCompiler.Backend<IntrinsicDrawOutcomeBackend.State> {
    public record State(int controllerHand, int opponentHand) { }
    private final IntrinsicOutcomeEvaluator evaluator;

    public IntrinsicDrawOutcomeBackend(final IntrinsicEvaluationSettings settings) {
        evaluator = new IntrinsicOutcomeEvaluator(settings);
    }

    @Override
    public boolean maximize(final AbilityOutcomeDescription node, final boolean opponentChooses) { return !opponentChooses; }

    @Override
    public boolean acceptsNode(final AbilityOutcomeDescription node) {
        // A mode with a cost cannot be valued as a free resolution. Activation/spell costs
        // will be handled by their future origin adapters, not discarded while parsing.
        return !node.parameters().containsKey("Cost") && !node.parameters().containsKey("AB")
                && !node.parameters().containsKey("SP");
    }

    @Override
    public Outcome<State> atomic(final AbilityOutcomeDescription node) {
        final DrawOutcomeDescription draw = DrawOutcomeDescription.parse(node.api(), node.parameters()).orElse(null);
        if (draw == null || !node.choices().isEmpty()) {
            return new Outcome.Unresolved<>("Unsupported intrinsic outcome at " + node.path() + ": "
                    + node.api() + " " + node.parameters());
        }
        return new Outcome.Atomic<>(node.path(), state -> {
            // TODO: Library distributions, draw restrictions/replacements, targeted recipients,
            // remembered cards and other atomic families. No hidden game data is read here.
            final int amount = Math.max(0, draw.amount());
            final int hand = draw.controller() ? state.controllerHand() : state.opponentHand();
            final int value = evaluator.evaluateCardDraw(hand, amount, draw.controller());
            final int after = EffectMath.add(hand, amount);
            return new Outcome.Transition<>((double) value, draw.controller()
                    ? new State(after, state.opponentHand()) : new State(state.controllerHand(), after), draw);
        });
    }
}
