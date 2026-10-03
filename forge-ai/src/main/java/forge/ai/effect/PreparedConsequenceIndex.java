package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/** Immutable routing facts only; activity, copied outcomes and event bindings remain fresh. */
final class PreparedConsequenceIndex {
    record Reference(Card source, long timestamp, AbilityIdentity ability, EffectType type) {
        EffectConsequence bind() {
            if (!source.isInZone(ZoneType.Battlefield) || source.getGameTimestamp() != timestamp
                    || !ability.path().startsWith(source.getCurrentStateName().name() + "/")) {
                return null;
            }
            final Trigger trigger = EffectAbilityUtils.triggerAtPath(source, ability.path());
            final EffectConsequence consequence = trigger == null ? null
                    : EffectConsequenceExtractorRegistry.extract(source, trigger);
            return consequence != null && consequence.observedType() == type ? consequence : null;
        }
    }

    private final Map<EffectType, List<Reference>> references;

    private PreparedConsequenceIndex(final Map<EffectType, List<Reference>> references) {
        final Map<EffectType, List<Reference>> copied = new EnumMap<>(EffectType.class);
        references.forEach((type, entries) -> copied.put(type, List.copyOf(entries)));
        this.references = Map.copyOf(copied);
    }

    static PreparedConsequenceIndex prepare(final Player ai, final Iterable<Player> controllers) {
        final Map<EffectType, List<Reference>> references = new EnumMap<>(EffectType.class);
        for (final Player controller : controllers) {
            for (final Card source : controller.getCardsIn(ZoneType.Battlefield)) {
                for (final Trigger trigger : source.getTriggers()) {
                    try {
                        final EffectType type = EventTriggerParser.observedType(trigger);
                        if (type != null) {
                            final AbilityIdentity identity = AbilityIdentity.forTrigger(source, trigger);
                            if (!identity.mapped()) { throw new IllegalStateException("Unmapped live trigger"); }
                            references.computeIfAbsent(type, key -> new ArrayList<>()).add(
                                    new Reference(source, source.getGameTimestamp(), identity, type));
                        }
                    } catch (final RuntimeException unsupported) {
                        SituationalAnalysisSession.noteFailure(ai);
                    }
                }
            }
        }
        return new PreparedConsequenceIndex(references);
    }

    List<Reference> forType(final EffectType type) {
        return references.getOrDefault(type, List.of());
    }

    // TODO: Share normalized production descriptors and baseline extraction without retaining
    // mutable event payloads, executable outcomes, or decision-dependent occurrence estimates.
}
