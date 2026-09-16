package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityUtils;
import forge.game.ability.AbilityKey;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/** Extracts predictable permanent-to-permanent control changes. */
final class ControlChangeProductionExtractor implements EffectProductionExtractor {
    static final ControlChangeProductionExtractor INSTANCE =
            new ControlChangeProductionExtractor();

    // TODO(effect analysis): Support dynamic/conditional group filters and choices, exchanges,
    // player control, selected/dynamic recipients, control-change costs, temporary duration
    // likelihood, and control changes embedded in richer chains or replacement effects.

    private ControlChangeProductionExtractor() {
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
        final SpellAbility gainControl = EffectAbilityUtils.findOutcome(
                opportunity.root(), ApiType.GainControl);
        if (gainControl == null || !isSupported(gainControl)) {
            return List.of();
        }
        gainControl.setActivatingPlayer(source.getController());
        gainControl.resetTargets();
        final Player newController = resolveNewController(source, gainControl);
        if (newController == null) {
            return List.of();
        }

        final List<Card> targets = resolveTargets(gainControl, newController);
        final List<EffectEvent> events = new ArrayList<>();
        for (final Card target : targets) {
            if (target == null || !canChangeControl(target, newController)) {
                continue;
            }
            events.add(createEvent(target, newController, opportunity.root()));
        }
        return events.isEmpty() ? List.of() : List.of(new EffectProduction(
                source, EffectType.CONTROL_CHANGED, events, opportunity.expectedBatches()));
    }

    private static boolean isSupported(final SpellAbility gainControl) {
        return !EffectAbilityUtils.hasUnsupportedControlFlow(gainControl)
                && !gainControl.hasParam("Choices")
                && !gainControl.hasParam("Chooser")
                && !gainControl.hasParam("Optional")
                && !gainControl.hasParam("TargetingPlayer")
                && !gainControl.hasParam("Untap")
                && !gainControl.hasParam("AddKWs")
                && (supportsStaticGroup(gainControl)
                        || (gainControl.usesTargeting()
                                ? AffectedCardResolver.supportsSingleBattlefieldTarget(gainControl)
                                : gainControl.hasParam("Defined")));
    }

    private static boolean supportsStaticGroup(final SpellAbility gainControl) {
        if (!gainControl.hasParam("AllValid") || gainControl.usesTargeting()
                || gainControl.hasParam("Defined")) {
            return false;
        }
        final String valid = gainControl.getParam("AllValid");
        return valid != null && !valid.contains("Triggered") && !valid.contains("Remembered")
                && !valid.contains("Chosen") && !valid.contains("Targeted");
    }

    private static Player resolveNewController(final Card source,
            final SpellAbility gainControl) {
        if (!gainControl.hasParam("NewController")) {
            return gainControl.getActivatingPlayer();
        }
        final PlayerCollection players = AbilityUtils.getDefinedPlayers(source,
                gainControl.getParam("NewController"), gainControl);
        return players.size() == 1 ? players.get(0) : null;
    }

    private static List<Card> resolveTargets(final SpellAbility gainControl,
            final Player newController) {
        if (gainControl.hasParam("AllValid")) {
            final CardCollection battlefield = new CardCollection(
                    gainControl.getHostCard().getGame().getCardsIn(ZoneType.Battlefield));
            final CardCollectionView matching = AbilityUtils.filterListByType(battlefield,
                    gainControl.getParam("AllValid"), gainControl);
            return new ArrayList<>(matching);
        }
        if (gainControl.usesTargeting()) {
            final Card selected = EffectCardTargetSelector.chooseBestControlChangeTarget(
                    gainControl, newController,
                    card -> canChangeControl(card, newController));
            return selected == null ? List.of() : List.of(selected);
        }
        return new ArrayList<>(AbilityUtils.getDefinedCards(gainControl.getHostCard(),
                gainControl.getParam("Defined"), gainControl));
    }

    private static boolean canChangeControl(final Card target, final Player newController) {
        return target.isInPlay() && !target.isPhasedOut()
                && target.getController() != newController
                && target.canBeControlledBy(newController);
    }

    private static EffectEvent createEvent(final Card target,
            final Player newController, final SpellAbility cause) {
        // ChangesController receives the new controlled object while it also receives the old
        // controller separately. Use an analysis-only post-change copy for ValidCard tests;
        // never mutate the live permanent while predicting the event.
        final Card changed = CardCopyService.getLKICopy(target);
        changed.setController(newController, 0);
        final Map<AbilityKey, Object> parameters = new EnumMap<>(AbilityKey.class);
        parameters.put(AbilityKey.Card, changed);
        parameters.put(AbilityKey.OriginalController, target.getController());
        parameters.put(AbilityKey.Player, newController);
        parameters.put(AbilityKey.Cause, cause);
        return new EffectEvent(EffectType.CONTROL_CHANGED, newController,
                List.of(new EffectEvent.Subject(changed, 1)), parameters);
    }
}
