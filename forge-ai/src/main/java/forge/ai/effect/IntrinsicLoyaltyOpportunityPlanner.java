package forge.ai.effect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;

/** Shared, game-free loyalty opportunities; alternatives do not each get a full activation horizon. */
final class IntrinsicLoyaltyOpportunityPlanner {
    private static final int MAX_STATES = 4096;

    record Option(String path, int loyaltyChange) { }

    /** Outcome subtotal for this cost-paid loyalty state, not a probability assigned to a mode. */
    record Payoff(double value, boolean available, boolean complete, List<String> unresolvedReasons) {
        Payoff {
            if (!Double.isFinite(value)) { throw new IllegalArgumentException("Finite loyalty payoff required"); }
            unresolvedReasons = List.copyOf(unresolvedReasons);
        }
    }

    @FunctionalInterface
    interface OutcomeEvaluator {
        Payoff evaluate(Option option, int loyaltyAfterPayment, int controllerTurn);
    }

    record Contribution(double value, double expectedUses, double currentTurnUses) { }

    record Result(double value, Map<String, Contribution> contributions, boolean supported,
            boolean complete, List<String> unresolvedReasons, int statesEvaluated) {
        Result {
            contributions = Map.copyOf(contributions);
            unresolvedReasons = List.copyOf(unresolvedReasons);
        }
    }

    private record Key(int opportunity, int loyalty) { }
    private record Plan(double value, Map<String, Contribution> contributions, List<String> reasons) { }

    private IntrinsicLoyaltyOpportunityPlanner() { }

    static Result estimate(final List<Option> options, final PermanentProfile source,
            final IntrinsicReferenceModel model, final IntrinsicEvaluationSettings settings,
            final EntryTiming timing, final OutcomeEvaluator outcomes) {
        if (options == null || source == null || model == null || settings == null || timing == null || outcomes == null
                || source.kind() != PermanentKind.PLANESWALKER || source.loyalty() < 0
                || options.size() > 32 || settings.futureActivationControllerTurns() > 24
                || options.stream().anyMatch(option -> option == null || option.path() == null || option.path().isBlank()
                        || Math.abs((long) option.loyaltyChange()) > 1000)
                || options.stream().map(Option::path).distinct().count() != options.size()) {
            return new Result(0, Map.of(), false, false, List.of("Unsupported intrinsic loyalty inputs"), 0);
        }
        final var search = new Search(options, source, model, settings, timing, outcomes);
        try {
            final var plan = source.present() && source.loyalty() > 0 ? search.best(0, source.loyalty())
                    : new Plan(0, Map.of(), List.of());
            return new Result(plan.value(), plan.contributions(), true, plan.reasons().isEmpty(), plan.reasons(), search.states);
        } catch (final IllegalStateException | ArithmeticException limit) {
            return new Result(0, Map.of(), false, false, List.of("Intrinsic loyalty planning limit exceeded"), search.states);
        }
    }

    private static final class Search {
        private final List<Option> options;
        private final PermanentProfile source;
        private final IntrinsicReferenceModel model;
        private final IntrinsicEvaluationSettings settings;
        private final EntryTiming timing;
        private final OutcomeEvaluator outcomes;
        private final Map<Key, Plan> memo = new LinkedHashMap<>();
        private int states;

        private Search(final List<Option> options, final PermanentProfile source,
                final IntrinsicReferenceModel model, final IntrinsicEvaluationSettings settings,
                final EntryTiming timing, final OutcomeEvaluator outcomes) {
            this.options = List.copyOf(options);
            this.source = source;
            this.model = model;
            this.settings = settings;
            this.timing = timing;
            this.outcomes = outcomes;
        }

        private Plan best(final int opportunity, final int loyalty) {
            final int opportunities = settings.futureActivationControllerTurns() + (timing.firstTurnIsControllerTurn() ? 1 : 0);
            if (opportunity >= opportunities || loyalty <= 0) { return new Plan(0, Map.of(), List.of()); }
            final Key key = new Key(opportunity, loyalty);
            final Plan cached = memo.get(key);
            if (cached != null) { return cached; }
            if (++states > MAX_STATES) { throw new IllegalStateException("Loyalty state budget"); }
            final int controllerTurn = opportunity + 1;
            final boolean current = timing.firstTurnIsControllerTurn() && opportunity == 0;
            final int relativeTurn = timing.firstTurnIsControllerTurn() ? 2 * controllerTurn - 1 : 2 * controllerTurn;
            final double weight = (current ? 1 : new PermanentSurvivalEstimator(model)
                    .probabilityAtTurnStart(source, timing, relativeTurn)) * AbilityOccurrenceEstimator.turnDiscount(controllerTurn);
            Plan winner = best(opportunity + 1, loyalty); // Waiting preserves loyalty and does not spend an activation.
            final List<String> unresolved = new ArrayList<>(winner.reasons());
            for (final Option option : options) {
                final int after = Math.addExact(loyalty, option.loyaltyChange());
                if (after < 0) { continue; }
                final Payoff payoff = outcomes.evaluate(option, after, controllerTurn);
                if (!payoff.complete()) {
                    unresolved.add(option.path() + ": unresolved legal loyalty alternative on controller turn " + controllerTurn);
                    unresolved.addAll(payoff.unresolvedReasons());
                }
                if (!payoff.available()) { continue; }
                // Reaching zero removes the planeswalker as a state-based action, but its
                // already-activated ability still resolves. The evaluator receives that zero.
                final Plan future = after == 0 ? new Plan(0, Map.of(), List.of()) : best(opportunity + 1, after);
                final double score = weight * payoff.value() + future.value();
                if (!Double.isFinite(score)) { throw new ArithmeticException("Nonfinite loyalty policy value"); }
                unresolved.addAll(future.reasons());
                if (score <= winner.value()) { continue; }
                final Map<String, Contribution> contributions = new LinkedHashMap<>(future.contributions());
                final Contribution previous = contributions.getOrDefault(option.path(), new Contribution(0, 0, 0));
                contributions.put(option.path(), new Contribution(previous.value() + weight * payoff.value(),
                        previous.expectedUses() + weight, previous.currentTurnUses() + (current ? 1 : 0)));
                winner = new Plan(score, Map.copyOf(contributions), future.reasons());
            }
            // Unknown choices are alternatives, not equal-probability random branches. Retain
            // a supported policy subtotal without claiming it is the best complete policy.
            final Plan result = new Plan(winner.value(), winner.contributions(), unresolved.stream().distinct().toList());
            memo.put(key, result);
            return result;
        }
    }

    // TODO: Other resource costs, loyalty-dependent survival, extra/shared
    // activations from static effects, mutable board evolution and multiplayer remain separate.
    // Only loyalty persists between opportunities; other reference quantities stay independent.
}
