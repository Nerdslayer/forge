package forge.ai.combat;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.ai.effect.ValuationCompleteness;

/** Frozen scalar loss ledger: contains no live cards, players, hidden definitions, or callbacks. */
public record PreparedCombatValuation(Map<Integer, PermanentValue> permanents,
        List<RelationshipValue> relationships, ValuationCompleteness completeness, List<String> reasons,
        Map<CombatAbilityKey, OpportunityValue> opportunities) {
    public PreparedCombatValuation {
        permanents = Map.copyOf(permanents);
        relationships = List.copyOf(relationships);
        reasons = List.copyOf(reasons);
        opportunities = Map.copyOf(opportunities);
        if (completeness == null) {
            throw new IllegalArgumentException("Combat valuation coverage is required");
        }
        for (final Map.Entry<Integer, PermanentValue> entry : permanents.entrySet()) {
            if (entry.getKey() != entry.getValue().cardId()) {
                throw new IllegalArgumentException("Permanent ID does not match its ledger key");
            }
        }
    }

    public PreparedCombatValuation(final Map<Integer, PermanentValue> permanents, final List<RelationshipValue> relationships,
            final ValuationCompleteness completeness, final List<String> reasons) {
        this(permanents, relationships, completeness, reasons, Map.of());
    }

    /** Signed removal-loss ownership: completing a use retires at most its remaining allowance. */
    public record OpportunityValue(int remainingLossValue, int perUseLossValue) {
        public OpportunityValue {
            if (remainingLossValue != 0 && perUseLossValue != 0
                    && Integer.signum(remainingLossValue) != Integer.signum(perUseLossValue)) {
                throw new IllegalArgumentException("Opportunity allowance and per-use value must share polarity");
            }
        }
    }

    /** Branch-local replacement, before either casualty scoring or continuation preparation. */
    public PreparedCombatValuation retireOpportunities(final Map<CombatAbilityKey, Integer> resolutions) {
        if (resolutions.isEmpty() || opportunities.isEmpty()) { return this; }
        final Map<Integer, PermanentValue> adjusted = new LinkedHashMap<>(permanents);
        final Map<CombatAbilityKey, OpportunityValue> remaining = new LinkedHashMap<>(opportunities);
        for (final var entry : resolutions.entrySet()) {
            if (entry.getValue() < 0) { throw new IllegalArgumentException("Nonnegative completed uses required"); }
            final OpportunityValue value = remaining.get(entry.getKey());
            if (value == null || entry.getValue() == 0) { continue; }
            final long magnitude = Math.min(Math.abs((long) value.remainingLossValue()),
                    Math.abs((long) value.perUseLossValue()) * entry.getValue());
            final int retired = saturated(magnitude * Integer.signum(value.remainingLossValue()));
            final PermanentValue source = adjusted.get(entry.getKey().sourceId());
            if (source == null) { throw new IllegalArgumentException("Opportunity source is outside the ledger"); }
            adjusted.put(source.cardId(), new PermanentValue(source.cardId(), source.controllerId(), source.bodyLossValue(),
                    source.unknownAbilityLossValue(), saturated((long) source.futureAbilityLossValue() - retired), source.reasons()));
            remaining.put(entry.getKey(), new OpportunityValue(saturated((long) value.remainingLossValue() - retired), value.perUseLossValue()));
        }
        return new PreparedCombatValuation(adjusted, relationships, completeness, reasons, remaining);
    }

    public PreparedCombatValuation surviving(final Collection<Integer> lostIds) {
        final Set<Integer> lost = Set.copyOf(lostIds);
        final Map<Integer, PermanentValue> remaining = new LinkedHashMap<>(permanents);
        lost.forEach(remaining::remove);
        final Map<CombatAbilityKey, OpportunityValue> kept = new LinkedHashMap<>(opportunities);
        kept.keySet().removeIf(key -> lost.contains(key.sourceId()));
        return new PreparedCombatValuation(remaining, relationships.stream().filter(edge ->
                !lost.contains(edge.key().producerId()) && !lost.contains(edge.key().consumerId())).toList(), completeness, reasons, kept);
    }

    /** Signed body delta for surviving counters; casualties already lose their entire old body. */
    public int survivorCounterUtility(final PublicCombatSnapshot snapshot, final CombatProjection projection) {
        long result = 0;
        for (final var entry : projection.survivors().entrySet()) {
            final var before = snapshot.creatures().get(entry.getKey());
            final int spent = forge.ai.CreatureEvaluator.shieldCounterValue(before.shieldCounters(), before.indestructible())
                    - forge.ai.CreatureEvaluator.shieldCounterValue(entry.getValue().shieldCounters(), before.indestructible());
            result += before.controllerId() == snapshot.observingPlayerId() ? -spent : spent;
        }
        return saturated(result);
    }

    public int survivorBodyUtility(final PublicCombatSnapshot snapshot, final CombatProjection projection) {
        long result = survivorCounterUtility(snapshot, projection);
        final var world = snapshot.staticWorlds().afterLosses(projection.lostCreatures());
        for (final int id : projection.survivors().keySet()) {
            final var changed = world.get(id);
            if (changed != null) {
                result += (long) changed.bodyDelta() * (snapshot.creatures().get(id).controllerId() == snapshot.observingPlayerId() ? 1 : -1);
            }
        }
        return saturated(result);
    }

    /** Removes casualties/realized opportunities and reduces surviving body allowances once. */
    public PreparedCombatValuation afterCombat(final PublicCombatSnapshot snapshot, final CombatProjection projection) {
        final var remaining = retireOpportunities(projection.outcomes().resolutions()).surviving(projection.lostCreatures());
        final Map<Integer, PermanentValue> adjusted = new LinkedHashMap<>(remaining.permanents());
        final var world = snapshot.staticWorlds().afterLosses(projection.lostCreatures());
        for (final var entry : projection.survivors().entrySet()) {
            final var before = snapshot.creatures().get(entry.getKey());
            final var value = adjusted.get(entry.getKey());
            if (value == null) { continue; }
            final int spent = forge.ai.CreatureEvaluator.shieldCounterValue(before.shieldCounters(), before.indestructible())
                    - forge.ai.CreatureEvaluator.shieldCounterValue(entry.getValue().shieldCounters(), before.indestructible());
            final int reduction = before.controllerId() == snapshot.observingPlayerId() ? spent : -spent;
            final int staticDelta = world.containsKey(entry.getKey()) ? world.get(entry.getKey()).bodyDelta() : 0;
            final int staticLossDelta = before.controllerId() == snapshot.observingPlayerId() ? -staticDelta : staticDelta;
            adjusted.put(entry.getKey(), new PermanentValue(value.cardId(), value.controllerId(),
                    saturated((long) value.bodyLossValue() + reduction + staticLossDelta), value.unknownAbilityLossValue(), value.futureAbilityLossValue(), value.reasons()));
        }
        // TODO: Characteristic/static changes beyond fixed additive P/T and counter-dependent relationships.
        return new PreparedCombatValuation(adjusted, remaining.relationships(), remaining.completeness(), remaining.reasons(), remaining.opportunities());
    }

    /** All three loss values are AI utility, not unsigned permanent value or threat ranking. */
    public record PermanentValue(int cardId, int controllerId, int bodyLossValue,
            int unknownAbilityLossValue, int futureAbilityLossValue, List<String> reasons) {
        public PermanentValue { reasons = List.copyOf(reasons); }
    }

    /** Identity includes both endpoints and distinguishes real batches, not just script paths. */
    public record RelationshipKey(int producerId, String producerAbility, int consumerId,
            String consumerAbility, String family, String batch) { }

    public record RelationshipValue(RelationshipKey key, int lossValue) {
        public RelationshipValue {
            if (key == null) { throw new IllegalArgumentException("Relationship identity is required"); }
        }
    }

    public record LossValue(int body, int unknownAbility, int futureAbility, int relationships,
            ValuationCompleteness completeness, List<String> reasons) {
        public LossValue { reasons = List.copyOf(reasons); }
        public int total() { return saturated((long) body + unknownAbility + futureAbility + relationships); }
    }

    /** Scores a simultaneous loss set once per permanent and once per distinct opportunity. */
    public LossValue evaluateLosses(final Collection<Integer> lostIds) {
        final Set<Integer> losses = Set.copyOf(lostIds);
        long body = 0;
        long unknown = 0;
        long future = 0;
        for (final int id : losses) {
            final PermanentValue value = permanents.get(id);
            if (value == null) {
                throw new IllegalArgumentException("Loss is outside the prepared battlefield: " + id);
            }
            body += value.bodyLossValue();
            unknown += value.unknownAbilityLossValue();
            future += value.futureAbilityLossValue();
        }
        final Map<RelationshipKey, Integer> counted = new LinkedHashMap<>();
        for (final RelationshipValue relationship : relationships) {
            final RelationshipKey key = relationship.key();
            if (!losses.contains(key.producerId()) && !losses.contains(key.consumerId())) {
                continue;
            }
            final Integer previous = counted.putIfAbsent(key, relationship.lossValue());
            if (previous != null && previous != relationship.lossValue()) {
                throw new IllegalArgumentException("Conflicting values for the same relationship opportunity");
            }
        }
        long relationshipValue = 0;
        for (final int value : counted.values()) { relationshipValue += value; }
        // TODO: Compose surviving static recipients and broader current/future ownership.
        // Concrete current-use replacement is a separate branch-local retireOpportunities call.
        return new LossValue(saturated(body), saturated(unknown), saturated(future),
                saturated(relationshipValue), completeness, reasons);
    }

    private static int saturated(final long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }
}
