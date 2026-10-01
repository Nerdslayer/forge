package forge.ai.combat;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** Execution-only bridge: retains a live combat identity, never used by projection/search. */
public final class CombatExecutionPlan {
    private final Combat combat;
    private final Player observer;
    private final PublicCombatSnapshot before;
    private final CombatAssignment assignment;
    private final CombatDamagePlan damagePlan;
    private final CombatProjection afterFirstStrike;
    private record Identity(long timestamp, String publicDefinition) { }
    private final Map<Integer, Identity> publicIdentities;
    private boolean invalid;

    private CombatExecutionPlan(final Player observer0, final Combat combat0, final PublicCombatSnapshot snapshot,
            final CombatAssignment assignment0, final CombatDamagePlan damagePlan0, final CombatProjection first) {
        observer = observer0;
        combat = combat0;
        before = snapshot;
        assignment = assignment0;
        damagePlan = damagePlan0;
        afterFirstStrike = first;
        publicIdentities = identities(Set.of());
    }

    /** Install only after the selected block assignment has actually been applied and validated. */
    public static Optional<CombatExecutionPlan> create(final Player observer, final Combat combat,
            final PublicCombatSnapshot snapshot, final CombatAssignment assignment, final CombatDamagePlan plan) {
        if (observer == null || combat == null || snapshot.observingPlayerId() != observer.getId()
                || observer.getGame() != combat.getAttackingPlayer().getGame()) { return Optional.empty(); }
        final CombatProjection first = CombatOutcomePredictor.predictFirstStrike(snapshot, assignment, plan);
        final CombatProjection complete = CombatOutcomePredictor.predict(snapshot, assignment, plan);
        if (!first.supported() || !first.available() || !complete.supported() || !complete.available()) { return Optional.empty(); }
        final CombatExecutionPlan execution = new CombatExecutionPlan(observer, combat, snapshot, assignment, plan, first);
        return execution.validate(false) ? Optional.of(execution) : Optional.empty();
    }

    public Optional<CardCollection> orderBlockers(final Card attacker, final CardCollectionView blockers) {
        if (!validate(false) || !sourceMatches(attacker) || !assignment.attackersToDefenders().containsKey(attacker.getId())) { return Optional.empty(); }
        final List<Integer> ordered = assignment.blockersByAttacker().getOrDefault(attacker.getId(), List.of());
        final Map<Integer, Card> cards = new HashMap<>();
        for (final Card card : blockers) {
            if (!identityMatches(card)) { invalidate(); return Optional.empty(); }
            cards.put(card.getId(), card);
        }
        if (cards.size() != blockers.size() || !cards.keySet().equals(Set.copyOf(ordered))) { invalidate(); return Optional.empty(); }
        final CardCollection result = new CardCollection();
        ordered.forEach(id -> result.add(cards.get(id)));
        return Optional.of(result);
    }

    /** Engine maps use null for the defending player; never copy them with Map.copyOf. */
    public Optional<Map<Card, Integer>> assignDamage(final Card attacker, final CardCollectionView blockers,
            final int damage, final GameEntity defender, final boolean overrideOrder) {
        final PhaseType phase = observer.getGame().getPhaseHandler().getPhase();
        final boolean first = phase == PhaseType.COMBAT_FIRST_STRIKE_DAMAGE;
        if (!first && phase != PhaseType.COMBAT_DAMAGE) { invalidate(); return Optional.empty(); }
        if (!validate(!first) || !sourceMatches(attacker)
                || !(defender instanceof Player player) || player.getId() != before.defendingPlayerId()
                || !assignment.attackersToDefenders().containsKey(attacker.getId())
                || before.legacyDamageOrder() && overrideOrder) { invalidate(); return Optional.empty(); }
        final List<Integer> expected = assignment.blockersByAttacker().getOrDefault(attacker.getId(), List.of()).stream()
                .filter(id -> first || !afterFirstStrike.lostCreatures().contains(id)).toList();
        final List<Integer> provided = java.util.stream.StreamSupport.stream(blockers.spliterator(), false).map(Card::getId).toList();
        for (final Card card : blockers) {
            if (!identityMatches(card)) { invalidate(); return Optional.empty(); }
        }
        if (!Set.copyOf(provided).equals(Set.copyOf(expected)) || provided.size() != expected.size()
                || before.legacyDamageOrder() && !provided.equals(expected)) { invalidate(); return Optional.empty(); }
        CombatDamageAllocation.Allocation allocation = damagePlan.step(first).get(attacker.getId());
        if (allocation == null && expected.isEmpty()) {
            // No allocation branch is searched when first strike removed every blocker. A
            // blocked trampler still deals its full regular damage to the defending player.
            final boolean playerDamage = assignment.blockersByAttacker().getOrDefault(attacker.getId(), List.of()).isEmpty()
                    || before.creatures().get(attacker.getId()).trample();
            allocation = new CombatDamageAllocation.Allocation(Map.of(), playerDamage ? damage : 0);
        }
        if (allocation == null) { invalidate(); return Optional.empty(); }
        final boolean deathtouch = before.creatures().get(attacker.getId()).deathtouch();
        final Set<Integer> priorLosses = first ? Set.of() : afterFirstStrike.lostCreatures();
        final List<CombatDamageAllocation.Target> targets = expected.stream().map(id -> new CombatDamageAllocation.Target(id,
                deathtouch ? 1 : Math.max(0, before.creatureAfterLosses(id, priorLosses).toughness() - (first
                        ? before.creatures().get(id).markedDamage() : afterFirstStrike.survivors().get(id).markedDamage())))).toList();
        if (damage != before.creatureAfterLosses(attacker.getId(), priorLosses).combatDamage()
                || !CombatDamageAllocation.isLegal(damage, targets, before.creatures().get(attacker.getId()).trample()
                        || assignment.blockersByAttacker().getOrDefault(attacker.getId(), List.of()).isEmpty(),
                        before.legacyDamageOrder(), allocation)) { invalidate(); return Optional.empty(); }
        final Map<Card, Integer> result = new LinkedHashMap<>();
        for (final Card blocker : blockers) {
            final int amount = allocation.creatureDamage().getOrDefault(blocker.getId(), 0);
            if (amount > 0) { result.put(blocker, amount); }
        }
        if (allocation.defenderDamage() > 0) { result.put(null, allocation.defenderDamage()); }
        return Optional.of(Collections.unmodifiableMap(result));
    }

    public boolean isInvalid() { return invalid; }

    /** Only the attacking player's controller can own these ordinary damage choices. */
    public boolean isFor(final Player player) {
        return !invalid && combat.getAttackingPlayer() == player && observer.getGame() == player.getGame()
                && player.getGame().getPhaseHandler().getCombat() == combat;
    }

    private void invalidate() { invalid = true; }

    private boolean validate(final boolean regular) {
        if (invalid || observer.getGame().getPhaseHandler().getCombat() != combat) { invalidate(); return false; }
        final PhaseType phase = observer.getGame().getPhaseHandler().getPhase();
        if (phase != PhaseType.COMBAT_DECLARE_BLOCKERS && phase != PhaseType.COMBAT_FIRST_STRIKE_DAMAGE
                && phase != PhaseType.COMBAT_DAMAGE || regular && afterFirstStrike.terminal() != CombatProjection.Terminal.NONE) {
            invalidate(); return false;
        }
        final Set<Integer> lost = regular ? afterFirstStrike.lostCreatures() : Set.of();
        // Later callbacks belong to a new live boundary, not the earlier search's expired clock.
        final CombatSearchBudget budget = CombatPlanningPreparation.budget(observer);
        final var captured = PublicCombatSnapshot.captureForExecution(observer, combat, budget::tryConsume);
        if (captured.isEmpty()) { invalidate(); return false; }
        final PublicCombatSnapshot current = captured.orElseThrow();
        final Map<Integer, Identity> expectedIdentities = new HashMap<>(publicIdentities);
        lost.forEach(expectedIdentities::remove);
        final PreparedCombatStaticWorlds expectedStatics = before.staticWorlds().surviving(lost);
        if (!current.unsupportedReasons().isEmpty() || !current.unavailableReasons().isEmpty()
                || current.legacyDamageOrder() != before.legacyDamageOrder()
                || !before.triggers().isEmpty() && !current.resources().equals(regular
                        ? afterFirstStrike.outcomes().resourcesAfter() : before.resources())
                || !current.triggers().equals(before.triggers().stream().filter(trigger -> !lost.contains(trigger.ability().sourceId())).toList())
                || !current.preventionRules().equals(before.preventionRules().stream().filter(rule -> !lost.contains(rule.providerId())).toList())
                || !current.staticWorlds().providers().equals(expectedStatics.providers())
                || !expectedStatics.providers().isEmpty() && !current.staticWorlds().equals(expectedStatics)
                || !expectedIdentities.equals(identities(lost, budget::tryConsume))
                || current.creatures().size() != before.creatures().size() - lost.size()) { invalidate(); return false; }
        for (final var entry : before.players().entrySet()) {
            if (!budget.tryConsume()) { invalidate(); return false; }
            final int life = regular ? afterFirstStrike.playerLifeAfter().get(entry.getKey()) : entry.getValue().life();
            final PublicCombatSnapshot.LifeState expected = new PublicCombatSnapshot.LifeState(life, entry.getValue().canLoseLife(),
                    entry.getValue().canGainLife(), entry.getValue().cannotLoseAtZero(), entry.getValue().cannotWin());
            if (!expected.equals(current.players().get(entry.getKey()))) { invalidate(); return false; }
        }
        for (final var entry : before.creatures().entrySet()) {
            if (!budget.tryConsume()) { invalidate(); return false; }
            if (lost.contains(entry.getKey())) { continue; }
            final PublicCombatSnapshot.Creature old = before.creatureAfterLosses(entry.getKey(), lost);
            final PublicCombatSnapshot.Creature now = current.creatures().get(entry.getKey());
            if (now == null) { invalidate(); return false; }
            final int marked = regular ? afterFirstStrike.survivors().get(entry.getKey()).markedDamage() : old.markedDamage();
            final boolean deathtouch = regular ? afterFirstStrike.survivors().get(entry.getKey()).markedDeathtouch() : old.markedDeathtouch();
            // Declaration taps attackers outside the planner. Their fixed declaration is validated
            // below; ignore only this ordinary tap transition, not blocker tapping or other stats.
            final boolean tapped = assignment.attackersToDefenders().containsKey(entry.getKey()) ? now.tapped() : old.tapped();
            final PublicCombatSnapshot.Creature expected = new PublicCombatSnapshot.Creature(old.id(), old.controllerId(), old.combatDamage(),
                    old.toughness(), marked, deathtouch, old.firstStrike(), old.doubleStrike(), old.deathtouch(), old.indestructible(),
                    old.trample(), old.lifelink(), old.vigilance(), tapped, old.minimumBlockers(), old.maximumBlockers(), old.stunCounters(),
                    regular ? afterFirstStrike.survivors().get(entry.getKey()).shieldCounters() : old.shieldCounters());
            if (!expected.equals(now)) { invalidate(); return false; }
        }
        final Map<Integer, Integer> attacks = new HashMap<>(assignment.attackersToDefenders());
        lost.forEach(attacks::remove);
        if (!attacks.equals(current.attackersToDefenders())) { invalidate(); return false; }
        for (final Card attacker : combat.getAttackers()) {
            if (!budget.tryConsume()) { invalidate(); return false; }
            final List<Integer> original = assignment.blockersByAttacker().getOrDefault(attacker.getId(), List.of());
            final Set<Integer> expected = original.stream().filter(id -> !lost.contains(id)).collect(Collectors.toSet());
            final Set<Integer> actual = combat.getBlockers(attacker).stream().map(Card::getId).collect(Collectors.toSet());
            if (!expected.equals(actual) || regular && !original.isEmpty() && !combat.isBlocked(attacker)) { invalidate(); return false; }
        }
        // TODO: Recompute safely when state changes instead of falling back; preserve richer
        // prevention, projected triggers/statics, multi-blockers and alternative assignments.
        if (!budget.tryConsume()) { invalidate(); return false; }
        return true;
    }

    private boolean sourceMatches(final Card card) {
        return identityMatches(card) && card.getController() == combat.getAttackingPlayer();
    }

    private boolean identityMatches(final Card card) {
        final Identity identity = publicIdentities.get(card.getId());
        return card.getGame() == observer.getGame() && identity != null && identity.timestamp() == card.getGameTimestamp();
    }

    private Map<Integer, Identity> identities(final Set<Integer> excluded) {
        return identities(excluded, () -> true);
    }

    private Map<Integer, Identity> identities(final Set<Integer> excluded, final java.util.function.BooleanSupplier checkpoint) {
        final Map<Integer, Identity> result = new HashMap<>();
        for (final Card card : observer.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            if (!checkpoint.getAsBoolean()) { return null; }
            if (excluded.contains(card.getId())) { continue; }
            // Never inspect a hidden face to decide whether an old prediction can be used.
            final String keywords = card.isFaceDown() ? "face-down" : java.util.stream.StreamSupport
                    .stream(card.getKeywords().spliterator(), false).map(keyword -> keyword.getOriginal()).sorted()
                    .collect(Collectors.joining(";"));
            final String triggers = card.isFaceDown() ? "" : card.getTriggers().stream()
                    .map(trigger -> trigger.getMapParams().toString()).sorted().collect(Collectors.joining(";"));
            result.put(card.getId(), new Identity(card.getGameTimestamp(), card.getCurrentStateName() + "/" + keywords + "/" + triggers));
        }
        return result;
    }
}
