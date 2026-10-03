package forge.ai.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import forge.ai.effect.ValuationDecision;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;

/** Binds real opposing blocks to an execution plan, never installs hypothetical declarations. */
public final class CombatDamagePlanner {
    private CombatDamagePlanner() { }

    public record Preparation(Optional<CombatExecutionPlan> execution, Optional<CombatDamageOptimizer.Result> search,
            List<String> reasons, Optional<CombatTwoTurnPressureEvaluator.Forecast> twoTurnPressure) {
        public Preparation { reasons = List.copyOf(reasons); }
    }

    public static Optional<CombatExecutionPlan> prepare(final Player observer, final Combat combat,
            final boolean chooseOrders, final CombatSearchBudget budget) {
        return plan(observer, combat, chooseOrders, budget).execution();
    }

    public static Preparation plan(final Player observer, final Combat combat,
            final boolean chooseOrders, final CombatSearchBudget budget) {
        if (observer == null || combat == null || combat.getAttackingPlayer() != observer
                || observer.getGame().getPhaseHandler().getCombat() != combat || budget == null || budget.remaining() == 0) {
            return rejected("Real own combat and remaining preparation budget required");
        }
        final PhaseType phase = observer.getGame().getPhaseHandler().getPhase();
        if (chooseOrders ? phase != PhaseType.COMBAT_DECLARE_BLOCKERS
                : phase != PhaseType.COMBAT_FIRST_STRIKE_DAMAGE && phase != PhaseType.COMBAT_DAMAGE) {
            return rejected("Execution preparation requires the correct order/damage phase");
        }
        final var captured = PublicCombatSnapshot.captureForExecution(observer, combat, budget::tryConsume);
        if (captured.isEmpty()) { return rejected("Shared combat budget exhausted during snapshot preparation"); }
        final PublicCombatSnapshot snapshot = captured.orElseThrow();
        final List<String> reasons = new ArrayList<>(snapshot.unsupportedReasons());
        reasons.addAll(snapshot.unavailableReasons());
        if (!reasons.isEmpty()) { return new Preparation(Optional.empty(), Optional.empty(), reasons, Optional.empty()); }
        if (!chooseOrders && phase == PhaseType.COMBAT_DAMAGE
                && snapshot.creatures().values().stream().anyMatch(card -> card.firstStrike() || card.doubleStrike())) {
            // TODO: Reconstruct/replan the regular-step boundary when no first-step callback
            // installed a plan. Never project already-resolved first-strike damage a second time.
            return rejected("Regular damage replan requires an audited post-first-strike boundary");
        }
        final Player defender = observer.getGame().getPlayers().stream()
                .filter(player -> player.getId() == snapshot.defendingPlayerId()).findFirst().orElse(null);
        if (defender == null || CombatUtil.validateBlocks(combat, defender) != null) {
            return rejected("Actual whole blocking declaration is not valid in the supported domain");
        }
        final Map<Integer, List<Integer>> blocks = new LinkedHashMap<>();
        combat.getAttackers().forEach(card -> {
            final List<Integer> ids = combat.getBlockers(card).stream().map(blocker -> blocker.getId()).toList();
            if (!ids.isEmpty()) { blocks.put(card.getId(), ids); }
        });
        if (!chooseOrders && snapshot.legacyDamageOrder() && blocks.values().stream().anyMatch(group -> group.size() > 1)) {
            // TODO: Read frozen engine orders for a damage-time replan. These are already
            // committed; do not silently select or assume a new order after declaration.
            return rejected("Committed legacy blocker orders need an execution-time order adapter");
        }
        final CombatAssignment assignment = new CombatAssignment(snapshot.attackersToDefenders(), blocks);
        final var prepared = CombatPlanningPreparation.values(observer, ValuationDecision.ATTACK, budget);
        if (prepared.isEmpty()) { return rejected("Shared combat budget exhausted during valuation preparation"); }
        final PreparedCombatValuation values = prepared.orElseThrow();
        final var preparedReadiness = PublicCombatReadiness.capture(observer, snapshot, budget::tryConsume);
        if (preparedReadiness.isEmpty()) { return rejected("Shared combat budget exhausted during readiness preparation"); }
        final PublicCombatReadiness readiness = preparedReadiness.orElseThrow();
        final var forecasts = new CombatAttackForecasts(snapshot, readiness, values, budget);
        final boolean[] supported = {true};
        final java.util.function.Function<CombatProjection, CombatSafetyEvaluator.FollowUp> forecast = state -> {
            if (state.terminal() != CombatProjection.Terminal.NONE) { return null; }
            final var reply = forecasts.reply(state);
            supported[0] &= reply.usable();
            reasons.addAll(reply.reasons());
            return reply;
        };
        final CombatDamageOptimizer.Result result = CombatDamageOptimizer.optimizeBlockGroups(snapshot, assignment, values, budget,
                state -> {
                    final var reply = forecast.apply(state);
                    if (reply == null || !reply.usable()) { return 0; }
                    final var future = forecasts.pressure(state);
                    supported[0] &= future.usable();
                    reasons.addAll(future.reasons());
                    return CombatOutcomePredictor.add(CombatSafetyEvaluator.discountedFollowUpValue(reply.nonterminalUtility()), future.value());
                }, (left, right) -> {
                    final var a = forecast.apply(left);
                    final var b = forecast.apply(right);
                    return a == null || b == null ? 0 : Boolean.compare(b.lethalOpportunity(), a.lethalOpportunity());
                });
        reasons.addAll(result.reasons());
        final boolean certifiedWin = snapshot.ignoredEffects().isEmpty() && result.outcomeSupported() && result.exhaustive() && result.best().isPresent()
                && result.best().orElseThrow().projection().terminal() == CombatProjection.Terminal.WIN;
        if (!result.outcomeSupported() || !result.exhaustive() || !supported[0] && !certifiedWin || result.best().isEmpty()) {
            reasons.add("No sufficiently supported complete execution allocation/reply result");
            return new Preparation(Optional.empty(), Optional.of(result), reasons.stream().distinct().toList(), Optional.empty());
        }
        final var chosen = result.best().orElseThrow();
        final var execution = CombatExecutionPlan.create(observer, combat, snapshot, chosen.assignment(), chosen.damagePlan());
        if (execution.isEmpty()) { reasons.add("Actual combat state failed execution-plan validation"); }
        return new Preparation(execution, Optional.of(result), reasons.stream().distinct().toList(),
                forecasts.cachedPressure(chosen.projection()));
    }

    private static Preparation rejected(final String reason) {
        return new Preparation(Optional.empty(), Optional.empty(), List.of(reason), Optional.empty());
    }
}
