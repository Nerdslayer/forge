package forge.ai.effect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.ai.ComputerUtilCard;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.cost.CostPart;
import forge.game.cost.CostSacrifice;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/** Extracts known sacrifices and their resulting battlefield-to-graveyard events. */
final class SacrificeProductionExtractor implements EffectProductionExtractor {
    static final SacrificeProductionExtractor INSTANCE = new SacrificeProductionExtractor();

    // TODO(effect analysis): Support optional, random, targeted, SacEachValid, variable/all,
    // multi-definition, and choice-sensitive sacrifice effects; non-activated sacrifice costs;
    // destination and sacrifice replacement effects; sacrificed-card value dependencies; and
    // future/repeated activation likelihood. Destruction and lethal-damage deaths are separate.

    private SacrificeProductionExtractor() {
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final Trigger trigger) {
        final ProductionOpportunity opportunity = ProductionOpportunity.fromTrigger(
                evaluatingAi, source, trigger);
        return opportunity == null ? List.of()
                : extractFromOpportunity(source, opportunity, false);
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final SpellAbility ability) {
        final ProductionOpportunity opportunity =
                ProductionOpportunity.fromActivatedAbility(source, ability);
        return opportunity == null ? List.of()
                : extractFromOpportunity(source, opportunity, true);
    }

    private static List<EffectProduction> extractFromOpportunity(final Card source,
            final ProductionOpportunity opportunity, final boolean includeCosts) {
        final List<EffectProduction> productions = new ArrayList<>();
        if (includeCosts) {
            final Map<Player, List<Card>> costSacrifices = resolveCostSacrifices(
                    opportunity.root());
            productions.addAll(PermanentDepartureEventFactory.createSacrificeProductions(
                    source, opportunity.root(), costSacrifices, opportunity.expectedBatches()));
        }

        final SpellAbility sacrifice = EffectAbilityUtils.findOutcome(
                opportunity.root(), ApiType.Sacrifice);
        if (sacrifice != null) {
            final Map<Player, List<Card>> effectSacrifices = resolveEffectSacrifices(sacrifice);
            productions.addAll(PermanentDepartureEventFactory.createSacrificeProductions(
                    source, opportunity.root(), effectSacrifices,
                    opportunity.expectedBatches()));
        }
        return productions;
    }

    private static Map<Player, List<Card>> resolveCostSacrifices(final SpellAbility ability) {
        if (ability.getPayCosts() == null) {
            return Map.of();
        }
        final Player payer = ability.getActivatingPlayer();
        final List<Card> selected = new ArrayList<>();
        final Set<Card> unavailable = new LinkedHashSet<>();
        for (final CostPart part : ability.getPayCosts().getCostParts()) {
            if (!(part instanceof CostSacrifice sacrifice)) {
                continue;
            }
            final Integer amount = sacrifice.convertAmount();
            if (amount == null || amount <= 0 || "OriginalHost".equals(sacrifice.getType())) {
                return Map.of();
            }
            if (sacrifice.payCostFromSource()) {
                final Card host = ability.getHostCard();
                if (unavailable.contains(host) || !host.canBeSacrificedBy(ability, false)) {
                    return Map.of();
                }
                selected.add(host);
                unavailable.add(host);
                continue;
            }
            final List<Card> choices = legalSacrifices(
                    payer, sacrifice.getType(), ability, unavailable, false);
            if (choices.size() < amount) {
                return Map.of();
            }
            selected.addAll(choices.subList(0, amount));
            unavailable.addAll(choices.subList(0, amount));
        }
        return selected.isEmpty() ? Map.of() : Map.of(payer, selected);
    }

    private static Map<Player, List<Card>> resolveEffectSacrifices(
            final SpellAbility sacrifice) {
        if (sacrifice.usesTargeting() || sacrifice.hasParam("Optional")
                || sacrifice.hasParam("Random") || sacrifice.hasParam("SacEachValid")
                || sacrifice.hasParam("Destroy") || sacrifice.hasParam("Echo")
                || sacrifice.hasParam("CumulativeUpkeep")
                || EffectAbilityUtils.hasUnsupportedControlFlow(sacrifice)) {
            return Map.of();
        }
        final int amount;
        try {
            amount = Integer.parseInt(sacrifice.getParamOrDefault("Amount", "1"));
        } catch (final NumberFormatException ignored) {
            return Map.of();
        }
        if (amount <= 0) {
            return Map.of();
        }

        final String valid = sacrifice.getParamOrDefault("SacValid", "Self");
        if ("Self".equals(valid)) {
            final Card host = sacrifice.getHostCard();
            return host.canBeSacrificedBy(sacrifice, true)
                    ? Map.of(host.getController(), List.of(host)) : Map.of();
        }

        final PlayerCollection players = AbilityUtils.getDefinedPlayers(sacrifice.getHostCard(),
                sacrifice.getParamOrDefault("Defined", "You"), sacrifice);
        final Map<Player, List<Card>> sacrifices = new LinkedHashMap<>();
        for (final Player player : players) {
            final List<Card> choices = legalSacrifices(
                    player, valid, sacrifice, Set.of(), true);
            if (sacrifice.hasParam("StrictAmount") && choices.size() < amount) {
                return Map.of();
            }
            final int selectedAmount = Math.min(amount, choices.size());
            if (selectedAmount > 0) {
                sacrifices.put(player, new ArrayList<>(choices.subList(0, selectedAmount)));
            }
        }
        return sacrifices;
    }

    private static List<Card> legalSacrifices(final Player player, final String valid,
            final SpellAbility ability, final Set<Card> unavailable, final boolean effect) {
        final CardCollection battlefield = new CardCollection(
                player.getCardsIn(ZoneType.Battlefield));
        CardCollectionView legal = AbilityUtils.filterListByType(battlefield, valid, ability);
        legal = CardLists.filter(legal, CardPredicates.canBeSacrificedBy(ability, effect));
        final List<Card> choices = new ArrayList<>();
        for (final Card card : legal) {
            if (!unavailable.contains(card)) {
                choices.add(card);
            }
        }
        choices.sort(Comparator.comparingInt(card ->
                ComputerUtilCard.evaluatePermanent(player, card)));
        return choices;
    }
}
