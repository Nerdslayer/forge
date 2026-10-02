package forge.ai.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import forge.ai.PlayerControllerAi;
import forge.ai.effect.ValuationDecision;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;

/** Live boundary around public preparation and game-free block search. */
public final class CombatBlockPlanner {
    private CombatBlockPlanner() { }

    public record Plan(Optional<PublicCombatSnapshot> snapshot, Optional<CombatBlockSearch.Result> search,
            boolean applicable, List<String> reasons, Map<Integer, List<Integer>> fixedBlocks) {
        public Plan {
            reasons = List.copyOf(reasons);
            fixedBlocks = new CombatAssignment(Map.of(), fixedBlocks).blockersByAttacker();
        }
    }

    /** Read-only: creates no execution cache and never modifies a declaration or live views. */
    public static Plan plan(final Player ai, final Combat combat, final CombatSearchBudget budget) {
        if (ai == null || combat == null || ai.getGame() != combat.getAttackingPlayer().getGame()) {
            return rejected("A combat and observer in the same game are required");
        }
        if (budget == null) { throw new IllegalArgumentException("A shared combat search budget is required"); }
        final var captured = PublicCombatSnapshot.capture(ai, combat, budget::tryConsume);
        if (captured.isEmpty()) { return rejected("Shared combat budget exhausted during snapshot preparation"); }
        final PublicCombatSnapshot snapshot = captured.orElseThrow();
        final Map<Integer, List<Integer>> fixed = fixedBlocks(combat);
        final List<String> reasons = new ArrayList<>(snapshot.unsupportedReasons());
        reasons.addAll(snapshot.unavailableReasons());
        if (snapshot.defendingPlayerId() != ai.getId()) { reasons.add("This entry point plans only the observer's own blocks"); }
        if (!reasons.isEmpty()) { return new Plan(Optional.of(snapshot), Optional.empty(), false, reasons, fixed); }
        if (budget.remaining() == 0) {
            return new Plan(Optional.of(snapshot), Optional.empty(), false, List.of("Shared search budget exhausted before preparation"), fixed);
        }
        final var prepared = CombatPlanningPreparation.values(ai, ValuationDecision.BLOCK, budget);
        if (prepared.isEmpty()) {
            return new Plan(Optional.of(snapshot), Optional.empty(), false,
                    List.of("Shared combat budget exhausted during valuation preparation"), fixed);
        }
        final PreparedCombatValuation values = prepared.orElseThrow();
        final var readiness = PublicCombatReadiness.capture(ai, snapshot, budget::tryConsume);
        if (readiness.isEmpty()) {
            return new Plan(Optional.of(snapshot), Optional.empty(), false,
                    List.of("Shared combat budget exhausted during readiness preparation"), fixed);
        }
        final CombatBlockSearch.Result result = CombatBlockSearch.searchWithPressure(snapshot, values, budget,
                readiness.orElseThrow(), fixed);
        reasons.addAll(result.reasons());
        final boolean applicable = result.outcomeDomainComplete() && result.best().isPresent()
                && (result.searchExhaustive() || result.best().orElseThrow().projection().terminal() != CombatProjection.Terminal.LOSS);
        if (!applicable) { reasons.add("No sufficiently supported, verified combat result"); }
        // TODO: Support global mandatory constraints and multiplayer/planeswalker blocks, current static recipient
        // changes and concrete event ownership. Broader reply/pressure support is still pending;
        // cancellation inside one complex ability evaluation remains unsupported.
        return new Plan(Optional.of(snapshot), Optional.of(result), applicable, reasons, fixed);
    }

    /** Actual declaration only. Returns false without changing live assignments on rejection. */
    public static boolean tryDeclare(final Player ai, final Player defender, final Combat combat, final CombatSearchBudget budget) {
        if (ai == null || combat == null || ai != defender || combat != ai.getGame().getPhaseHandler().getCombat()
                || ai.getGame().getPhaseHandler().getPhase() != PhaseType.COMBAT_DECLARE_BLOCKERS) { return false; }
        if (budget == null) { throw new IllegalArgumentException("A shared combat search budget is required"); }
        final Plan plan = plan(ai, combat, budget.planningAllowance());
        String reason = "Planner is not applicable";
        if (plan.applicable()) {
            final CombatBlockSearch.Candidate candidate = plan.search().orElseThrow().best().orElseThrow();
            final PublicCombatSnapshot snapshot = plan.snapshot().orElseThrow();
            final String initialValidation = validateSnapshot(ai, combat, plan, snapshot, budget);
            if (initialValidation != null) {
                CombatDecisionTrace.blocks(ai, plan, false, initialValidation, budget);
                return false;
            }
            final Map<Integer, Card> cards = new LinkedHashMap<>();
            ai.getGame().getCardsIn(forge.game.zone.ZoneType.Battlefield).forEach(card -> cards.put(card.getId(), card));
            final Combat detached = new Combat(combat.getAttackingPlayer());
            combat.getAttackers().forEach(card -> detached.addAttackerForValidation(card, combat.getDefenderByAttacker(card)));
            candidate.assignment().blockersByAttacker().forEach((attacker, blockers) ->
                    blockers.forEach(blocker -> detached.addBlockerForValidation(cards.get(attacker), cards.get(blocker))));
            reason = CombatUtil.validateBlocks(detached, defender);
            if (reason == null) { reason = validateSnapshot(ai, combat, plan, snapshot, budget); }
            if (reason == null) {
                candidate.assignment().blockersByAttacker().forEach((attacker, blockers) ->
                        blockers.stream().filter(blocker -> !plan.fixedBlocks().getOrDefault(attacker, List.of()).contains(blocker))
                                .forEach(blocker -> combat.addBlocker(cards.get(attacker), cards.get(blocker))));
                if (candidate.damagePlan().isPresent() && combat.getAttackingPlayer().getController() instanceof PlayerControllerAi controller) {
                    final var execution = CombatExecutionPlan.create(ai, combat, snapshot, candidate.assignment(), candidate.damagePlan().orElseThrow());
                    execution.ifPresent(controller::installCombatExecutionPlan);
                }
                CombatDecisionTrace.blocks(ai, plan, true, "Whole assignment validated and applied", budget);
                return true;
            }
        }
        CombatDecisionTrace.blocks(ai, plan, false, reason, budget);
        return false;
    }

    private static String validateSnapshot(final Player ai, final Combat combat, final Plan plan,
            final PublicCombatSnapshot snapshot, final CombatSearchBudget budget) {
        if (!plan.fixedBlocks().equals(fixedBlocks(combat))) { return "Fixed block declarations changed before validation"; }
        final var captured = PublicCombatSnapshot.capture(ai, combat, budget::tryConsume);
        if (captured.isEmpty()) { return "Combat validation budget exhausted during snapshot capture"; }
        return snapshot.equals(captured.orElseThrow()) ? null : "Public combat snapshot changed before application";
    }

    private static Plan rejected(final String reason) {
        return new Plan(Optional.empty(), Optional.empty(), false, List.of(reason), Map.of());
    }

    private static Map<Integer, List<Integer>> fixedBlocks(final Combat combat) {
        final Map<Integer, List<Integer>> blocks = new LinkedHashMap<>();
        combat.getAttackers().forEach(attacker -> {
            final List<Integer> group = combat.getBlockers(attacker).stream().map(Card::getId).sorted().toList();
            if (!group.isEmpty()) { blocks.put(attacker.getId(), group); }
        });
        return Map.copyOf(blocks);
    }
}
