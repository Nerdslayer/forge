package forge.ai.effect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;

/** Resolves targeted, defined, and group card recipients for consequence evaluators. */
final class AffectedCardResolver {
    // TODO(effect analysis): Divided allocation and non-battlefield recipients. The planner binds
    // single/group/up-to targets; the choose-one path remains for legacy scalar callers.
    private AffectedCardResolver() {
    }

    static Resolution targeted(final SpellAbility outcome, final OutcomeEvaluationContext context,
            final Predicate<Card> additionalFilter) {
        final List<WeightedCard> cards = new ArrayList<>();
        if (unprojected(context)) { return new Resolution(cards, false); }
        if (context.state() != null) {
            for (final Card selected : outcome.getTargets().getTargetCards()) {
                final Card projected = context.state().card(selected);
                if (projected != null && additionalFilter.test(projected)) {
                    cards.add(new WeightedCard(projected, 1));
                }
            }
            return new Resolution(cards, false);
        }
        for (final Card candidate : allPotentialCards(outcome, context).keySet()) {
            final SpellAbility targetCheck = outcome.copy(outcome.getHostCard(), false);
            targetCheck.setActivatingPlayer(outcome.getActivatingPlayer());
            targetCheck.resetTargets();
            if (additionalFilter.test(candidate) && targetCheck.canTarget(candidate)) {
                cards.add(new WeightedCard(candidate, 1));
            }
        }
        return new Resolution(cards, true);
    }

    static Resolution defined(final SpellAbility outcome, final OutcomeEvaluationContext context,
            final Predicate<Card> additionalFilter) {
        if (unprojected(context)) { return new Resolution(List.of(), false); }
        final Map<Card, Integer> weights = allPotentialCards(outcome, context);
        final List<WeightedCard> cards = new ArrayList<>();
        for (final Card original : AbilityUtils.getDefinedCards(outcome.getHostCard(),
                outcome.getParamOrDefault("Defined", "Self"), outcome)) {
            final Card card = context.state() == null ? original : context.state().card(original);
            if (card != null && additionalFilter.test(card)) {
                cards.add(new WeightedCard(card, weights.getOrDefault(card, 1)));
            }
        }
        return new Resolution(cards, false);
    }

    static Resolution group(final SpellAbility outcome, final OutcomeEvaluationContext context,
            final Predicate<Card> additionalFilter) {
        if (unprojected(context)) { return new Resolution(List.of(), false); }
        final Map<Card, Integer> weights = allPotentialCards(outcome, context);
        final CardCollection candidates = new CardCollection(weights.keySet());
        final CardCollectionView matching = AbilityUtils.filterListByType(candidates,
                outcome.getParamOrDefault("ValidCards", "Card"), outcome);
        final List<WeightedCard> cards = new ArrayList<>();
        for (final Card card : matching) {
            if (additionalFilter.test(card)) {
                cards.add(new WeightedCard(card, weights.getOrDefault(card, 1)));
            }
        }
        return new Resolution(cards, false);
    }

    private static boolean unprojected(final OutcomeEvaluationContext context) {
        if (context.state() == null || !context.state().unprojectedBoard) { return false; }
        context.unsupported();
        return true;
    }

    static boolean supportsSingleBattlefieldTarget(final SpellAbility outcome) {
        if (!outcome.usesTargeting()) {
            return false;
        }
        final TargetRestrictions restrictions = outcome.getTargetRestrictions();
        return outcome.getMinTargets() == 1
                && outcome.getMaxTargets() == 1
                && restrictions.getZone().size() == 1
                && restrictions.getZone().contains(ZoneType.Battlefield);
    }

    static boolean affectsOnlyBattlefield(final SpellAbility outcome, final String zoneParam) {
        if (!outcome.hasParam(zoneParam)) {
            return true;
        }
        final List<ZoneType> zones = ZoneType.listValueOf(outcome.getParam(zoneParam));
        return zones.size() == 1 && zones.contains(ZoneType.Battlefield);
    }

    private static Map<Card, Integer> allPotentialCards(final SpellAbility outcome,
            final OutcomeEvaluationContext context) {
        final Map<Card, Integer> weights = new LinkedHashMap<>();
        for (final Card card : context.state() == null
                ? outcome.getHostCard().getGame().getCardsIn(ZoneType.Battlefield)
                : context.state().battlefield(context.evaluatingAi())) {
            final Card projected = context.state() == null ? card : context.state().card(card);
            if (projected != null) { weights.put(projected, 1); }
        }
        if (context.event() == null) { return weights; }
        for (final EffectEvent.Subject subject : context.event().subjects()) {
            if (subject.value() instanceof Card card) {
                weights.merge(card, subject.occurrences(), Math::max);
            }
        }
        return weights;
    }

    record WeightedCard(Card card, int occurrences) {
    }

    record Resolution(List<WeightedCard> cards, boolean chooseOne) {
        Resolution {
            cards = List.copyOf(cards);
        }
    }
}
