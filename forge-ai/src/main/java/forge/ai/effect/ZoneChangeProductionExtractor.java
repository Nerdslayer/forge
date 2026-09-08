package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/** Extracts predictable battlefield departures caused by direct zone-change effects. */
final class ZoneChangeProductionExtractor implements EffectProductionExtractor {
    static final ZoneChangeProductionExtractor INSTANCE = new ZoneChangeProductionExtractor();

    private static final Set<ZoneType> SUPPORTED_DESTINATIONS = Set.of(
            ZoneType.Exile, ZoneType.Graveyard, ZoneType.Hand, ZoneType.Library);
    private static final Set<String> UNSUPPORTED_PARAMS = Set.of(
            "AtEOT", "ChangeNum", "Chooser", "DestinationAlternative", "Duration",
            "ExileFaceDown", "Optional", "RandomOrder", "ThisDefinedAndTgts", "TypeLimit");

    // TODO(effect analysis): Support temporary and linked exile, delayed returns, multiple or
    // optional targets, destination choices, face-down exile without leaking information,
    // player-targeted/group movement, rational beneficial self-bounce/blink choices, replacement
    // results, cards moving from non-battlefield zones, spells, additional trigger origins, and
    // chained or repeated zone changes.

    private ZoneChangeProductionExtractor() {
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
        final SpellAbility changeZone = EffectAbilityUtils.findOutcome(
                opportunity.root(), ApiType.ChangeZone);
        if (changeZone != null) {
            productions.addAll(createProduction(source, opportunity, changeZone, false));
        }
        final SpellAbility changeZoneAll = EffectAbilityUtils.findOutcome(
                opportunity.root(), ApiType.ChangeZoneAll);
        if (changeZoneAll != null) {
            productions.addAll(createProduction(source, opportunity, changeZoneAll, true));
        }
        return productions;
    }

    private static List<EffectProduction> createProduction(final Card source,
            final ProductionOpportunity opportunity, final SpellAbility movement,
            final boolean group) {
        movement.setActivatingPlayer(source.getController());
        movement.resetTargets();
        if (!isSupported(movement, group)) {
            return List.of();
        }

        final ZoneType destination = ZoneType.smartValueOf(movement.getParam("Destination"));
        final List<Card> recipients = group
                ? resolveGroup(movement, destination) : resolveIndividual(movement, destination);
        return PermanentDepartureEventFactory.createZoneChangeProduction(
                source, opportunity.root(), recipients, ZoneType.Battlefield,
                destination, opportunity.expectedBatches());
    }

    private static boolean isSupported(final SpellAbility movement, final boolean group) {
        if (movement.getSubAbility() != null
                || EffectAbilityUtils.hasUnsupportedControlFlow(movement)
                || movement.getMapParams().keySet().stream().anyMatch(UNSUPPORTED_PARAMS::contains)
                || !"Battlefield".equals(movement.getParam("Origin"))
                || !movement.hasParam("Destination")) {
            return false;
        }
        final ZoneType destination;
        try {
            destination = ZoneType.smartValueOf(movement.getParam("Destination"));
        } catch (final RuntimeException ignored) {
            return false;
        }
        if (!SUPPORTED_DESTINATIONS.contains(destination)) {
            return false;
        }
        if (group) {
            return !movement.usesTargeting() && !movement.hasParam("Defined")
                    && movement.hasParam("ChangeType");
        }
        return !movement.usesTargeting()
                || AffectedCardResolver.supportsSingleBattlefieldTarget(movement);
    }

    private static List<Card> resolveIndividual(final SpellAbility movement,
            final ZoneType destination) {
        if (movement.usesTargeting()) {
            final Card selected = EffectCardTargetSelector.chooseBestDepartureTarget(
                    movement, card -> ZoneChangePrediction.willMove(
                            card, ZoneType.Battlefield, destination, movement));
            return selected == null ? List.of() : List.of(selected);
        }
        final List<Card> defined = new ArrayList<>(AbilityUtils.getDefinedCards(
                movement.getHostCard(), movement.getParamOrDefault("Defined", "Self"), movement));
        defined.removeIf(card -> !ZoneChangePrediction.willMove(
                card, ZoneType.Battlefield, destination, movement));
        return defined;
    }

    private static List<Card> resolveGroup(final SpellAbility movement,
            final ZoneType destination) {
        final CardCollection battlefield = new CardCollection(
                movement.getHostCard().getGame().getCardsIn(ZoneType.Battlefield));
        final CardCollectionView matching = AbilityUtils.filterListByType(
                battlefield, movement.getParam("ChangeType"), movement);
        final List<Card> recipients = new ArrayList<>(matching);
        recipients.removeIf(card -> !ZoneChangePrediction.willMove(
                card, ZoneType.Battlefield, destination, movement));
        return recipients;
    }
}
