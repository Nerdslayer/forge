package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/** Extracts fixed counter moves that have a known source group and destination. */
final class CounterMoveProductionExtractor implements EffectProductionExtractor {
    static final CounterMoveProductionExtractor INSTANCE = new CounterMoveProductionExtractor();

    // TODO(effect analysis): Support targeted moves, Any/All counter choices, multiple targets,
    // selected source subsets, per-source dynamic amounts, replacement effects, non-battlefield
    // counters, and the richer AddOrRemoveCounter family.

    private CounterMoveProductionExtractor() {
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
        final SpellAbility move = EffectAbilityUtils.findOutcome(opportunity.root(),
                ApiType.MoveCounter);
        if (move == null || !isSupported(move)) {
            return List.of();
        }
        move.setActivatingPlayer(source.getController());
        move.resetTargets();

        final CounterType counterType = CounterType.getType(move.getParam("CounterType"));
        if (counterType == null || "Any".equalsIgnoreCase(move.getParam("CounterType"))
                || "All".equalsIgnoreCase(move.getParam("CounterType"))) {
            return List.of();
        }
        final Card destination = resolveDestination(source, move);
        if (destination == null || !destination.canReceiveCounters(counterType)) {
            return List.of();
        }

        final List<EffectEvent> removed = new ArrayList<>();
        int movedAmount = 0;
        for (final Card from : resolveSources(source, move)) {
            if (from == destination || !from.canRemoveCounters(counterType)) {
                continue;
            }
            final int existing = from.getCounters(counterType);
            final int amount = Math.min(existing, moveAmount(source, move, existing));
            if (amount <= 0) {
                continue;
            }
            movedAmount = EffectMath.add(movedAmount, amount);
            removed.add(createEvent(source, from, counterType, amount, existing,
                    EffectType.COUNTER_REMOVED));
        }
        if (movedAmount <= 0) {
            return List.of();
        }

        final EffectEvent added = createEvent(source, destination, counterType, movedAmount,
                destination.getCounters(counterType), EffectType.COUNTER_ADDED);
        return List.of(
                new EffectProduction(source, EffectType.COUNTER_REMOVED, removed,
                        opportunity.expectedBatches()),
                new EffectProduction(source, EffectType.COUNTER_ADDED, List.of(added),
                        opportunity.expectedBatches()));
    }

    private static boolean isSupported(final SpellAbility move) {
        return !move.usesTargeting()
                && !EffectAbilityUtils.hasUnsupportedControlFlow(move)
                && move.hasParam("ValidSource")
                && move.hasParam("Defined")
                && move.hasParam("CounterType")
                && move.hasParam("CounterNum")
                && !move.hasParam("Source")
                && !move.hasParam("CounterTypes")
                && !move.hasParam("EachNotOn")
                && !move.hasParam("Optional")
                && ("All".equalsIgnoreCase(move.getParam("CounterNum"))
                        || isPositiveInteger(move.getParam("CounterNum")));
    }

    private static Card resolveDestination(final Card source, final SpellAbility move) {
        final List<Card> defined = new ArrayList<>(AbilityUtils.getDefinedCards(source,
                move.getParam("Defined"), move));
        return defined.size() == 1 ? defined.get(0) : null;
    }

    private static List<Card> resolveSources(final Card source, final SpellAbility move) {
        final CardCollectionView battlefield = source.getGame().getCardsIn(ZoneType.Battlefield);
        return new ArrayList<>(CardLists.getValidCards(battlefield,
                move.getParam("ValidSource"), source.getController(), source, move));
    }

    private static int moveAmount(final Card source, final SpellAbility move, final int existing) {
        final String raw = move.getParam("CounterNum");
        if ("All".equalsIgnoreCase(raw)) {
            return existing;
        }
        try {
            return AbilityUtils.calculateAmount(source, raw, move);
        } catch (final RuntimeException ignored) {
            return 0;
        }
    }

    private static EffectEvent createEvent(final Card source, final Card recipient,
            final CounterType counterType, final int amount, final int existing,
            final EffectType type) {
        final Map<AbilityKey, Object> parameters = new EnumMap<>(AbilityKey.class);
        parameters.put(AbilityKey.Card, recipient);
        parameters.put(AbilityKey.CounterType, counterType);
        parameters.put(AbilityKey.CounterAmount, amount);
        parameters.put(AbilityKey.NewCounterAmount,
                type == EffectType.COUNTER_REMOVED ? existing - amount : existing + amount);
        parameters.put(AbilityKey.Player, source.getController());
        parameters.put(AbilityKey.Source, source.getController());
        return new EffectEvent(type, source.getController(),
                List.of(new EffectEvent.Subject(recipient, amount)), parameters);
    }

    private static boolean isPositiveInteger(final String value) {
        try {
            return Integer.parseInt(value) > 0;
        } catch (final NumberFormatException ignored) {
            return false;
        }
    }
}
