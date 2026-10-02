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

    private record Key(List<Object> mechanics, List<Object> lossValue, int defender, Set<Integer> blockers,
            boolean futureAttacker, Set<Integer> futureBlockers, Set<Integer> futureBlockedAttackers) { }

    static Map<Integer, Integer> representatives(final PublicCombatSnapshot snapshot,
            final PreparedCombatValuation values, final PublicCombatReadiness readiness, final List<Integer> candidates) {
        final Map<Integer, Integer> result = new LinkedHashMap<>();
        final Map<Key, Integer> representatives = new HashMap<>();
        // TODO: Prove permutation equivalence for source-keyed triggers, relationships, static worlds,
        // prevention policies and ignored effects before reusing their singleton scores.
        final boolean simple = snapshot.triggers().isEmpty() && snapshot.preventionRules().isEmpty()
                && snapshot.staticWorlds().equals(PreparedCombatStaticWorlds.empty()) && snapshot.ignoredEffects().isEmpty()
                && readiness.ignoredEffects().isEmpty() && values.relationships().isEmpty() && values.opportunities().isEmpty();
        for (final int id : candidates) {
            if (!simple || !values.permanents().containsKey(id)) { result.put(id, id); continue; }
            final var card = snapshot.creatures().get(id);
            final var value = values.permanents().get(id);
            final List<Object> mechanics = List.of(card.controllerId(), card.combatDamage(), card.toughness(), card.markedDamage(),
                    card.markedDeathtouch(), card.firstStrike(), card.doubleStrike(), card.deathtouch(), card.indestructible(),
                    card.trample(), card.lifelink(), card.vigilance(), card.tapped(), card.minimumBlockers(), card.maximumBlockers(),
                    card.stunCounters(), card.shieldCounters());
            final List<Object> loss = List.of(value.controllerId(), value.bodyLossValue(), value.unknownAbilityLossValue(),
                    value.futureAbilityLossValue(), value.reasons());
            final Set<Integer> incoming = readiness.blockPairsNextTurn().entrySet().stream()
                    .filter(entry -> entry.getValue().contains(id)).map(Map.Entry::getKey).collect(Collectors.toSet());
            final Key key = new Key(mechanics, loss, snapshot.attackersToDefenders().get(id), snapshot.legalBlockers().getOrDefault(id, Set.of()),
                    readiness.canAttackNextTurn().contains(id), readiness.blockPairsNextTurn().getOrDefault(id, Set.of()), incoming);
            result.put(id, representatives.computeIfAbsent(key, ignored -> id));
        }
        return Map.copyOf(result);
    }
}
