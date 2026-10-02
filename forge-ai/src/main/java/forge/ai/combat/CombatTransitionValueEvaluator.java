package forge.ai.combat;

import java.util.ArrayList;
import java.util.List;

import forge.ai.PlayerResourceValueEvaluator;
import forge.ai.effect.ValuationCompleteness;

/** Composes frozen permanent loss and nonlinear life utility with explicit terminal priority. */
public final class CombatTransitionValueEvaluator {
    private CombatTransitionValueEvaluator() { }

    public record Score(PreparedCombatValuation.LossValue permanentLoss, int lifeUtility, int outcomeUtility,
            int terminalValue, CombatProjection.Terminal terminal,
            ValuationCompleteness completeness, List<String> reasons) {
        public Score { reasons = List.copyOf(reasons); }
        public int total() { return CombatOutcomePredictor.add(CombatOutcomePredictor.add(
                CombatOutcomePredictor.add(permanentLoss.total(), lifeUtility), outcomeUtility), terminalValue); }
    }

    /** Positive means left is preferable for the observing AI, independently of assigning actor. */
    public static int compare(final Score left, final Score right) {
        final int terminal = Integer.compare(terminalRank(left.terminal()), terminalRank(right.terminal()));
        return terminal != 0 ? terminal : Integer.compare(left.total(), right.total());
    }

    private static int terminalRank(final CombatProjection.Terminal terminal) {
        return switch (terminal) {
            case WIN -> 3;
            case NONE -> 2;
            case DRAW -> 1;
            case LOSS -> 0;
        };
    }

    public static Score evaluate(final PublicCombatSnapshot snapshot, final PreparedCombatValuation values,
            final CombatProjection projection) {
        if (!projection.supported() || !projection.available()) {
            throw new IllegalArgumentException("A supported, available combat projection is required");
        }
        if (!projection.outcomes().supported()) { throw new IllegalArgumentException("Incomplete outcomes cannot be scored"); }
        final PreparedCombatValuation.LossValue casualties = values.retireOpportunities(projection.outcomes().resolutions())
                .evaluateLosses(projection.lostCreatures());
        final PreparedCombatValuation.LossValue loss = new PreparedCombatValuation.LossValue(
                CombatOutcomePredictor.add(casualties.body(), values.survivorBodyUtility(snapshot, projection)),
                casualties.unknownAbility(), casualties.futureAbility(), casualties.relationships(), casualties.completeness(), casualties.reasons());
        int lifeUtility = 0;
        for (final var entry : snapshot.players().entrySet()) {
            final int utility = PlayerResourceValueEvaluator.evaluateLifeChange(entry.getValue().life(),
                    projection.playerLifeAfter().get(entry.getKey()));
            lifeUtility = CombatOutcomePredictor.add(lifeUtility,
                    entry.getKey() == snapshot.observingPlayerId() ? utility : -utility);
        }
        final int terminal = switch (projection.terminal()) {
            case WIN -> PlayerResourceValueEvaluator.TERMINAL_GAME_VALUE;
            case LOSS -> -PlayerResourceValueEvaluator.TERMINAL_GAME_VALUE;
            default -> 0;
        };
        final List<String> reasons = new ArrayList<>(loss.reasons());
        reasons.addAll(projection.reasons());
        reasons.add("Broader static layers and concrete outcome/event attribution remain");
        // TODO: Survivor changes beyond fixed additive P/T/shields and broader immediate outcome ownership. Tactical future
        // adjustments are intentionally composed by the search, not embedded in raw transitions.
        // Terminal is a separate ordering class: do not rely on total points to prioritize wins.
        return new Score(loss, lifeUtility, projection.outcomes().utility(), terminal, projection.terminal(), ValuationCompleteness.PARTIAL, reasons);
    }
}
