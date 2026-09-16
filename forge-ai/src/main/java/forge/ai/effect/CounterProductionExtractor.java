package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.GameEntity;
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

/** Extracts direct, fixed-recipient counter-added productions. */
final class CounterProductionExtractor implements EffectProductionExtractor {
    static final CounterProductionExtractor INSTANCE = new CounterProductionExtractor();

    // TODO(effect analysis): Support targeted, distributed, multi-type, optional, ETB,
    // replacement-modified, and additional trigger-origin counter productions. PutCounterAll is
    // currently limited to one fixed untargeted battlefield batch; reference batch extraction and
    // intrinsic valuation still need recipient-population modeling.

    private CounterProductionExtractor() {
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final Trigger trigger) {
        final ProductionOpportunity opportunity = ProductionOpportunity.fromTrigger(
                evaluatingAi, source, trigger);
        if (opportunity == null) {
            return List.of();
        }
        final SpellAbility outcome = findSupportedCounterOutcome(
                opportunity.root());
        final EffectProduction production = outcome == null
                ? null : createProduction(source, outcome, opportunity.expectedBatches());
        return production == null ? List.of() : List.of(production);
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final SpellAbility ability) {
        final ProductionOpportunity opportunity =
                ProductionOpportunity.fromActivatedAbility(source, ability);
        if (opportunity == null) {
            return List.of();
        }
        final SpellAbility outcome = findSupportedCounterOutcome(opportunity.root());
        final EffectProduction production = outcome == null
                ? null : createProduction(source, outcome, opportunity.expectedBatches());
        return production == null ? List.of() : List.of(production);
    }

    private static SpellAbility findSupportedCounterOutcome(final SpellAbility root) {
        final SpellAbility outcome = findFirstOutcome(root, ApiType.PutCounter, ApiType.PutCounterAll);
        if (outcome == null || outcome.usesTargeting() || !outcome.hasParam("CounterType")
                || outcome.hasParam("CounterTypes") || outcome.hasParam("Choices")
                || outcome.hasParam("ChooseDifferent") || outcome.hasParam("DividedAsYouChoose")
                || outcome.hasParam("DividedRandomly") || outcome.hasParam("EachExistingCounter")
                || outcome.hasParam("ExistingCounter") || outcome.hasParam("PutOnEachOther")
                || outcome.hasParam("PutOnDefined") || outcome.hasParam("ETB")
                || outcome.hasParam("UpTo") || outcome.hasParam("Optional")
                || outcome.hasParam("ValidCards2") || outcome.hasParam("CounterType2")
                || outcome.hasParam("CounterNum2") || outcome.hasParam("AmountByChosenMap")
                || (outcome.hasParam("Placer") && !"You".equals(outcome.getParam("Placer")))) {
            return null;
        }
        if (outcome.getApi() == ApiType.PutCounterAll && !outcome.hasParam("ValidCards")) {
            return null;
        }
        if (outcome.getApi() != ApiType.PutCounter && outcome.getApi() != ApiType.PutCounterAll) {
            return null;
        }
        for (final String param : outcome.getMapParams().keySet()) {
            if (param.startsWith("Condition") || param.startsWith("Unless")) {
                return null;
            }
        }
        return outcome;
    }

    private static SpellAbility findFirstOutcome(final SpellAbility root, final ApiType... apis) {
        for (final ApiType api : apis) {
            final SpellAbility outcome = EffectAbilityUtils.findOutcome(root, api);
            if (outcome != null) {
                return outcome;
            }
        }
        return null;
    }

    private static EffectProduction createProduction(final Card source,
            final SpellAbility outcome, final double expectedBatches) {
        outcome.setActivatingPlayer(source.getController());
        final CounterType counterType = CounterType.getType(outcome.getParam("CounterType"));
        if (counterType == null) {
            return null;
        }
        final int amount = AbilityUtils.calculateAmount(source,
                outcome.getParamOrDefault("CounterNum", "1"), outcome);
        if (amount <= 0) {
            return null;
        }

        final List<? extends GameEntity> recipients = outcome.getApi() == ApiType.PutCounterAll
                ? allBattlefieldRecipients(source, outcome)
                : AbilityUtils.getDefinedEntities(source,
                        outcome.getParamOrDefault("Defined", "Self").split(" & "), outcome);
        final List<EffectEvent> events = new ArrayList<>();
        for (final GameEntity recipient : recipients) {
            if (!(recipient instanceof Card) && !(recipient instanceof Player)
                    || !recipient.canReceiveCounters(counterType)) {
                continue;
            }
            final Map<AbilityKey, Object> triggerParameters = new EnumMap<>(AbilityKey.class);
            if (recipient instanceof Card card) {
                triggerParameters.put(AbilityKey.Card, card);
            } else {
                triggerParameters.put(AbilityKey.Player, recipient);
            }
            triggerParameters.put(AbilityKey.Source, source.getController());
            triggerParameters.put(AbilityKey.CounterType, counterType);
            events.add(new EffectEvent(EffectType.COUNTER_ADDED, source.getController(),
                    List.of(new EffectEvent.Subject(recipient, amount)), triggerParameters));
        }
        return events.isEmpty() ? null : new EffectProduction(
                source, EffectType.COUNTER_ADDED, events, expectedBatches);
    }

    private static List<Card> allBattlefieldRecipients(final Card source,
            final SpellAbility outcome) {
        final CardCollectionView battlefield = source.getGame().getCardsIn(ZoneType.Battlefield);
        return CardLists.getValidCards(battlefield, outcome.getParam("ValidCards"),
                source.getController(), source, outcome);
    }
}
