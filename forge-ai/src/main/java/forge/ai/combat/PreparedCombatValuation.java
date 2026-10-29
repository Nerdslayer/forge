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
                    source.unknownAbilityLossValue(), saturated((long) source.futureAbilityLossValue() - retired), source.reasons(), source.bodyRetentionPercent(), source.abilitiesComplete()));
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

    /** Decision-local residual preservation only; concrete combat effects still resolve normally. */
    PreparedCombatValuation continuationPreservation(final PublicCombatSnapshot snapshot) {
        final Set<Integer> expiring = snapshot.departures().expiringIds();
        if (expiring.isEmpty()) { return this; }
        final Map<Integer, PermanentValue> adjusted = new LinkedHashMap<>(permanents);
        // Compare preservation at the same end-step boundary in every branch. Temporary
        // anthem boosts still affect combat mechanics, but are not durable casualty value.
        final var baseline = snapshot.staticWorlds().afterLosses(expiring);
        baseline.forEach((id, state) -> {
            final var value = adjusted.get(id);
            if (value == null || expiring.contains(id)) { return; }
            final int sign = value.controllerId() == snapshot.observingPlayerId() ? -1 : 1;
            adjusted.put(id, new PermanentValue(id, value.controllerId(),
                    saturated((long) value.bodyLossValue() + bodyDelta(id, (long) sign * state.bodyDelta())),
                    value.unknownAbilityLossValue(), value.futureAbilityLossValue(), value.reasons(),
                    value.bodyRetentionPercent(), value.abilitiesComplete()));
        });
        for (final int id : expiring) {
            final var value = adjusted.get(id);
            if (value != null) {
                adjusted.put(id, new PermanentValue(id, value.controllerId(), 0, 0, 0, value.reasons(), 0, value.abilitiesComplete()));
            }
        }
        final Map<CombatAbilityKey, OpportunityValue> remaining = new LinkedHashMap<>(opportunities);
        remaining.keySet().removeIf(key -> expiring.contains(key.sourceId()));
        return new PreparedCombatValuation(adjusted, relationships.stream().filter(edge ->
                !expiring.contains(edge.key().producerId()) && !expiring.contains(edge.key().consumerId())).toList(),
                completeness, reasons, remaining);
    }

    /** Signed body delta for surviving counters; casualties already lose their entire old body. */
    public int survivorCounterUtility(final PublicCombatSnapshot snapshot, final CombatProjection projection) {
        long result = 0;
        for (final var entry : projection.survivors().entrySet()) {
            final var before = snapshot.creatures().get(entry.getKey());
            final int spent = forge.ai.CreatureEvaluator.shieldCounterValue(before.shieldCounters(), before.indestructible())
                    - forge.ai.CreatureEvaluator.shieldCounterValue(entry.getValue().shieldCounters(), before.indestructible());
            result += bodyDelta(entry.getKey(), before.controllerId() == snapshot.observingPlayerId() ? -spent : spent);
        }
        return saturated(result);
    }

    public int survivorBodyUtility(final PublicCombatSnapshot snapshot, final CombatProjection projection) {
        long result = survivorCounterUtility(snapshot, projection);
        final Set<Integer> expiring = snapshot.departures().expiringIds();
        final Set<Integer> gone = new java.util.HashSet<>(projection.lostPermanents());
        gone.addAll(expiring);
        final var baseline = snapshot.staticWorlds().afterLosses(expiring);
        final var world = snapshot.staticWorlds().afterLosses(gone);
        for (final int id : projection.survivors().keySet()) {
            final var changed = world.get(id);
            if (changed != null) {
                final int baselineDelta = baseline.containsKey(id) ? baseline.get(id).bodyDelta() : 0;
                result += bodyDelta(id, ((long) changed.bodyDelta() - baselineDelta)
                        * (snapshot.creatures().get(id).controllerId() == snapshot.observingPlayerId() ? 1 : -1));
            }
        }
        return saturated(result);
    }

    /** Removes casualties/realized opportunities and reduces surviving body allowances once. */
    public PreparedCombatValuation afterCombat(final PublicCombatSnapshot snapshot, final CombatProjection projection) {
        final var remaining = retireOpportunities(projection.outcomes().resolutions()).surviving(projection.lostPermanents());
        final Map<Integer, PermanentValue> adjusted = new LinkedHashMap<>(remaining.permanents());
        final var world = snapshot.staticWorlds().afterLosses(projection.lostPermanents());
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
                    saturated((long) value.bodyLossValue() + bodyDelta(entry.getKey(), (long) reduction + staticLossDelta)),
                    value.unknownAbilityLossValue(), value.futureAbilityLossValue(), value.reasons(), value.bodyRetentionPercent(), value.abilitiesComplete()));
        }
        // TODO: Characteristic/static changes beyond fixed additive P/T and counter-dependent relationships.
        snapshot.planeswalkers().forEach((id, walker) -> {
            final var value = adjusted.get(id);
            if (value == null) { return; }
            final int after = projection.planeswalkerLoyaltyAfter().getOrDefault(id, walker.loyalty());
            final int removed = Math.max(0, walker.loyalty() - after) * 10;
            final int sign = walker.controllerId() == snapshot.observingPlayerId() ? -1 : 1;
            adjusted.put(id, new PermanentValue(value.cardId(), value.controllerId(), value.bodyLossValue() - sign * removed,
                    value.unknownAbilityLossValue(), value.futureAbilityLossValue(), value.reasons(), value.bodyRetentionPercent(), value.abilitiesComplete()));
        });
        return new PreparedCombatValuation(adjusted, remaining.relationships(), remaining.completeness(), remaining.reasons(), remaining.opportunities());
    }

    /** Rebase surviving recipient bodies after expiry, without charging the expiring source twice. */
    PreparedCombatValuation afterDepartures(final PublicCombatSnapshot snapshot, final CombatProjection combat, final Set<Integer> gone) {
        final var remaining = surviving(gone);
        if (snapshot.departures().expiringIds().isEmpty()) { return remaining; }
        final var oldWorld = snapshot.staticWorlds().afterLosses(combat.lostPermanents());
        final var newWorld = snapshot.staticWorlds().afterLosses(gone);
        final Map<Integer, PermanentValue> adjusted = new LinkedHashMap<>(remaining.permanents());
        for (final var entry : newWorld.entrySet()) {
            final int id = entry.getKey();
            final var value = adjusted.get(id);
            if (value == null || !oldWorld.containsKey(id)) { continue; }
            final int delta = saturated((long) entry.getValue().bodyDelta() - oldWorld.get(id).bodyDelta());
            final int sign = value.controllerId() == snapshot.observingPlayerId() ? -1 : 1;
            adjusted.put(id, new PermanentValue(id, value.controllerId(), saturated((long) value.bodyLossValue() + sign * bodyDelta(id, delta)),
                    value.unknownAbilityLossValue(), value.futureAbilityLossValue(), value.reasons(), value.bodyRetentionPercent(), value.abilitiesComplete()));
        }
        return new PreparedCombatValuation(adjusted, remaining.relationships(), remaining.completeness(), remaining.reasons(), remaining.opportunities());
    }

    /** All three loss values are AI utility, not unsigned permanent value or threat ranking. */
    public record PermanentValue(int cardId, int controllerId, int bodyLossValue,
            int unknownAbilityLossValue, int futureAbilityLossValue, List<String> reasons, int bodyRetentionPercent,
            boolean abilitiesComplete) {
        public PermanentValue {
            reasons = List.copyOf(reasons);
            if (bodyRetentionPercent < 0 || bodyRetentionPercent > 100) { throw new IllegalArgumentException("Invalid body retention"); }
        }
        public PermanentValue(final int cardId, final int controllerId, final int bodyLossValue,
                final int unknownAbilityLossValue, final int futureAbilityLossValue, final List<String> reasons, final int bodyRetentionPercent) {
            this(cardId, controllerId, bodyLossValue, unknownAbilityLossValue, futureAbilityLossValue, reasons, bodyRetentionPercent, false);
        }
        public PermanentValue(final int cardId, final int controllerId, final int bodyLossValue,
                final int unknownAbilityLossValue, final int futureAbilityLossValue, final List<String> reasons) {
            this(cardId, controllerId, bodyLossValue, unknownAbilityLossValue, futureAbilityLossValue, reasons, 100);
        }
    }

    /** Preserve the source's lifetime scale when a projected survivor changes characteristics. */
    private int bodyDelta(final int id, final long delta) {
        final var value = permanents.get(id);
        final int retained = value == null ? 100 : value.bodyRetentionPercent();
        return saturated(Long.signum(delta) * Math.round(Math.abs((double) delta) * retained / 100.0));
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
