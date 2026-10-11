package forge.ai.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded hold-and-defend public clock, not a prediction of future spells or guaranteed survival. */
public final class CombatSurvivalHorizonEvaluator {
    private static final int MAX_ATTACKS = 3;

    public record Step(List<Integer> attackers, List<Integer> casualties, int lifeAfter) {
        public Step {
            attackers = List.copyOf(attackers);
            casualties = List.copyOf(casualties);
        }
    }

    public record Result(boolean complete, int attacksSurvived, boolean lethalWithinHorizon,
            List<Step> steps, List<String> reasons) {
        public Result {
            steps = List.copyOf(steps); reasons = List.copyOf(reasons);
            if (attacksSurvived < 0 || attacksSurvived > MAX_ATTACKS || lethalWithinHorizon && attacksSurvived == MAX_ATTACKS) {
                throw new IllegalArgumentException("Invalid bounded survival horizon");
            }
        }
    }

    public record Opportunity(Result horizon, int turnValue) {
        public Opportunity { if (turnValue < 0) { throw new IllegalArgumentException("Nonnegative opportunity value required"); } }
        public int delta(final Opportunity reference) {
            if (!horizon.complete() || !reference.horizon().complete() || turnValue != reference.turnValue) {
                throw new IllegalArgumentException("Complete comparable survival opportunities required");
            }
            if (!horizon.lethalWithinHorizon() && !reference.horizon().lethalWithinHorizon()) { return 0; }
            return turnValue * Math.max(-1, Math.min(1, horizon.attacksSurvived() - reference.horizon().attacksSurvived()));
        }
    }

    private final PublicCombatReadiness readiness;
    private final CombatSearchBudget budget;
    private final Map<CombatSafetyEvaluator.Continuation, Result> cache = new HashMap<>();

    CombatSurvivalHorizonEvaluator(final PublicCombatReadiness readiness0, final CombatSearchBudget budget0) {
        readiness = readiness0;
        budget = budget0;
    }

    Result evaluate(final CombatSafetyEvaluator.Continuation first) {
        return cache.computeIfAbsent(first, this::forecast);
    }

    private Result forecast(final CombatSafetyEvaluator.Continuation first) {
        final List<Step> steps = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();
        CombatSafetyEvaluator.Continuation state = first;
        int survived = 0;
        for (int index = 0; index < MAX_ATTACKS; index++) {
            if (!budget.tryConsume()) { return incomplete(survived, steps, "Survival horizon shared allowance exhausted"); }
            final var projection = state.projection();
            if (!projection.supported() || !projection.available()) {
                return incomplete(survived, steps, "Unsupported survival horizon combat: " + projection.reasons());
            }
            steps.add(new Step(state.snapshot().attackersToDefenders().keySet().stream().sorted().toList(),
                    projection.lostCreatures().stream().sorted().toList(),
                    projection.playerLifeAfter().get(state.snapshot().observingPlayerId())));
            if (projection.terminal() == CombatProjection.Terminal.LOSS) {
                return new Result(true, survived, true, steps, reasons);
            }
            if (projection.terminal() == CombatProjection.Terminal.DRAW) {
                return incomplete(survived, steps, "Draw is not an additional survival opportunity");
            }
            survived++;
            if (projection.terminal() == CombatProjection.Terminal.WIN) {
                return new Result(true, MAX_ATTACKS, false, steps, reasons);
            }
            if (index + 1 == MAX_ATTACKS) { break; }
            // TODO: Intervening plays, offensive tail decisions, extra turns and arbitrary
            // survivor-dependent legality. Plain tail blocks are locally optimal, not multi-turn optimal.
            final var aiTurn = CombatTurnProjectionBuilder.advance(state.snapshot(), projection,
                    readiness.forActivePlayer(state.snapshot().observingPlayerId()), state.values());
            if (aiTurn.turn().isEmpty()) { return incomplete(survived, steps, aiTurn.reasons().toString()); }
            final var turn = aiTurn.turn().orElseThrow();
            final var hold = CombatAttackCandidates.select(turn.alternatives(), List.of());
            final var held = CombatOutcomePredictor.predict(hold, new CombatAssignment(Map.of(), Map.of()));
            final var next = CombatSafetyEvaluator.forecastNextAttack(hold, held,
                    readiness.forActivePlayer(state.snapshot().attackingPlayerId()), turn.values(), budget, null, true);
            if (!next.evaluation().usable() || next.continuation().isEmpty()) {
                return incomplete(survived, steps, "Incomplete survival horizon tail: " + next.evaluation().reasons());
            }
            reasons.addAll(next.evaluation().reasons());
            state = next.continuation().orElseThrow();
        }
        reasons.add("Survival horizon right-censored after three opposing attacks; public hold-and-defend heuristic");
        return new Result(true, survived, false, steps, reasons);
    }

    private static Result incomplete(final int survived, final List<Step> steps, final String reason) {
        return new Result(false, survived, false, steps, List.of(reason));
    }
}
