package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

/** Extracts direct, fixed-recipient counter-removal productions. */
final class CounterRemovalProductionExtractor implements EffectProductionExtractor {
    static final CounterRemovalProductionExtractor INSTANCE =
            new CounterRemovalProductionExtractor();

    private static final Set<String> UNSUPPORTED_PARAMS = Set.of(
            "Choices", "ChoiceNum", "CounterTypes", "CounterType2", "CounterNum2",
            "EachExistingCounter", "ExistingCounter", "PutOnEachOther", "PutOnDefined",
            "Optional", "UpTo", "DividedAsYouChoose", "DividedRandomly", "AmountByChosenMap",
            "RememberRemoved", "UnlessCost", "ConditionCheckSVar", "ConditionSVarCompare");

    // TODO(effect analysis): Support targeted or selected recipients, Any/All counter choices,
    // counter movement and AddOrRemoveCounter, non-battlefield cards, player counters,
    // replacement-modified removal, and removal amounts that depend on combat or other events.

    private CounterRemovalProductionExtractor() {
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
        final SpellAbility outcome = EffectAbilityUtils.findOutcome(opportunity.root(),
                ApiType.RemoveCounter);
        final SpellAbility groupOutcome = EffectAbilityUtils.findOutcome(opportunity.root(),
                ApiType.RemoveCounterAll);
        final List<EffectProduction> productions = new ArrayList<>();
        if (outcome != null) {
            final EffectProduction production = createProduction(source, outcome,
                    opportunity.expectedBatches(), false);
            if (production != null) {
                productions.add(production);
            }
        }
        if (groupOutcome != null) {
            final EffectProduction production = createProduction(source, groupOutcome,
                    opportunity.expectedBatches(), true);
            if (production != null) {
                productions.add(production);
            }
        }
        return productions;
    }

    private static EffectProduction createProduction(final Card source,
            final SpellAbility outcome, final double expectedBatches, final boolean group) {
        outcome.setActivatingPlayer(source.getController());
        outcome.resetTargets();
        if (!isSupported(outcome, group)) {
            return null;
        }
        final CounterType counterType = CounterType.getType(outcome.getParam("CounterType"));
        if (counterType == null || "Any".equalsIgnoreCase(outcome.getParam("CounterType"))
                || "All".equalsIgnoreCase(outcome.getParam("CounterType"))) {
            return null;
        }
        final List<Card> recipients = group
                ? allBattlefieldRecipients(source, outcome)
                : new ArrayList<>(AbilityUtils.getDefinedCards(source,
                        outcome.getParamOrDefault("Defined", "Self"), outcome));
        final List<EffectEvent> events = new ArrayList<>();
        for (final Card recipient : recipients) {
            final int existing = recipient.getCounters(counterType);
            final int amount = removalAmount(source, outcome, existing);
            if (amount <= 0 || !recipient.canRemoveCounters(counterType)) {
                continue;
            }
            final int actualAmount = Math.min(existing, amount);
            if (actualAmount <= 0) {
                continue;
            }
            final Map<AbilityKey, Object> parameters = new EnumMap<>(AbilityKey.class);
            parameters.put(AbilityKey.Card, recipient);
            parameters.put(AbilityKey.CounterType, counterType);
            parameters.put(AbilityKey.CounterAmount, actualAmount);
            parameters.put(AbilityKey.NewCounterAmount, existing - actualAmount);
            parameters.put(AbilityKey.Player, source.getController());
            parameters.put(AbilityKey.Source, source.getController());
            events.add(new EffectEvent(EffectType.COUNTER_REMOVED, source.getController(),
                    List.of(new EffectEvent.Subject(recipient, actualAmount)), parameters));
        }
        return events.isEmpty() ? null : new EffectProduction(source, EffectType.COUNTER_REMOVED,
                events, expectedBatches);
    }

    private static boolean isSupported(final SpellAbility outcome, final boolean group) {
        if (outcome.getSubAbility() != null || EffectAbilityUtils.hasUnsupportedControlFlow(outcome)
                || outcome.usesTargeting()
                || outcome.getMapParams().keySet().stream().anyMatch(UNSUPPORTED_PARAMS::contains)
                || !outcome.hasParam("CounterType") || !outcome.hasParam("CounterNum")) {
            return false;
        }
        if (group && (!outcome.hasParam("ValidCards") || outcome.hasParam("Defined"))) {
            return false;
        }
        return !group || "Battlefield".equalsIgnoreCase(
                outcome.getParamOrDefault("ValidZone", "Battlefield"));
    }

    private static int removalAmount(final Card source, final SpellAbility outcome,
            final int existing) {
        final String raw = outcome.getParam("CounterNum");
        if ("All".equalsIgnoreCase(raw)) {
            return existing;
        }
        try {
            return AbilityUtils.calculateAmount(source, raw, outcome);
        } catch (final RuntimeException ignored) {
            return 0;
        }
    }

    private static List<Card> allBattlefieldRecipients(final Card source,
            final SpellAbility outcome) {
        final CardCollectionView battlefield = source.getGame().getCardsIn(ZoneType.Battlefield);
        return new ArrayList<>(CardLists.getValidCards(battlefield,
                outcome.getParam("ValidCards"), source.getController(), source, outcome));
    }
}
