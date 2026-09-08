package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;

import forge.ai.ComputerUtil;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/** Extracts predictable destruction as battlefield-to-graveyard movement. */
final class DestroyProductionExtractor implements EffectProductionExtractor {
    static final DestroyProductionExtractor INSTANCE = new DestroyProductionExtractor();

    // TODO(effect analysis): Include relationship and destination value in target selection; support
    // targeted DestroyAll, optional and random destruction, regeneration choices across a batch, shields
    // consumed without moving the permanent, destination replacement effects once their result can
    // be predicted, destruction from spells and unsupported trigger origins, multiple destruction
    // steps in one chain, delayed destruction, and deaths from lethal damage or zero toughness.

    private DestroyProductionExtractor() {
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
        final ProductionOpportunity opportunity =
                ProductionOpportunity.fromActivatedAbility(source, ability);
        return opportunity == null ? List.of() : extractFromOpportunity(source, opportunity);
    }

    private static List<EffectProduction> extractFromOpportunity(final Card source,
            final ProductionOpportunity opportunity) {
        final List<EffectProduction> productions = new ArrayList<>();
        final SpellAbility destroy = EffectAbilityUtils.findOutcome(
                opportunity.root(), ApiType.Destroy);
        if (destroy != null) {
            productions.addAll(createProduction(source, opportunity, destroy, false));
        }
        final SpellAbility destroyAll = EffectAbilityUtils.findOutcome(
                opportunity.root(), ApiType.DestroyAll);
        if (destroyAll != null) {
            productions.addAll(createProduction(source, opportunity, destroyAll, true));
        }
        return productions;
    }

    private static List<EffectProduction> createProduction(final Card source,
            final ProductionOpportunity opportunity, final SpellAbility destroy,
            final boolean group) {
        destroy.setActivatingPlayer(source.getController());
        destroy.resetTargets();
        if (EffectAbilityUtils.hasUnsupportedControlFlow(destroy)
                || destroy.hasParam("Radiance")
                || (group && (destroy.usesTargeting() || !destroy.hasParam("ValidCards")))) {
            return List.of();
        }

        final List<Card> recipients = group
                ? resolveGroup(destroy) : resolveIndividual(destroy);
        recipients.removeIf(card -> !willReachGraveyard(destroy, card));
        return PermanentDepartureEventFactory.createBattlefieldToGraveyardProduction(
                source, opportunity.root(), recipients, opportunity.expectedBatches());
    }

    private static List<Card> resolveIndividual(final SpellAbility destroy) {
        if (destroy.usesTargeting()) {
            final Card selected = EffectCardTargetSelector.chooseBestDepartureTarget(
                    destroy, card -> willReachGraveyard(destroy, card));
            return selected == null ? new ArrayList<>() : new ArrayList<>(List.of(selected));
        }
        if (!destroy.hasParam("Defined")) {
            return new ArrayList<>();
        }
        return new ArrayList<>(AbilityUtils.getDefinedCards(destroy.getHostCard(),
                destroy.getParam("Defined"), destroy));
    }

    private static List<Card> resolveGroup(final SpellAbility destroy) {
        final CardCollection battlefield = new CardCollection(
                destroy.getHostCard().getGame().getCardsIn(ZoneType.Battlefield));
        final CardCollectionView matching = AbilityUtils.filterListByType(
                battlefield, destroy.getParam("ValidCards"), destroy);
        return new ArrayList<>(matching);
    }

    private static boolean willReachGraveyard(final SpellAbility destroy, final Card card) {
        if (!card.isInPlay() || card.isPhasedOut() || !card.canBeDestroyed()
                || card.getShieldCount() > 0
                || card.getCounters(CounterEnumType.SHIELD) > 0) {
            return false;
        }
        final boolean noRegeneration = destroy.hasParam("NoRegen")
                || destroy.hasParam("NoRegenValid")
                && card.isValid(destroy.getParam("NoRegenValid"),
                        destroy.getActivatingPlayer(), destroy.getHostCard(), destroy);
        if (!noRegeneration && ComputerUtil.canRegenerate(card.getController(), card)) {
            return false;
        }

        return ZoneChangePrediction.willMove(
                card, ZoneType.Battlefield, ZoneType.Graveyard, destroy);
    }
}
