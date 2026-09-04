package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardZoneTable;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/** Matches known individual zone changes against straightforward ChangesZone triggers. */
final class ZoneChangedEventMatcher implements EffectEventMatcher {
    static final ZoneChangedEventMatcher INSTANCE = new ZoneChangedEventMatcher();

    // TODO(effect analysis): Support excluded zones, cause and fizzle checks, cast/history
    // constraints, delayed triggers, limited/conditional ChangesZoneAll forms, batches containing
    // unrelated movements, and events whose old/new card representations differ. Token creation
    // currently supplies only None-to-Battlefield events.

    private ZoneChangedEventMatcher() {
    }

    @Override
    public List<EffectMatch> match(final EffectProduction production,
            final EffectConsequence consequence) {
        if (production.type() != EffectType.ZONE_CHANGED
                || consequence.observedType() != EffectType.ZONE_CHANGED) {
            return List.of();
        }
        if (consequence.trigger().getMode() == TriggerType.ChangesZoneAll) {
            return matchBatch(production, consequence);
        }

        final List<EffectMatch> matches = new ArrayList<>();
        for (final EffectEvent event : production.events()) {
            final EffectEvent.Subject subject = event.subjects().get(0);
            if (!(subject.value() instanceof Card moved)) {
                continue;
            }
            final Map<AbilityKey, Object> runParams = new EnumMap<>(AbilityKey.class);
            runParams.putAll(event.triggerParameters());
            if (matches(consequence.trigger(), moved, runParams)
                    && EffectEventMatchUtils.passesCommon(consequence, runParams)) {
                matches.add(new EffectMatch(new EffectEvent(event.type(), event.player(),
                        List.of(new EffectEvent.Subject(moved, 1)), runParams),
                        subject.occurrences()));
            }
        }
        return matches;
    }

    private static List<EffectMatch> matchBatch(final EffectProduction production,
            final EffectConsequence consequence) {
        final CardZoneTable movements = new CardZoneTable();
        final List<EffectEvent.Subject> subjects = new ArrayList<>();
        for (final EffectEvent event : production.events()) {
            final ZoneType origin = zoneType(event.triggerParameters().get(AbilityKey.Origin));
            final ZoneType destination = zoneType(
                    event.triggerParameters().get(AbilityKey.Destination));
            if (destination == null) {
                return List.of();
            }
            for (final EffectEvent.Subject subject : event.subjects()) {
                if (!(subject.value() instanceof Card moved)) {
                    continue;
                }
                subjects.add(subject);
                for (int i = 0; i < subject.occurrences(); i++) {
                    // CardZoneTable is set-like, while EffectEvent compresses equivalent objects
                    // into one prototype plus a count. Give each represented token a distinct
                    // analysis-only identity so TriggerCount$Amount observes the full batch.
                    final Card represented = i == 0
                            ? moved : EffectAnalysisCardFactory.copyWithDistinctIdentity(moved);
                    movements.put(origin, destination, represented);
                }
            }
        }
        if (subjects.isEmpty()) {
            return List.of();
        }

        final Map<AbilityKey, Object> runParams = new EnumMap<>(AbilityKey.class);
        runParams.put(AbilityKey.Cards, movements);
        runParams.put(AbilityKey.Cause,
                production.events().get(0).triggerParameters().get(AbilityKey.Cause));
        if (!EffectEventMatchUtils.passes(consequence, runParams)) {
            return List.of();
        }
        final EffectEvent matched = new EffectEvent(EffectType.ZONE_CHANGED,
                production.events().get(0).player(), subjects, runParams);
        return List.of(new EffectMatch(matched, 1));
    }

    private static ZoneType zoneType(final Object value) {
        if (value == null) {
            return ZoneType.None;
        }
        return value instanceof ZoneType zone ? zone : ZoneType.smartValueOf(value.toString());
    }

    private static boolean matches(final Trigger trigger, final Card moved,
            final Map<AbilityKey, Object> runParams) {
        return matchesZone(trigger, "Origin", runParams.get(AbilityKey.Origin))
                && matchesZone(trigger, "Destination", runParams.get(AbilityKey.Destination))
                && trigger.matchesValidParam("ValidCard", moved);
    }

    private static boolean matchesZone(final Trigger trigger, final String parameter,
            final Object actualZone) {
        if (!trigger.hasParam(parameter) || "Any".equals(trigger.getParam(parameter))) {
            return true;
        }
        if (!(actualZone instanceof String actual)) {
            return false;
        }
        for (final String allowed : trigger.getParam(parameter).split(",")) {
            if (allowed.trim().equals(actual)) {
                return true;
            }
        }
        return false;
    }
}
