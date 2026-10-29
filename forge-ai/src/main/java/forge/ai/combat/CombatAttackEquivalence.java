package forge.ai.combat;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Conservative singleton-score reuse, never substitution of a different creature in a live attack. */
final class CombatAttackEquivalence {
    private CombatAttackEquivalence() { }

    private record Key(List<Object> mechanics, List<Object> lossValue, int defender, Set<Integer> destinations, Set<Integer> blockers,
            boolean futureAttacker, Set<Integer> futureBlockers, Set<Integer> futureBlockedAttackers,
            boolean observedAttacker, boolean observedBlocker) { }

    static Map<Integer, Integer> representatives(final PublicCombatSnapshot snapshot,
            final PreparedCombatValuation values, final PublicCombatReadiness readiness, final List<Integer> candidates) {
        if (!canReuse(snapshot, values, readiness, candidates, Map.of())) { return identities(candidates); }
        final Map<Integer, Set<Integer>> legal = new HashMap<>();
        snapshot.attackersToDefenders().forEach((id, target) -> legal.put(id, Set.of(target)));
        return representatives(snapshot, values, readiness, candidates, Map.of(), legal, null, false);
    }

    /** An extension's representatives must belong to its remaining pool, not fixed attackers. */
    static Map<Integer, Integer> representatives(final PublicCombatSnapshot snapshot,
            final PreparedCombatValuation values, final PublicCombatReadiness readiness, final List<Integer> candidates,
            final Map<Integer, Integer> committed, final Map<Integer, Set<Integer>> legal,
            final Integer destination, final boolean standardPressure) {
        if (!canReuse(snapshot, values, readiness, candidates, committed)) { return identities(candidates); }
        final Map<Integer, Integer> result = new LinkedHashMap<>();
        final Map<Key, Integer> representatives = new HashMap<>();
        final Map<Integer, Key> keys = new HashMap<>();
        for (final int id : snapshot.creatures().keySet()) {
            if (!values.permanents().containsKey(id)) { continue; }
            final var card = snapshot.creatures().get(id);
            final var value = values.permanents().get(id);
            final List<Object> mechanics = List.of(card.controllerId(), card.combatDamage(), card.toughness(), card.markedDamage(),
                    card.markedDeathtouch(), card.firstStrike(), card.doubleStrike(), card.deathtouch(), card.indestructible(),
                    card.trample(), card.lifelink(), card.vigilance(), card.tapped(), card.minimumBlockers(), card.maximumBlockers(),
                    card.stunCounters(), card.shieldCounters());
            final List<Object> loss = List.of(value.controllerId(), value.bodyLossValue(), value.unknownAbilityLossValue(),
                    value.futureAbilityLossValue(), value.reasons(), value.bodyRetentionPercent(), value.abilitiesComplete());
            final Set<Integer> incoming = readiness.blockPairsNextTurn().entrySet().stream()
                    .filter(entry -> entry.getValue().contains(id)).map(Map.Entry::getKey).collect(Collectors.toSet());
            final Key key = new Key(mechanics, loss, destination == null ? snapshot.attackersToDefenders().getOrDefault(id, -1) : destination,
                    legal.getOrDefault(id, Set.of()), snapshot.legalBlockers().getOrDefault(id, Set.of()),
                    readiness.canAttackNextTurn().contains(id), readiness.blockPairsNextTurn().getOrDefault(id, Set.of()), incoming,
                    snapshot.observedAttackers().contains(id), snapshot.observedBlockers().contains(id));
            keys.put(id, key);
        }
        for (final int id : candidates.stream().sorted().toList()) {
            final Key key = keys.get(id);
            if (key == null || committed.containsKey(id)
                    || standardPressure && !safeStandardContinuation(id, keys, snapshot, committed)) {
                result.put(id, id); continue;
            }
            result.put(id, representatives.computeIfAbsent(key, ignored -> id));
        }
        return Map.copyOf(result);
    }

    private static Map<Integer, Integer> identities(final List<Integer> candidates) {
        final Map<Integer, Integer> result = new HashMap<>();
        candidates.forEach(id -> result.put(id, id));
        return Map.copyOf(result);
    }

    private static boolean canReuse(final PublicCombatSnapshot snapshot, final PreparedCombatValuation values,
            final PublicCombatReadiness readiness, final List<Integer> candidates, final Map<Integer, Integer> committed) {
        // Avoid whole-board key construction (and destination-map preparation) when no
        // equivalence is possible. TODO: Audit broader source-sensitive permutation support,
        // including matching lifetime roles; a disposable body cannot stand in for a durable one.
        if (candidates.size() < 2 || !snapshot.singletonPermutationSafe() || !readiness.singletonPermutationSafe()
                || !snapshot.triggers().isEmpty() || !snapshot.preventionRules().isEmpty() || !snapshot.protection().creatures().isEmpty()
                || !snapshot.departures().entries().isEmpty()
                || !snapshot.staticWorlds().equals(PreparedCombatStaticWorlds.empty())
                || !snapshot.unsupportedReasons().isEmpty() || !snapshot.unavailableReasons().isEmpty()
                || !readiness.unsupportedReasons().isEmpty() || !values.relationships().isEmpty() || !values.opportunities().isEmpty()) {
            return false;
        }
        return candidates.stream().filter(id -> !committed.containsKey(id) && snapshot.creatures().containsKey(id)
                && values.permanents().containsKey(id)).distinct().limit(2).count() == 2;
    }

    private static boolean safeStandardContinuation(final int id, final Map<Integer, Key> keys,
            final PublicCombatSnapshot snapshot, final Map<Integer, Integer> committed) {
        final var card = snapshot.creatures().get(id);
        // ID tie-breaking can select different casualties in an equal-valued reply, not only
        // in the two-biggest-attacker stress. Admit homogeneous uncommitted friendly roles
        // first. TODO: Broader mixed-board continuation symmetries with audited tie attribution.
        // Ordinary future searches without callbacks compare immediate transitions only.
        return snapshot.creatures().entrySet().stream().filter(entry -> entry.getValue().controllerId() == card.controllerId())
                .allMatch(entry -> !committed.containsKey(entry.getKey()) && keys.get(id).equals(keys.get(entry.getKey())));
    }
}
