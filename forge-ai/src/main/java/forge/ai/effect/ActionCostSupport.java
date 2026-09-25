/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package forge.ai.effect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import forge.ai.CardResourceValueEvaluator;
import forge.ai.ComputerUtilCard;
import forge.ai.PlayerResourceValueEvaluator;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.card.CounterType;
import forge.game.cost.Cost;
import forge.game.cost.CostDamage;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostExile;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostPutCounter;
import forge.game.cost.CostRemoveAnyCounter;
import forge.game.cost.CostRemoveCounter;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTap;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Extracts the small, deterministic subset of additional costs that the action optimizer can
 * carry through a combination. It intentionally rejects choices whose payment resource cannot be
 * represented without simulating the payment UI.
 */
final class ActionCostSupport {
    private ActionCostSupport() {
    }

    static ActionCostAnalysis analyze(final SpellAbility ability, final Player payer) {
        if (ability == null || payer == null || ability.getPayCosts() == null) {
            return unsupported("Missing action or payer");
        }
        final Cost cost = ability.getPayCosts();
        final Card source = ability.getHostCard();
        final List<Card> consumed = new ArrayList<>();
        final List<Card> tapped = new ArrayList<>();
        final List<ActionResourceRequirement> requirements = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();
        int explicitValue = 0;

        if (cost.hasTapCost() && source != null) {
            tapped.add(source);
        }

        for (final CostPart part : cost.getCostParts()) {
            if (part instanceof CostPartMana || part instanceof CostTap) {
                continue;
            }
            if (part instanceof CostPayLife || part instanceof CostDamage) {
                final Integer amount = numericAmount(part);
                if (amount == null) {
                    return unsupported("Dynamic life or damage cost");
                }
                explicitValue = add(explicitValue, lifeCostValue(payer, amount));
                reasons.add("Pays " + amount + " life-equivalent as an additional cost");
                continue;
            }
            if (part instanceof CostSacrifice sacrifice) {
                final int amount = numericAmountOrZero(sacrifice);
                if (amount < 0) {
                    return unsupported("Dynamic sacrifice amount");
                }
                if (sacrifice.payCostFromSource()) {
                    if (amount != 1 || source == null) {
                        return unsupported("Source-bound sacrifice is not fixed at one card");
                    }
                    consumed.add(source);
                    explicitValue = add(explicitValue, cardLossValue(payer, source));
                    reasons.add("Consumes the source by sacrifice");
                } else {
                    final CardCollection candidates = sacrificeCandidates(ability, payer, sacrifice);
                    if (!addRequirement(requirements, candidates, amount,
                            ActionResourceRequirement.ResourceKind.BATTLEFIELD_PERMANENT,
                            false, "sacrifice")) {
                        return unsupported("Sacrifice candidates are not representable");
                    }
                    explicitValue = add(explicitValue, minimumCardLoss(payer, candidates, amount));
                    reasons.add("Selects " + amount + " sacrifice resource(s)");
                }
                continue;
            }
            if (part instanceof CostDiscard discard) {
                final int amount = numericAmountOrZero(discard);
                if (amount < 0 || discard.payCostFromSource()) {
                    return unsupported("Source-bound or dynamic discard cost");
                }
                if ("Hand".equals(discard.getType()) || "LastDrawn".equals(discard.getType())
                        || discard.getType().contains("+")) {
                    return unsupported("Special discard selection");
                }
                final CardCollection candidates = discardCandidates(ability, payer, discard);
                if (!addRequirement(requirements, candidates, amount,
                        ActionResourceRequirement.ResourceKind.HAND_CARD, false, "discard")) {
                    return unsupported("Discard candidates are not representable");
                }
                explicitValue = add(explicitValue,
                        "Random".equals(discard.getType())
                                ? PlayerResourceValueEvaluator.evaluateRandomDiscard(
                                        payer.getCardsIn(ZoneType.Hand).size(), amount)
                                : minimumCardLoss(payer, candidates, amount));
                reasons.add("Discards " + amount + " card resource(s)");
                continue;
            }
            if (part instanceof CostExile exile) {
                final int amount = numericAmountOrZero(exile);
                if (amount < 0 || exile.getFrom().contains(ZoneType.Library)
                        || exile.getType().contains("+") || "All".equals(exile.getType())) {
                    return unsupported("Dynamic or hidden exile cost");
                }
                if (exile.payCostFromSource()) {
                    if (amount != 1 || source == null) {
                        return unsupported("Source-bound exile is not fixed at one card");
                    }
                    consumed.add(source);
                    explicitValue = add(explicitValue, cardLossValue(payer, source));
                    reasons.add("Consumes the source by exile");
                } else if (exile.zoneRestriction != 1) {
                    return unsupported("Exile cost can select cards outside the payer's resources");
                } else {
                    final CardCollection candidates = exileCandidates(ability, payer, exile);
                    if (!addRequirement(requirements, candidates, amount,
                            ActionResourceRequirement.ResourceKind.ZONE_CARD, false, "exile")) {
                        return unsupported("Exile candidates are not representable");
                    }
                    explicitValue = add(explicitValue, minimumCardLoss(payer, candidates, amount));
                    reasons.add("Exiles " + amount + " card resource(s)");
                }
                continue;
            }
            if (part instanceof CostPutCounter put) {
                final int amount = numericAmountOrZero(put);
                if (amount < 0 || put.getCounter() == null
                        || !put.getCounter().is(forge.game.card.CounterEnumType.LOYALTY)
                        || !put.payCostFromSource() || source == null || !source.isPlaneswalker()) {
                    return unsupported("Only fixed loyalty additions to the planeswalker source are modeled");
                }
                explicitValue = add(explicitValue,
                        -PlaneswalkerLoyaltyValue.change(payer, source, amount));
                reasons.add("Adds " + amount + " loyalty counter resource(s)");
                continue;
            }
            if (part instanceof CostRemoveCounter remove) {
                final int amount = numericAmountOrZero(remove);
                if (amount < 0 || amount == 0) {
                    if (amount == 0) {
                        continue;
                    }
                    return unsupported("Dynamic counter-removal amount");
                }
                final CardCollection candidates = counterCandidates(ability, payer, remove);
                if (remove.payCostFromSource()) {
                    if (source == null || !addCounterRequirement(requirements, source, remove.counter,
                            amount, true, "source counter removal")) {
                        return unsupported("Source counter resource is unavailable");
                    }
                } else if (!addCounterRequirement(requirements, candidates, remove.counter, amount,
                        true, "counter removal")) {
                    return unsupported("Counter-removal resources are not representable");
                }
                final int counterValue = remove.counter != null
                        && remove.counter.is(forge.game.card.CounterEnumType.LOYALTY)
                        && remove.payCostFromSource() && source != null && source.isPlaneswalker()
                                ? -PlaneswalkerLoyaltyValue.change(payer, source, -amount)
                                : counterCostValue(remove.counter, amount);
                explicitValue = add(explicitValue, counterValue);
                reasons.add("Removes " + amount + " counter resource(s)");
                continue;
            }
            if (part instanceof CostRemoveAnyCounter removeAny) {
                final int amount = numericAmountOrZero(removeAny);
                if (amount < 0 || amount == 0) {
                    if (amount == 0) {
                        continue;
                    }
                    return unsupported("Dynamic any-counter amount");
                }
                final Card sourceCard = ability.getHostCard();
                final CardCollection candidates = counterCandidates(ability, payer, removeAny);
                if (removeAny.payCostFromSource()) {
                    if (sourceCard == null || !addAnyCounterRequirement(requirements, sourceCard,
                            amount, true, "source counter removal")) {
                        return unsupported("Source counter resource is unavailable");
                    }
                } else if (!addAnyCounterRequirement(requirements, candidates, amount, true,
                        "counter removal")) {
                    return unsupported("Any-counter resources are not representable");
                }
                explicitValue = add(explicitValue, counterCostValue(null, amount));
                reasons.add("Removes " + amount + " counter resource(s)");
                continue;
            }
            return unsupported("Unsupported additional cost: " + part.getClass().getSimpleName());
        }

        return new ActionCostAnalysis(ActionResourceFootprint.withRequirements(consumed, tapped,
                requirements, false), explicitValue, ValuationCompleteness.COMPLETE, reasons);
    }

    private static CardCollection sacrificeCandidates(final SpellAbility ability, final Player payer,
            final CostSacrifice cost) {
        CardCollection candidates = CardLists.getValidCards(payer.getCardsIn(ZoneType.Battlefield),
                cost.getType().split(";"), payer, ability.getHostCard(), ability);
        return CardLists.filter(candidates, CardPredicates.canBeSacrificedBy(ability, false));
    }

    private static CardCollection discardCandidates(final SpellAbility ability, final Player payer,
            final CostDiscard cost) {
        CardCollection candidates = new CardCollection(payer.getCardsIn(ZoneType.Hand));
        if (!"Random".equals(cost.getType()) && !"Card".equals(cost.getType())) {
            candidates = CardLists.getValidCards(candidates, cost.getType().split(";"), payer,
                    ability.getHostCard(), ability);
        }
        return payer.canDiscardBy(ability, false) ? candidates : new CardCollection();
    }

    private static CardCollection exileCandidates(final SpellAbility ability, final Player payer,
            final CostExile cost) {
        CardCollection candidates = new CardCollection(payer.getCardsIn(cost.getFrom()));
        candidates = CardLists.filter(candidates, CardPredicates.canExiledBy(ability, false));
        if (!cost.getType().contains("X")) {
            candidates = CardLists.getValidCards(candidates, cost.getType().split(";"), payer,
                    ability.getHostCard(), ability);
        } else {
            return new CardCollection();
        }
        return candidates;
    }

    private static CardCollection counterCandidates(final SpellAbility ability, final Player payer,
            final CostRemoveCounter cost) {
        CardCollection candidates = CardLists.getValidCards(payer.getCardsIn(cost.zone),
                cost.getType().split(";"), payer, ability.getHostCard(), ability);
        return CardLists.filter(candidates, card -> counterCapacity(card, cost.counter) > 0
                && card.canRemoveCounters(cost.counter));
    }

    private static CardCollection counterCandidates(final SpellAbility ability, final Player payer,
            final CostRemoveAnyCounter cost) {
        CardCollection candidates = CardLists.getValidCards(payer.getCardsIn(ZoneType.Battlefield),
                cost.getType().split(";"), payer, ability.getHostCard(), ability);
        return CardLists.filter(candidates, card -> card.getNumAllCounters() > 0
                && card.canRemoveCounters(null));
    }

    private static boolean addRequirement(final List<ActionResourceRequirement> requirements,
            final CardCollection candidates, final int amount,
            final ActionResourceRequirement.ResourceKind kind, final boolean sameObject,
            final String description) {
        if (amount == 0) {
            return true;
        }
        if (candidates == null || candidates.size() < amount) {
            return false;
        }
        final List<ActionResourceRequirement.ResourceCandidate> resources = candidates.stream()
                .map(card -> new ActionResourceRequirement.ResourceCandidate(card, 1))
                .toList();
        requirements.add(new ActionResourceRequirement(kind, resources, amount, sameObject, false,
                description));
        return true;
    }

    private static boolean addCounterRequirement(final List<ActionResourceRequirement> requirements,
            final Card card, final CounterType counter, final int amount, final boolean sameObject,
            final String description) {
        if (card == null || counterCapacity(card, counter) < amount) {
            return false;
        }
        requirements.add(new ActionResourceRequirement(ActionResourceRequirement.ResourceKind.COUNTER,
                List.of(new ActionResourceRequirement.ResourceCandidate(card,
                        counterCapacity(card, counter))), amount, sameObject, false, description));
        return true;
    }

    private static boolean addAnyCounterRequirement(final List<ActionResourceRequirement> requirements,
            final Card card, final int amount, final boolean sameObject, final String description) {
        if (card == null || card.getNumAllCounters() < amount) {
            return false;
        }
        requirements.add(new ActionResourceRequirement(ActionResourceRequirement.ResourceKind.COUNTER,
                List.of(new ActionResourceRequirement.ResourceCandidate(card, card.getNumAllCounters())),
                amount, sameObject, false, description));
        return true;
    }

    private static boolean addAnyCounterRequirement(final List<ActionResourceRequirement> requirements,
            final CardCollection candidates, final int amount, final boolean sameObject,
            final String description) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        final List<ActionResourceRequirement.ResourceCandidate> resources = candidates.stream()
                .map(card -> new ActionResourceRequirement.ResourceCandidate(card,
                        card.getNumAllCounters()))
                .filter(candidate -> candidate.capacity() >= amount)
                .toList();
        if (resources.isEmpty()) {
            return false;
        }
        requirements.add(new ActionResourceRequirement(ActionResourceRequirement.ResourceKind.COUNTER,
                resources, amount, sameObject, false, description));
        return true;
    }

    private static boolean addCounterRequirement(final List<ActionResourceRequirement> requirements,
            final CardCollection candidates, final CounterType counter, final int amount,
            final boolean sameObject, final String description) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        final List<ActionResourceRequirement.ResourceCandidate> resources = candidates.stream()
                .map(card -> new ActionResourceRequirement.ResourceCandidate(card,
                        counterCapacity(card, counter)))
                .filter(candidate -> candidate.capacity() >= amount)
                .toList();
        if (resources.isEmpty()) {
            return false;
        }
        requirements.add(new ActionResourceRequirement(ActionResourceRequirement.ResourceKind.COUNTER,
                resources, amount, sameObject, false, description));
        return true;
    }

    private static int counterCapacity(final Card card, final CounterType counter) {
        return counter == null ? card.getNumAllCounters() : card.getCounters(counter);
    }

    private static Integer numericAmount(final CostPart part) {
        return part.getAmount() != null && part.getAmount().matches("\\d+")
                ? Integer.valueOf(part.getAmount()) : null;
    }

    private static int numericAmountOrZero(final CostPart part) {
        final Integer amount = numericAmount(part);
        return amount == null ? -1 : amount;
    }

    private static int minimumCardLoss(final Player payer, final Iterable<Card> candidates,
            final int amount) {
        final List<Integer> values = new ArrayList<>();
        for (final Card card : candidates) {
            values.add(cardLossValue(payer, card));
        }
        values.sort(Comparator.naturalOrder());
        int result = 0;
        for (int i = 0; i < Math.min(amount, values.size()); i++) {
            result = add(result, values.get(i));
        }
        return result;
    }

    private static int cardLossValue(final Player payer, final Card card) {
        if (card == null) {
            return 0;
        }
        if (card.isInZone(ZoneType.Battlefield)) {
            return Math.max(0, ComputerUtilCard.evaluatePermanent(payer, card));
        }
        if (card.isInZone(ZoneType.Hand)) {
            final CardValueBreakdown handValue = UnifiedCardValueEvaluator.evaluateCard(card,
                    HandValuationContext.fullHand(payer, card.getOwner()));
            return Math.max(0, handValue.netValue());
        }
        return CardResourceValueEvaluator.evaluateNextCard(0);
    }

    private static int lifeCostValue(final Player payer, final int amount) {
        return Math.max(0, EffectMath.negate(PlayerResourceValueEvaluator.evaluateLifeChange(
                payer.getLife(), payer.getLife() - amount)));
    }

    private static int counterCostValue(final CounterType counter, final int amount) {
        final int perCounter = counter == null ? 10
                : counter.getName().equalsIgnoreCase("loyalty") ? 12 : 8;
        return add(0, perCounter * amount);
    }

    private static ActionCostAnalysis unsupported(final String reason) {
        return new ActionCostAnalysis(ActionResourceFootprint.withRequirements(List.of(), List.of(),
                List.of(), true), 0, ValuationCompleteness.UNSUPPORTED, List.of(reason));
    }

    private static int add(final int left, final int right) {
        final long result = (long) left + right;
        return result >= Integer.MAX_VALUE ? Integer.MAX_VALUE
                : result <= Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) result;
    }
}
