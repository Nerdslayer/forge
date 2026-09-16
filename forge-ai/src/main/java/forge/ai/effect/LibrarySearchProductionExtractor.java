package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/** Extracts known future SearchedLibrary events from direct library changes. */
final class LibrarySearchProductionExtractor implements EffectProductionExtractor {
    static final LibrarySearchProductionExtractor INSTANCE =
            new LibrarySearchProductionExtractor();

    // TODO(effect analysis): Support Dig/Seek/tutor-specific APIs, search costs and timing,
    // alternate-library origins, library-search restrictions, optional/conditional searches,
    // and hidden result quality. No card identity is inferred from a search event.
    private LibrarySearchProductionExtractor() {
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final Trigger trigger) {
        final ProductionOpportunity opportunity = ProductionOpportunity.fromTrigger(
                evaluatingAi, source, trigger);
        return opportunity == null ? List.of() : extractFromOpportunity(source, opportunity);
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final SpellAbility ability) {
        final ProductionOpportunity opportunity = ProductionOpportunity.fromActivatedAbility(
                source, ability);
        return opportunity == null ? List.of() : extractFromOpportunity(source, opportunity);
    }

    private static List<EffectProduction> extractFromOpportunity(final Card source,
            final ProductionOpportunity opportunity) {
        final List<EffectEvent> events = new ArrayList<>();
        SpellAbility current = opportunity.root();
        while (current != null) {
            if (EffectAbilityUtils.hasUnsupportedControlFlow(current)) {
                return List.of();
            }
            if ((current.getApi() == ApiType.ChangeZone
                    || current.getApi() == ApiType.ChangeZoneAll)
                    && looksThroughLibrary(current)) {
                events.addAll(createEvents(source, current));
            }
            current = current.getSubAbility();
        }
        return events.isEmpty() ? List.of() : List.of(new EffectProduction(source,
                EffectType.CARD_SEARCHED_OR_SELECTED, events,
                opportunity.expectedBatches()));
    }

    private static List<EffectEvent> createEvents(final Card source,
            final SpellAbility search) {
        final List<EffectEvent> events = new ArrayList<>();
        for (final Player searcher : resolveSearchers(source, search)) {
            if (searcher == null || !searcher.isInGame()) {
                continue;
            }
            final Map<AbilityKey, Object> parameters = new EnumMap<>(AbilityKey.class);
            // TriggerSearchedLibrary treats Player as the player whose library is searched and
            // Target as the searched library's owner. Setting both avoids leaking any card from
            // the hidden library while retaining the engine's exact trigger semantics.
            parameters.put(AbilityKey.Player, searcher);
            parameters.put(AbilityKey.Target, searcher);
            parameters.put(AbilityKey.Cause, search);
            events.add(new EffectEvent(EffectType.CARD_SEARCHED_OR_SELECTED, searcher,
                    List.of(new EffectEvent.Subject(searcher, 1)), parameters));
        }
        return events;
    }

    private static List<Player> resolveSearchers(final Card source,
            final SpellAbility search) {
        if (!search.hasParam("DefinedPlayer")) {
            return List.of(source.getController());
        }
        try {
            return new ArrayList<>(AbilityUtils.getDefinedPlayers(source,
                    search.getParam("DefinedPlayer"), search));
        } catch (final RuntimeException ignored) {
            return List.of();
        }
    }

    private static boolean looksThroughLibrary(final SpellAbility search) {
        if (search.hasParam("NoLooking")) {
            return false;
        }
        if (search.hasParam("Searched")) {
            return true;
        }
        if (!search.hasParam("Origin")) {
            return false;
        }
        try {
            return ZoneType.listValueOf(search.getParam("Origin")).contains(ZoneType.Library);
        } catch (final RuntimeException ignored) {
            return false;
        }
    }
}
