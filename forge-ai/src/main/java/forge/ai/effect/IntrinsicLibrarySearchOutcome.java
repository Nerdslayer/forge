package forge.ai.effect;

import java.util.Optional;
import java.util.Set;

import forge.ai.effect.IntrinsicDrawOutcomeBackend.State;

/** Fixed library-to-hand searches; selection quality is conservatively generic card value. */
final class IntrinsicLibrarySearchOutcome {
    private static final Set<String> PARAMETERS = Set.of("DB", "SubAbility", "SpellDescription", "StackDescription",
            "Secondary", "PrecostDesc", "CostDesc", "Origin", "Destination", "ChangeType", "ChangeNum", "DefinedPlayer", "Mandatory");

    private IntrinsicLibrarySearchOutcome() { }

    record Search(boolean controller, WeightedDistribution<Integer> counts) {
        Outcome<State> outcome(final String path, final IntrinsicOutcomeEvaluator evaluator) {
            return new Outcome.Random<>(path + ":search-hits", counts.entries().stream().map(entry ->
                    new Outcome.Weighted<State>(new Outcome.Atomic<State>(path, state -> {
                        final int hand = controller ? state.controllerHand() : state.opponentHand();
                        return new Outcome.Transition<>((double) evaluator.evaluateCardDraw(hand, entry.value(), controller),
                                state.withHands(controller, EffectMath.add(hand, entry.value())).clearTarget(),
                                "Library search: " + entry.value());
                    }), entry.weight())).toList());
        }
    }

    static Optional<Search> parse(final AbilityOutcomeDescription node, final IntrinsicLibraryReference library) {
        // TODO: Targeted/variable players, revealed/remembered identity, actual chosen card value,
        // search-to-battlefield/library/exile and replacements require explicit zone/card state.
        if (!"ChangeZone".equals(node.api()) || !PARAMETERS.containsAll(node.parameters().keySet())
                || !"Library".equals(node.parameters().get("Origin"))
                || !"Hand".equals(node.parameters().get("Destination"))
                || node.parameters().containsKey("Mandatory") && !"True".equals(node.parameters().get("Mandatory"))) {
            return Optional.empty();
        }
        final String player = node.parameters().getOrDefault("DefinedPlayer", "You");
        if (!Set.of("You", "Opponent").contains(player)) { return Optional.empty(); }
        try {
            final int limit = Integer.parseInt(node.parameters().getOrDefault("ChangeNum", "1"));
            if (limit < 0 || limit > 8) { return Optional.empty(); }
            return library.searchCounts(node.parameters().getOrDefault("ChangeType", "Card"), limit)
                    .map(counts -> new Search("You".equals(player), counts));
        } catch (final NumberFormatException unsupported) {
            return Optional.empty();
        }
    }
}
