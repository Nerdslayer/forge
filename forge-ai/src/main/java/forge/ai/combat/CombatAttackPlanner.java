package forge.ai.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import forge.ai.effect.ValuationDecision;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** Live public alternative capture/validation around the game-free attack search. */
public final class CombatAttackPlanner {
    private CombatAttackPlanner() { }

    public record Plan(Optional<PublicCombatSnapshot> snapshot, Optional<PublicCombatReadiness> readiness,
            Optional<CombatAttackSearch.Result> search, boolean applicable, List<String> reasons,
            Map<Integer, Integer> fixedAttackers) {
        public Plan { reasons = List.copyOf(reasons); fixedAttackers = Map.copyOf(fixedAttackers); }
    }

    private record Capture(PublicCombatSnapshot snapshot, PublicCombatReadiness readiness, Map<Integer, Integer> fixedAttackers) { }

    /** Read-only: hypothetical attacks never publish live combat/card views or consume card IDs. */
    public static Plan plan(final Player ai, final Combat combat, final CombatSearchBudget budget) {
        final List<String> reasons = validateEntry(ai, combat);
        if (!reasons.isEmpty()) { return rejected(reasons); }
        if (budget == null) { throw new IllegalArgumentException("A shared combat search budget is required"); }
        if (budget.remaining() == 0) { return rejected(List.of("Shared search budget exhausted before preparation")); }
        final Capture capture = capture(ai, combat, reasons, budget);
        if (capture == null) { return rejected(List.of("Shared combat budget exhausted during snapshot preparation")); }
        if (!reasons.isEmpty()) {
            return new Plan(Optional.of(capture.snapshot()), Optional.of(capture.readiness()), Optional.empty(), false, reasons, capture.fixedAttackers());
        }
        final var prepared = CombatPlanningPreparation.values(ai, ValuationDecision.ATTACK, budget);
        if (prepared.isEmpty()) {
            return new Plan(Optional.of(capture.snapshot()), Optional.of(capture.readiness()), Optional.empty(), false,
                    List.of("Shared combat budget exhausted during valuation preparation"), capture.fixedAttackers());
        }
        final PreparedCombatValuation values = prepared.orElseThrow();
        final CombatAttackSearch.Result search = CombatAttackSearch.search(capture.snapshot(), values, capture.readiness(), budget,
                capture.fixedAttackers().keySet());
        reasons.addAll(search.reasons());
        // Greedy declaration coverage need not enumerate every subset. Opposing responses must
        // still be complete; an incomplete counterattack must never become "zero threat".
        // TODO: Audit bounded opposing responses before relaxing their separate completeness gate.
        final boolean applicable = search.best().isPresent() && (search.best().orElseThrow().certifiedWin()
                || search.outcomeDomainComplete() && search.candidateSearchComplete());
        if (!applicable) { reasons.add("No sufficiently supported, verified attack/reply result"); }
        return new Plan(Optional.of(capture.snapshot()), Optional.of(capture.readiness()), Optional.of(search), applicable, reasons, capture.fixedAttackers());
    }

    /** A real own declaration only; rejected plans leave all live assignments untouched. */
    public static boolean tryDeclare(final Player ai, final Player attacker, final Combat combat, final CombatSearchBudget budget) {
        if (ai == null || ai != attacker || combat == null || combat != ai.getGame().getPhaseHandler().getCombat()) { return false; }
        if (budget == null) { throw new IllegalArgumentException("A shared combat search budget is required"); }
        final Plan plan = plan(ai, combat, budget.planningAllowance());
        if (!plan.applicable()) {
            CombatDecisionTrace.attacks(ai, plan, false, "Planner is not applicable", budget);
            return false;
        }
        final List<String> stale = validateEntry(ai, combat);
        final Capture fresh = capture(ai, combat, stale, budget);
        if (fresh == null) {
            CombatDecisionTrace.attacks(ai, plan, false, "Combat validation budget exhausted during alternatives/readiness capture", budget);
            return false;
        }
        if (!stale.isEmpty() || !plan.snapshot().orElseThrow().equals(fresh.snapshot())
                || !plan.readiness().orElseThrow().equals(fresh.readiness()) || !plan.fixedAttackers().equals(fresh.fixedAttackers())) {
            CombatDecisionTrace.attacks(ai, plan, false, "Public alternatives/readiness or declarations changed", budget);
            return false;
        }
        final Map<Integer, Card> cards = new LinkedHashMap<>();
        ai.getCardsIn(ZoneType.Battlefield).forEach(card -> cards.put(card.getId(), card));
        final Player defender = ai.getGame().getPlayers().stream()
                .filter(player -> player.getId() == fresh.snapshot().defendingPlayerId()).findFirst().orElseThrow();
        final var selected = plan.search().orElseThrow().best().orElseThrow();
        final Combat detached = new Combat(ai);
        selected.attackers().forEach(id -> detached.addAttackerForValidation(cards.get(id), defender));
        if (!CombatUtil.validateAttackers(detached)) {
            CombatDecisionTrace.attacks(ai, plan, false, "Chosen whole attack declaration is no longer legal", budget);
            return false;
        }
        final List<String> finalReasons = validateEntry(ai, combat);
        final Capture finalState = capture(ai, combat, finalReasons, budget);
        if (finalState == null) {
            CombatDecisionTrace.attacks(ai, plan, false, "Combat validation budget exhausted during final alternatives/readiness capture", budget);
            return false;
        }
        if (!finalReasons.isEmpty() || !fresh.equals(finalState)) {
            CombatDecisionTrace.attacks(ai, plan, false, "Public state changed during final legality validation", budget);
            return false;
        }
        selected.attackers().stream().filter(id -> !plan.fixedAttackers().containsKey(id))
                .forEach(id -> combat.addAttacker(cards.get(id), defender));
        if (ai.getController() instanceof forge.ai.PlayerControllerAi controller) {
            controller.requestCombatExecutionPlan(combat);
        }
        // Actual opposing blocks bind/reprice allocations in the controller's order/damage
        // boundary. Never install the searched hypothetical blocks into live combat.
        CombatDecisionTrace.attacks(ai, plan, true, "Whole attack declaration validated and applied", budget);
        return true;
    }

    private static List<String> validateEntry(final Player ai, final Combat combat) {
        final List<String> reasons = new ArrayList<>();
        if (ai == null || combat == null || combat.getAttackingPlayer() != ai) {
            reasons.add("The observer must be the attacking player in the same game");
            return reasons;
        }
        if (ai.getGame().getPhaseHandler().getPhase() != PhaseType.COMBAT_DECLARE_ATTACKERS) {
            reasons.add("Attack planning requires the declaration phase");
        }
        if (!combat.getAllBlockers().isEmpty()) {
            reasons.add("Attack planning cannot change a declaration after blocks");
        }
        for (final Card card : combat.getAttackers()) {
            if (!(combat.getDefenderByAttacker(card) instanceof Player target) || target == ai) {
                reasons.add("Fixed attacks require an opposing player destination");
            }
        }
        if (ai.getGame().getPlayers().size() != 2) { reasons.add("Only ordinary opposing two-player attacks are supported"); }
        return reasons;
    }

    private static Capture capture(final Player ai, final Combat combat, final List<String> reasons, final CombatSearchBudget budget) {
        final Player defender = ai.getGame().getPlayers().stream().filter(player -> player != ai).findFirst().orElseThrow();
        final Combat all = new Combat(ai);
        final Combat fixed = new Combat(ai);
        final Map<Integer, Integer> fixedIds = new LinkedHashMap<>();
        combat.getAttackers().forEach(card -> {
            if (combat.getDefenderByAttacker(card) == defender) {
                fixed.addAttackerForValidation(card, defender);
                fixedIds.put(card.getId(), defender.getId());
            }
        });
        for (final Card card : ai.getCreaturesInPlay()) {
            if (!budget.tryConsume()) { return null; }
            if (!CombatUtil.canAttack(card, defender)) { continue; }
            if (CombatUtil.getAttackCost(ai.getGame(), card, defender) != null) {
                reasons.add("Attack payment needs explicit planning: " + card.getId());
                continue;
            }
            all.addAttackerForValidation(card, defender);
        }
        if (!CombatUtil.validateAttackers(fixed)) { reasons.add("Incomplete mandatory attacks need a constraint-preserving adapter"); }
        if (!all.getAttackers().stream().map(Card::getId).toList().containsAll(fixedIds.keySet())) {
            reasons.add("A fixed attacker is no longer an admitted public legal alternative");
        }
        final var captured = PublicCombatSnapshot.capture(ai, all, budget::tryConsume);
        if (captured.isEmpty()) { return null; }
        final PublicCombatSnapshot snapshot = captured.orElseThrow();
        final var preparedReadiness = PublicCombatReadiness.capture(ai, snapshot, budget::tryConsume);
        if (preparedReadiness.isEmpty()) { return null; }
        final PublicCombatReadiness readiness = preparedReadiness.orElseThrow();
        reasons.addAll(snapshot.unsupportedReasons());
        reasons.addAll(snapshot.unavailableReasons());
        // Future-only uncertainty must not prevent certifying an immediate actual win.
        // The search reports it when a continuing combat needs a reply forecast.
        // TODO: Defender alternatives/planeswalkers, attack maxima, forced splits and taxes.
        return new Capture(snapshot, readiness, Map.copyOf(fixedIds));
    }

    private static Plan rejected(final List<String> reasons) {
        return new Plan(Optional.empty(), Optional.empty(), Optional.empty(), false, reasons, Map.of());
    }
}
