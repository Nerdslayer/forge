package forge.ai.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Game-free damage projection; gang blocks require explicit, validated per-step allocations. */
public final class CombatOutcomePredictor {
    private CombatOutcomePredictor() { }

    public static CombatProjection predict(final PublicCombatSnapshot snapshot, final CombatAssignment assignment) {
        return predict(snapshot, assignment, null);
    }

    public static CombatProjection predict(final PublicCombatSnapshot snapshot, final CombatAssignment assignment,
            final CombatDamagePlan plan) {
        return predictInternal(snapshot, assignment, plan, null, false);
    }

    /** Reuses the same simulator to establish the expected boundary before regular damage. */
    public static CombatProjection predictFirstStrike(final PublicCombatSnapshot snapshot, final CombatAssignment assignment,
            final CombatDamagePlan plan) {
        return predictInternal(snapshot, assignment, plan, null, true);
    }

    record AllocationRequest(boolean firstStrike, int attackerId, int damage,
            List<CombatDamageAllocation.Target> targets, boolean trample, boolean legacyOrder) { }

    /** Only the game-free allocation optimizer uses this continuation boundary. */
    static CombatProjection predictResolving(final PublicCombatSnapshot snapshot, final CombatAssignment assignment,
            final Function<AllocationRequest, CombatDamageAllocation.Allocation> resolver) {
        return predictInternal(snapshot, assignment, null, resolver, false);
    }

    private static CombatProjection predictInternal(final PublicCombatSnapshot snapshot, final CombatAssignment assignment,
            final CombatDamagePlan plan, final Function<AllocationRequest, CombatDamageAllocation.Allocation> resolver,
            final boolean firstStrikeOnly) {
        if (!snapshot.unsupportedReasons().isEmpty()) {
            return CombatProjection.rejected(false, snapshot.unsupportedReasons());
        }
        if (!snapshot.staticWorlds().supported()) { return CombatProjection.rejected(false, snapshot.staticWorlds().reasons()); }
        if (!snapshot.unavailableReasons().isEmpty()) {
            return CombatProjection.rejected(true, snapshot.unavailableReasons());
        }
        if (!assignment.attackersToDefenders().equals(snapshot.attackersToDefenders())) {
            return CombatProjection.rejected(true, List.of("Attack declaration differs from frozen snapshot"));
        }
        if (plan != null && (!assignment.attackersToDefenders().keySet().containsAll(plan.firstStrike().keySet())
                || !assignment.attackersToDefenders().keySet().containsAll(plan.regular().keySet()))) {
            return CombatProjection.rejected(true, List.of("Damage plan references an undeclared attacker"));
        }
        final Set<Integer> used = new HashSet<>();
        for (final var entry : assignment.blockersByAttacker().entrySet()) {
            if (!snapshot.attackersToDefenders().containsKey(entry.getKey())) {
                return CombatProjection.rejected(true, List.of("Block references an undeclared attacker"));
            }
            if (!snapshot.creatures().get(entry.getKey()).permitsBlockerCount(entry.getValue().size())) {
                return CombatProjection.rejected(true, List.of("Illegal blocker count for attacker " + entry.getKey()));
            }
            // TODO: Blockers that can block multiple attackers and global declaration requirements.
            if (new HashSet<>(entry.getValue()).size() != entry.getValue().size()) {
                return CombatProjection.rejected(true, List.of("Repeated blocker within one group"));
            }
            if (entry.getValue().size() > 1 && plan == null && resolver == null) {
                return CombatProjection.rejected(false, List.of("Gang-block damage assignment is not projected"));
            }
            for (final int blocker : entry.getValue()) {
                if (!snapshot.legalBlockers().getOrDefault(entry.getKey(), Set.of()).contains(blocker)
                        || !used.add(blocker)) {
                    return CombatProjection.rejected(true, List.of("Illegal or reused blocker"));
                }
                if (plan == null && resolver == null && snapshot.creatures().get(entry.getKey()).trample()
                        && snapshot.creatures().get(blocker).indestructible()) {
                    // TODO: The existing damage-assignment AI can overassign to indestructible
                    // blockers, including deathtouch/trample. Bridge the chosen legal allocation
                    // to actual assignment callbacks before promising that player spillover.
                    return CombatProjection.rejected(false, List.of(
                            "Trample against indestructible requires an execution-consistent damage assignment"));
                }
            }
        }
        final Map<Integer, Integer> damage = new HashMap<>();
        final Map<Integer, Integer> shields = new HashMap<>();
        final Set<Integer> deathtouched = new HashSet<>();
        final Set<Integer> lost = new HashSet<>();
        final Map<Integer, Integer> life = new LinkedHashMap<>();
        snapshot.creatures().forEach((id, creature) -> {
            damage.put(id, creature.markedDamage());
            shields.put(id, creature.shieldCounters());
            if (creature.markedDeathtouch()) { deathtouched.add(id); }
        });
        snapshot.players().forEach((id, player) -> life.put(id, player.life()));
        final List<CombatProjection.DamageBatch> batches = new ArrayList<>();
        final List<CombatEventBatch> eventBatches = new ArrayList<>();
        declarations(snapshot, assignment, damage, deathtouched, eventBatches);
        CombatOutcomeResolution outcomes = forge.ai.effect.CombatEventOutcomeResolver.resolve(snapshot, eventBatches);
        if (!outcomes.supported()) { return CombatProjection.rejected(false, outcomes.reasons()); }
        CombatProjection.Terminal terminal = CombatProjection.Terminal.NONE;
        for (final boolean first : List.of(true, false)) {
            if (!first && firstStrikeOnly) { break; }
            final Map<Integer, Integer> cardHits = new HashMap<>();
            final Map<Integer, Integer> playerHits = new HashMap<>();
            final Map<Integer, Integer> gains = new HashMap<>();
            final List<CombatEventBatch.Event> events = new ArrayList<>();
            final Map<Integer, Integer> lifeBefore = Map.copyOf(life);
            final Map<Integer, CombatEventBatch.CardState> beforeDamage = eventState(
                    snapshot, assignment, damage, deathtouched, lost, true, shields);
            for (final int id : assignment.attackersToDefenders().keySet().stream().sorted().toList()) {
                if (lost.contains(id)) { continue; }
                final PublicCombatSnapshot.Creature attacker = snapshot.creatureAfterLosses(id, lost);
                final List<Integer> group = assignment.blockersByAttacker().getOrDefault(id, List.of());
                final List<Integer> survivingBlockers = group.stream().filter(blocker -> !lost.contains(blocker)).toList();
                if (dealsDamage(attacker, first)) {
                    final int power = attacker.combatDamage();
                    CombatDamageAllocation.Allocation allocation = plan == null ? null : plan.step(first).get(id);
                    if (!survivingBlockers.isEmpty()) {
                        final List<CombatDamageAllocation.Target> targets = survivingBlockers.stream().map(blocker ->
                                new CombatDamageAllocation.Target(blocker, attacker.deathtouch() ? 1
                                        : Math.max(0, snapshot.creatureAfterLosses(blocker, lost).toughness() - damage.get(blocker)))).toList();
                        if (resolver != null) {
                            allocation = resolver.apply(new AllocationRequest(first, id, power, targets,
                                    attacker.trample(), snapshot.legacyDamageOrder()));
                            if (allocation == null) {
                                return CombatProjection.rejected(false, List.of("Damage allocation decision pending"));
                            }
                        }
                        if (allocation != null) {
                            if (!CombatDamageAllocation.isLegal(power, targets, attacker.trample(), snapshot.legacyDamageOrder(), allocation)) {
                                return CombatProjection.rejected(true, List.of("Illegal damage allocation for attacker " + id));
                            }
                            for (final var assigned : allocation.creatureDamage().entrySet().stream()
                                    .sorted(Map.Entry.comparingByKey()).toList()) {
                                hit(attacker, assigned.getKey(), assigned.getValue(), cardHits, gains, events,
                                        CombatEventBatch.Recipient.CREATURE);
                            }
                            hit(attacker, snapshot.defendingPlayerId(), allocation.defenderDamage(), playerHits, gains,
                                    events, CombatEventBatch.Recipient.PLAYER);
                        } else {
                            final int blockerId = survivingBlockers.get(0);
                            if (survivingBlockers.size() > 1 || attacker.trample() && snapshot.creatures().get(blockerId).indestructible()) {
                                return CombatProjection.rejected(false, List.of("Missing explicit damage allocation for attacker " + id));
                            }
                            final int toBlocker = attacker.trample() ? Math.min(power, targets.get(0).lethalDamage()) : power;
                            hit(attacker, blockerId, toBlocker, cardHits, gains, events, CombatEventBatch.Recipient.CREATURE);
                            if (attacker.trample()) {
                                hit(attacker, snapshot.defendingPlayerId(), power - toBlocker, playerHits, gains,
                                        events, CombatEventBatch.Recipient.PLAYER);
                            }
                        }
                    } else if (group.isEmpty() || attacker.trample()) {
                        // A creature remains blocked after its blocker dies in first-strike damage.
                        if (allocation != null && (!allocation.creatureDamage().isEmpty() || allocation.defenderDamage() != power)) {
                            return CombatProjection.rejected(true, List.of("Illegal unblocked damage allocation for attacker " + id));
                        }
                        hit(attacker, snapshot.defendingPlayerId(), power, playerHits, gains,
                                events, CombatEventBatch.Recipient.PLAYER);
                    } else if (allocation != null && (!allocation.creatureDamage().isEmpty() || allocation.defenderDamage() != 0)) {
                        return CombatProjection.rejected(true, List.of("Blocked nontrampler has no remaining damage recipient"));
                    }
                }
                for (final int blockerId : survivingBlockers) {
                    final PublicCombatSnapshot.Creature blocker = snapshot.creatureAfterLosses(blockerId, lost);
                    if (dealsDamage(blocker, first)) {
                        hit(blocker, id, blocker.combatDamage(), cardHits, gains,
                                events, CombatEventBatch.Recipient.CREATURE);
                    }
                }
            }
            if (cardHits.isEmpty() && playerHits.isEmpty()) { continue; }
            preventShieldDamage(snapshot, events, cardHits, gains, shields, deathtouched, lost);
            cardHits.forEach((id, amount) -> damage.merge(id, amount, CombatOutcomePredictor::add));
            gains.entrySet().removeIf(entry -> !snapshot.players().get(entry.getKey()).canGainLife());
            lifeEvents(snapshot, playerHits, events);
            for (final var entry : snapshot.players().entrySet()) {
                final int id = entry.getKey();
                final PublicCombatSnapshot.LifeState player = entry.getValue();
                final int delta = (player.canGainLife() ? gains.getOrDefault(id, 0) : 0)
                        - (player.canLoseLife() ? playerHits.getOrDefault(id, 0) : 0);
                life.put(id, add(life.get(id), delta));
            }
            batches.add(new CombatProjection.DamageBatch(first, cardHits, playerHits, gains));
            final CombatEventBatch damageEvents = new CombatEventBatch(first ? CombatEventBatch.Stage.FIRST_STRIKE_DAMAGE
                    : CombatEventBatch.Stage.REGULAR_DAMAGE, beforeDamage, lifeBefore, events);
            eventBatches.add(damageEvents);
            final List<CombatEventBatch> resolvingEvents = new ArrayList<>(List.of(damageEvents));
            while (true) {
                final Map<Integer, CombatEventBatch.CardState> beforeDeaths = eventState(
                        snapshot, assignment, damage, deathtouched, lost, true, shields);
                final List<CombatEventBatch.Event> deaths = new ArrayList<>();
                for (final int id : snapshot.creatures().keySet().stream().sorted().toList()) {
                    if (lost.contains(id)) { continue; }
                    final var creature = snapshot.creatureAfterLosses(id, lost);
                    if (creature.toughness() <= 0 || !creature.indestructible()
                            && (damage.get(id) >= creature.toughness() || deathtouched.contains(id))) {
                        deaths.add(new CombatEventBatch.Dies(id));
                    }
                }
                // Determine the simultaneous casualty set before removing any of its providers.
                for (final var event : deaths) { lost.add(((CombatEventBatch.Dies) event).cardId()); }
                if (!deaths.isEmpty()) {
                    final var deathEvents = new CombatEventBatch(first ? CombatEventBatch.Stage.FIRST_STRIKE_DEATHS
                            : CombatEventBatch.Stage.REGULAR_DEATHS, beforeDeaths, life, deaths);
                    eventBatches.add(deathEvents);
                    resolvingEvents.add(deathEvents);
                }
                terminal = terminal(snapshot, life);
                if (deaths.isEmpty() || terminal != CombatProjection.Terminal.NONE) { break; }
                // Repeat SBAs after the combined static loss: toughness loss can kill another
                // provider/recipient, whose departure requires a distinct LKI death batch.
            }
            if (terminal != CombatProjection.Terminal.NONE) { break; }
            // Damage triggers are put on the stack after state-based losses. A dying source's
            // trigger still resolves using the pre-damage event batch; a terminal game does not.
            // Fixed draws commute on each recipient's public counts. TODO: APNAP/stack ordering
            // before admitting mixed characteristic-changing damage/death outcomes.
            outcomes = forge.ai.effect.CombatEventOutcomeResolver.continueWith(snapshot, resolvingEvents, outcomes);
            if (!outcomes.supported()) { return CombatProjection.rejected(false, outcomes.reasons()); }
        }
        final Map<Integer, CombatProjection.Survivor> survivors = new HashMap<>();
        snapshot.creatures().forEach((id, creature) -> {
            if (!lost.contains(id)) {
                final boolean tapped = creature.tapped()
                        || assignment.attackersToDefenders().containsKey(id) && !creature.vigilance();
                survivors.put(id, new CombatProjection.Survivor(damage.get(id), tapped, deathtouched.contains(id), shields.get(id)));
            }
        });
        // TODO: Bind the frozen event batches and LKI to shared projected outcomes;
        // support planeswalkers, poison/commander damage and broader replacement/prevention.
        // Supported here means the approximate model can be evaluated, not that every
        // printed ability has been simulated. Preserve omissions for callers and diagnostics.
        return new CombatProjection(true, true, snapshot.ignoredEffects(), lost, survivors, life, batches, terminal, eventBatches, outcomes);
    }

    private static boolean dealsDamage(final PublicCombatSnapshot.Creature creature, final boolean first) {
        return first ? creature.firstStrike() || creature.doubleStrike() : !creature.firstStrike() || creature.doubleStrike();
    }

    private static void preventShieldDamage(final PublicCombatSnapshot snapshot, final List<CombatEventBatch.Event> events,
            final Map<Integer, Integer> hits, final Map<Integer, Integer> gains, final Map<Integer, Integer> shields,
            final Set<Integer> deathtouched, final Set<Integer> lostProviders) {
        final Set<Integer> protectedTargets = new HashSet<>();
        for (final CombatEventBatch.Event event : events) {
            if (event instanceof CombatEventBatch.Damage hit && hit.recipient() == CombatEventBatch.Recipient.CREATURE
                    && shields.get(hit.recipientId()) > 0) { protectedTargets.add(hit.recipientId()); }
        }
        // One prevention effect covers the entire simultaneous batch, not one counter per source.
        events.removeIf(event -> event instanceof CombatEventBatch.Damage hit
                && hit.recipient() == CombatEventBatch.Recipient.CREATURE && protectedTargets.contains(hit.recipientId())
                && snapshot.preventionRules().stream().noneMatch(rule -> rule.applies(snapshot.creatures().get(hit.sourceId()), lostProviders)));
        protectedTargets.stream().sorted().forEach(id -> {
            shields.put(id, shields.get(id) - 1);
            events.add(new CombatEventBatch.CountersRemoved(id, "Shield", 1));
        });
        hits.clear();
        gains.clear();
        for (final CombatEventBatch.Event event : events) {
            if (!(event instanceof CombatEventBatch.Damage hit)) { continue; }
            final var source = snapshot.creatures().get(hit.sourceId());
            if (hit.recipient() == CombatEventBatch.Recipient.CREATURE) {
                hits.merge(hit.recipientId(), hit.amount(), CombatOutcomePredictor::add);
                if (source.deathtouch()) { deathtouched.add(hit.recipientId()); }
            }
            if (source.lifelink()) { gains.merge(source.controllerId(), hit.amount(), CombatOutcomePredictor::add); }
        }
        // TODO: Other prevention/replacement interactions and conditional source policies.
    }

    private static void hit(final PublicCombatSnapshot.Creature source, final int target, final int amount,
            final Map<Integer, Integer> hits, final Map<Integer, Integer> gains,
            final List<CombatEventBatch.Event> events, final CombatEventBatch.Recipient recipient) {
        if (amount <= 0) { return; }
        hits.merge(target, amount, CombatOutcomePredictor::add);
        events.add(new CombatEventBatch.Damage(source.id(), target, recipient, amount));
        if (source.lifelink()) { gains.merge(source.controllerId(), amount, CombatOutcomePredictor::add); }
    }

    private static Map<Integer, CombatEventBatch.CardState> eventState(final PublicCombatSnapshot snapshot,
            final CombatAssignment assignment, final Map<Integer, Integer> damage,
            final Set<Integer> deathtouched, final Set<Integer> lost, final boolean declared) {
        final Map<Integer, Integer> shields = new HashMap<>();
        snapshot.creatures().forEach((id, creature) -> shields.put(id, creature.shieldCounters()));
        return eventState(snapshot, assignment, damage, deathtouched, lost, declared, shields);
    }

    private static Map<Integer, CombatEventBatch.CardState> eventState(final PublicCombatSnapshot snapshot,
            final CombatAssignment assignment, final Map<Integer, Integer> damage,
            final Set<Integer> deathtouched, final Set<Integer> lost, final boolean declared, final Map<Integer, Integer> shields) {
        final Map<Integer, CombatEventBatch.CardState> state = new LinkedHashMap<>();
        snapshot.creatures().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            final int id = entry.getKey();
            final PublicCombatSnapshot.Creature creature = snapshot.creatureAfterLosses(id, lost);
            if (!lost.contains(id)) {
                state.put(id, new CombatEventBatch.CardState(creature.withShieldCounters(shields.get(id)), damage.get(id), deathtouched.contains(id),
                        creature.tapped() || declared && assignment.attackersToDefenders().containsKey(id)
                                && !creature.vigilance()));
            }
        });
        return state;
    }

    private static void declarations(final PublicCombatSnapshot snapshot, final CombatAssignment assignment,
            final Map<Integer, Integer> damage, final Set<Integer> deathtouched,
            final List<CombatEventBatch> batches) {
        final List<CombatEventBatch.Event> attacks = new ArrayList<>();
        final Map<Integer, Integer> life = new LinkedHashMap<>();
        snapshot.players().forEach((id, player) -> life.put(id, player.life()));
        assignment.attackersToDefenders().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            final PublicCombatSnapshot.Creature creature = snapshot.creatures().get(entry.getKey());
            if (!creature.tapped() && !creature.vigilance()) { attacks.add(new CombatEventBatch.Taps(entry.getKey())); }
            attacks.add(new CombatEventBatch.Attacks(entry.getKey(), entry.getValue()));
        });
        if (!attacks.isEmpty()) {
            batches.add(new CombatEventBatch(CombatEventBatch.Stage.ATTACK_DECLARATION,
                    eventState(snapshot, assignment, damage, deathtouched, Set.of(), false), life, attacks));
        }
        final List<CombatEventBatch.Event> blocks = new ArrayList<>();
        assignment.blockersByAttacker().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (!entry.getValue().isEmpty()) {
                entry.getValue().stream().sorted().forEach(id -> blocks.add(new CombatEventBatch.Blocks(id, entry.getKey())));
                blocks.add(new CombatEventBatch.BecomesBlocked(entry.getKey(), entry.getValue().stream().sorted().toList()));
            }
        });
        if (!blocks.isEmpty()) {
            batches.add(new CombatEventBatch(CombatEventBatch.Stage.BLOCK_DECLARATION,
                    eventState(snapshot, assignment, damage, deathtouched, Set.of(), true), life, blocks));
        }
    }

    private static void lifeEvents(final PublicCombatSnapshot snapshot, final Map<Integer, Integer> playerHits,
            final List<CombatEventBatch.Event> events) {
        final Map<Integer, Integer> lifelinkBySource = new LinkedHashMap<>();
        for (final CombatEventBatch.Event event : events) {
            if (event instanceof CombatEventBatch.Damage hit) {
                final PublicCombatSnapshot.Creature source = snapshot.creatures().get(hit.sourceId());
                if (source.lifelink() && snapshot.players().get(source.controllerId()).canGainLife()) {
                    lifelinkBySource.merge(source.id(), hit.amount(), CombatOutcomePredictor::add);
                }
            }
        }
        lifelinkBySource.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> events.add(
                new CombatEventBatch.LifeGain(entry.getKey(), snapshot.creatures().get(entry.getKey()).controllerId(), entry.getValue())));
        playerHits.entrySet().stream().sorted(Map.Entry.comparingByKey()).filter(entry ->
                snapshot.players().get(entry.getKey()).canLoseLife()).forEach(entry -> events.add(
                        new CombatEventBatch.LifeLoss(entry.getKey(), entry.getValue())));
    }

    private static CombatProjection.Terminal terminal(final PublicCombatSnapshot snapshot, final Map<Integer, Integer> life) {
        final int observer = snapshot.observingPlayerId();
        final int opponent = observer == snapshot.attackingPlayerId() ? snapshot.defendingPlayerId() : snapshot.attackingPlayerId();
        final boolean observerLost = life.get(observer) <= 0 && !snapshot.players().get(observer).cannotLoseAtZero();
        final boolean opponentLost = life.get(opponent) <= 0 && !snapshot.players().get(opponent).cannotLoseAtZero();
        if (observerLost && opponentLost) { return CombatProjection.Terminal.DRAW; }
        if (observerLost && !snapshot.players().get(opponent).cannotWin()) { return CombatProjection.Terminal.LOSS; }
        if (opponentLost && !snapshot.players().get(observer).cannotWin()) { return CombatProjection.Terminal.WIN; }
        return CombatProjection.Terminal.NONE;
    }

    static int add(final int left, final int right) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, (long) left + right));
    }
}
