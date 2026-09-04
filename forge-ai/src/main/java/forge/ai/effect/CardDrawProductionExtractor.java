package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityCantDraw;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/** Extracts direct draws with known recipients and currently calculable amounts. */
final class CardDrawProductionExtractor implements EffectProductionExtractor {
    static final CardDrawProductionExtractor INSTANCE = new CardDrawProductionExtractor();

    // TODO(effect analysis): Support ordinary draw-step production, spells and stack objects,
    // targeted/dynamic recipients, draws with choices or optionality, card-characteristic
    // prediction without hidden information, replacement effects, draw-all/loot/dig/named-action
    // decomposition, additional trigger origins, and more precise future turn ordinals.

    private CardDrawProductionExtractor() {
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final Trigger trigger) {
        final ProductionOpportunity opportunity = ProductionOpportunity.fromTrigger(
                evaluatingAi, source, trigger);
        if (opportunity == null) {
            return List.of();
        }
        final SpellAbility outcome = findSupportedDrawOutcome(opportunity.root());
        final EffectProduction production = outcome == null ? null
                : createProduction(source, outcome, opportunity.expectedBatches(), false);
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
        final SpellAbility outcome = findSupportedDrawOutcome(opportunity.root());
        final EffectProduction production = outcome == null ? null
                : createProduction(source, outcome, opportunity.expectedBatches(), true);
        return production == null ? List.of() : List.of(production);
    }

    private static SpellAbility findSupportedDrawOutcome(final SpellAbility root) {
        SpellAbility current = root;
        while (current != null) {
            if (EffectAbilityUtils.hasUnsupportedControlFlow(current)) {
                return null;
            }
            if (current.getApi() == ApiType.Draw) {
                if (current.usesTargeting() || current.hasParam("OptionalDecider")
                        || current.hasParam("Upto") || !hasSupportedRecipient(current)) {
                    return null;
                }
                return current;
            }
            current = current.getSubAbility();
        }
        return null;
    }

    private static boolean hasSupportedRecipient(final SpellAbility outcome) {
        final String defined = outcome.getParamOrDefault("Defined", "You");
        return "You".equals(defined) || "Opponent".equals(defined);
    }

    private static EffectProduction createProduction(final Card source,
            final SpellAbility outcome, final int expectedBatches,
            final boolean useCurrentDrawCount) {
        outcome.setActivatingPlayer(source.getController());
        final int requestedAmount = AbilityUtils.calculateAmount(source,
                outcome.getParamOrDefault("NumCards", "1"), outcome);
        if (requestedAmount <= 0) {
            return null;
        }

        final List<EffectEvent> events = new ArrayList<>();
        for (final Player recipient : AbilityUtils.getDefinedPlayers(source,
                outcome.getParamOrDefault("Defined", "You"), outcome)) {
            final int amount = Math.min(requestedAmount, Math.min(
                    StaticAbilityCantDraw.canDrawAmount(recipient, requestedAmount),
                    recipient.getCardsIn(ZoneType.Library).size()));
            if (amount <= 0) {
                continue;
            }
            final int alreadyDrawn = useCurrentDrawCount ? recipient.getNumDrawnThisTurn() : 0;
            final boolean inCurrentDrawStep = useCurrentDrawCount
                    && recipient.getGame().getPhaseHandler().is(PhaseType.DRAW, recipient);
            final int alreadyDrawnThisStep = inCurrentDrawStep
                    ? recipient.numDrawnThisDrawStep() : 0;
            final Card unknownCard = EffectAnalysisCardFactory.createUnknownCard(
                    recipient, ZoneType.Hand);
            for (int i = 1; i <= amount; i++) {
                final Map<AbilityKey, Object> triggerParameters =
                        new EnumMap<>(AbilityKey.class);
                triggerParameters.put(AbilityKey.Card, unknownCard);
                triggerParameters.put(AbilityKey.Player, recipient);
                triggerParameters.put(AbilityKey.Number, EffectMath.add(alreadyDrawn, i));
                triggerParameters.put(AbilityKey.FirstTime,
                        inCurrentDrawStep && alreadyDrawnThisStep + i == 1);
                events.add(new EffectEvent(EffectType.CARD_DRAWN, recipient,
                        List.of(new EffectEvent.Subject(unknownCard, 1)), triggerParameters));
            }
        }
        return events.isEmpty() ? null : new EffectProduction(
                source, EffectType.CARD_DRAWN, events, expectedBatches);
    }
}
