package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.player.PlayerCollection;
import forge.game.staticability.StaticAbilityDisableTriggers;
import forge.game.trigger.TriggerType;

/** Matches token-created events against TokenCreated and TokenCreatedOnce triggers. */
final class TokenCreatedEventMatcher implements EffectEventMatcher {
    static final TokenCreatedEventMatcher INSTANCE = new TokenCreatedEventMatcher();

    private TokenCreatedEventMatcher() {
    }

    @Override
    public List<EffectMatch> match(final EffectProduction production,
            final EffectConsequence consequence) {
        if (production.type() != EffectType.TOKEN_CREATED
                || consequence.observedType() != EffectType.TOKEN_CREATED) {
            return List.of();
        }
        return consequence.trigger().getMode() == TriggerType.TokenCreatedOnce
                ? matchBatch(production, consequence) : matchIndividual(production, consequence);
    }

    private static List<EffectMatch> matchIndividual(final EffectProduction production,
            final EffectConsequence consequence) {
        final List<EffectMatch> matches = new ArrayList<>();
        int eventNumber = production.events().get(0).player().getNumTokenCreatedThisTurn();
        for (final EffectEvent event : production.events()) {
            final EffectEvent.Subject subject = event.subjects().get(0);
            final int firstEventNumber = EffectMath.add(eventNumber, 1);
            final Map<AbilityKey, Object> runParams = new EnumMap<>(AbilityKey.class);
            runParams.putAll(event.triggerParameters());
            runParams.put(AbilityKey.Num, firstEventNumber);
            if (passes(consequence, runParams)) {
                final int resolutions = consequence.trigger().hasParam("OnlyFirst")
                        ? 1 : subject.occurrences();
                matches.add(new EffectMatch(new EffectEvent(event.type(), event.player(),
                        List.of(new EffectEvent.Subject(subject.value(), 1)), runParams), resolutions));
            }
            eventNumber = EffectMath.add(eventNumber, subject.occurrences());
        }
        return matches;
    }

    private static List<EffectMatch> matchBatch(final EffectProduction production,
            final EffectConsequence consequence) {
        final List<EffectEvent.Subject> subjects = new ArrayList<>();
        for (final EffectEvent event : production.events()) {
            subjects.addAll(event.subjects());
        }

        final Map<AbilityKey, Object> runParams = new EnumMap<>(AbilityKey.class);
        final EffectEvent event = new EffectEvent(EffectType.TOKEN_CREATED,
                production.events().get(0).player(), subjects, runParams);
        runParams.put(AbilityKey.Cards, event.expandedCardSubjects());
        if (event.player().getNumTokenCreatedThisTurn() == 0) {
            runParams.put(AbilityKey.FirstTime, new PlayerCollection(event.player()));
        } else {
            runParams.put(AbilityKey.FirstTime, new PlayerCollection());
        }
        if (!passes(consequence, runParams)) {
            return List.of();
        }
        return List.of(new EffectMatch(new EffectEvent(
                event.type(), event.player(), subjects, runParams), 1));
    }

    private static boolean passes(final EffectConsequence consequence,
            final Map<AbilityKey, Object> runParams) {
        try {
            return consequence.trigger().checkActivationLimit()
                    && consequence.trigger().meetsRequirementsOnTriggeredObjects(
                            consequence.source().getGame(), runParams)
                    && consequence.trigger().performTest(runParams)
                    && !StaticAbilityDisableTriggers.disabled(
                            consequence.source().getGame(), consequence.trigger(), runParams);
        } catch (final RuntimeException ignored) {
            return false;
        }
    }
}
