package forge.ai.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
            Map<Integer, Integer> fixedAttackers, Map<Integer, Set<Integer>> legalDestinations) {
        public Plan { reasons = List.copyOf(reasons); fixedAttackers = Map.copyOf(fixedAttackers); legalDestinations = Map.copyOf(legalDestinations); }
    }

    private record Capture(PublicCombatSnapshot snapshot, PublicCombatReadiness readiness, Map<Integer, Integer> fixedAttackers,
            Map<Integer, Set<Integer>> legalDestinations) { }

    /** Read-only: hypothetical attacks never publish live combat/card views or consume card IDs. */
    public static Plan plan(final Player ai, final Combat combat, final CombatSearchBudget budget) {
        final List<String> reasons = validateEntry(ai, combat);
        if (!reasons.isEmpty()) { return rejected(reasons); }
        if (budget == null) { throw new IllegalArgumentException("A shared combat search budget is required"); }
        if (budget.remaining() == 0) { return rejected(List.of("Shared search budget exhausted before preparation")); }
        final Capture capture = capture(ai, combat, reasons, budget);
        if (capture == null) { return rejected(List.of("Shared combat budget exhausted during snapshot preparation")); }
        if (!reasons.isEmpty()) {
            return new Plan(Optional.of(capture.snapshot()), Optional.of(capture.readiness()), Optional.empty(), false, reasons, capture.fixedAttackers(), capture.legalDestinations());
        }
        final var prepared = CombatPlanningPreparation.values(ai, ValuationDecision.ATTACK, budget);
        if (prepared.isEmpty()) {
            return new Plan(Optional.of(capture.snapshot()), Optional.of(capture.readiness()), Optional.empty(), false,
                    List.of("Shared combat budget exhausted during valuation preparation"), capture.fixedAttackers(), capture.legalDestinations());
        }
        final PreparedCombatValuation values = prepared.orElseThrow();
        final boolean destinations = forge.ai.AiProfileUtil.getBoolProperty(ai, forge.ai.AiProps.ENABLE_COMBAT_ATTACK_DESTINATION_PLANNING)
                && capture.snapshot().planeswalkers().values().stream().anyMatch(walker -> walker.controllerId() == capture.snapshot().defendingPlayerId());
        final CombatAttackSearch.Result search = destinations
                ? CombatAttackDestinationSearch.search(capture.snapshot(), values, capture.readiness(), budget,
                        capture.fixedAttackers(), capture.legalDestinations())
                : CombatAttackSearch.search(capture.snapshot(), values, capture.readiness(), budget, capture.fixedAttackers().keySet());
        reasons.addAll(search.reasons());
        // Greedy declaration coverage need not enumerate every subset. Opposing responses must
        // still be complete; an incomplete counterattack must never become "zero threat".
        // TODO: Audit bounded opposing responses before relaxing their separate completeness gate.
        final boolean applicable = search.best().isPresent() && (search.best().orElseThrow().certifiedWin()
                || search.outcomeDomainComplete() && search.candidateSearchComplete());
        if (!applicable) { reasons.add("No sufficiently supported, verified attack/reply result"); }
        return new Plan(Optional.of(capture.snapshot()), Optional.of(capture.readiness()), Optional.of(search), applicable, reasons, capture.fixedAttackers(), capture.legalDestinations());
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
                || !plan.readiness().orElseThrow().equals(fresh.readiness()) || !plan.fixedAttackers().equals(fresh.fixedAttackers())
                || !plan.legalDestinations().equals(fresh.legalDestinations())) {
            CombatDecisionTrace.attacks(ai, plan, false, "Public alternatives/readiness or declarations changed", budget);
            return false;
        }
        final Map<Integer, Card> cards = new LinkedHashMap<>();
        ai.getCardsIn(ZoneType.Battlefield).forEach(card -> cards.put(card.getId(), card));
        final Player defender = ai.getGame().getPlayers().stream()
                .filter(player -> player.getId() == fresh.snapshot().defendingPlayerId()).findFirst().orElseThrow();
        final var selected = plan.search().orElseThrow().best().orElseThrow();
        final Combat detached = new Combat(ai);
        for (final var entry : selected.destinations().entrySet()) {
            final var target = resolveDestination(ai, defender, entry.getValue());
            if (target == null) {
                CombatDecisionTrace.attacks(ai, plan, false, "Chosen destination no longer exists", budget);
                return false;
            }
            detached.addAttackerForValidation(cards.get(entry.getKey()), target);
        }
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
                .forEach(id -> combat.addAttacker(cards.get(id), resolveDestination(ai, defender, selected.destinations().get(id))));
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
            final var target = combat.getDefenderByAttacker(card);
            final boolean supportedWalker = forge.ai.AiProfileUtil.getBoolProperty(ai, forge.ai.AiProps.ENABLE_COMBAT_ATTACK_DESTINATION_PLANNING)
                    && target instanceof Card walker && walker.isPlaneswalker() && !walker.isCreature()
                    && walker.getController().isOpponentOf(ai);
            if (!(target instanceof Player player && player.isOpponentOf(ai)) && !supportedWalker) {
                reasons.add("Fixed attacks require an opposing supported destination");
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
        final Map<Integer, Set<Integer>> legal = new LinkedHashMap<>();
        final boolean enabled = forge.ai.AiProfileUtil.getBoolProperty(ai, forge.ai.AiProps.ENABLE_COMBAT_ATTACK_DESTINATION_PLANNING);
        final List<forge.game.GameEntity> targets = new ArrayList<>(List.of(defender));
        if (enabled) { targets.addAll(defender.getPlaneswalkersInPlay()); }
        combat.getAttackers().forEach(card -> {
            final var target = combat.getDefenderByAttacker(card);
            fixed.addAttackerForValidation(card, target);
            fixedIds.put(card.getId(), destination(target).key());
        });
        for (final Card card : ai.getCreaturesInPlay()) {
            if (!budget.tryConsume()) { return null; }
            final Set<Integer> options = new java.util.LinkedHashSet<>();
            for (final var target : targets) {
                if (targets.size() > 1 && !budget.tryConsume()) { return null; }
                if (target instanceof Card walker && walker.isCreature()) {
                    reasons.add("Creature/planeswalker destination needs explicit mechanics: " + walker.getId());
                    continue;
                }
                if (!CombatUtil.canAttack(card, target)) { continue; }
                if (CombatUtil.getAttackCost(ai.getGame(), card, target) != null) {
                    reasons.add("Attack payment needs explicit planning: " + card.getId());
                    continue;
                }
                options.add(destination(target).key());
            }
            if (options.isEmpty()) { continue; }
            legal.put(card.getId(), Set.copyOf(options));
            final int initial = fixedIds.getOrDefault(card.getId(), options.iterator().next());
            all.addAttackerForValidation(card, resolveDestination(ai, defender, CombatAttackDestination.fromKey(initial)));
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
        // TODO: Battles/multiplayer destinations, attack maxima, forced splits and taxes.
        return new Capture(snapshot, readiness, Map.copyOf(fixedIds), Map.copyOf(legal));
    }

    private static CombatAttackDestination destination(final forge.game.GameEntity target) {
        return new CombatAttackDestination(target instanceof Player ? CombatAttackDestination.Kind.PLAYER
                : CombatAttackDestination.Kind.PLANESWALKER, target.getId());
    }

    private static forge.game.GameEntity resolveDestination(final Player ai, final Player defender,
            final CombatAttackDestination destination) {
        if (destination.kind() == CombatAttackDestination.Kind.PLAYER) {
            return destination.id() == defender.getId() ? defender : null;
        }
        return ai.getGame().getCardsIn(ZoneType.Battlefield).stream().filter(card -> card.getId() == destination.id()
                && card.isPlaneswalker() && !card.isCreature() && card.getController() == defender).findFirst().orElse(null);
    }

    private static Plan rejected(final List<String> reasons) {
        return new Plan(Optional.empty(), Optional.empty(), Optional.empty(), false, reasons, Map.of(), Map.of());
    }
}
