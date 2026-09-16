package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityUtils;
import forge.game.ability.AbilityKey;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

/** Extracts predictable one-creature-versus-one-creature fight events. */
final class FightProductionExtractor implements EffectProductionExtractor {
    static final FightProductionExtractor INSTANCE = new FightProductionExtractor();

    // TODO(effect analysis): Support optional/up-to and random targets, two targeted fighters,
    // one-sided fights, power/toughness-derived non-Fight damage, replacement/prevention effects,
    // fight outcomes embedded in choices or richer chains, and future fight likelihood.

    private FightProductionExtractor() {
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
        final SpellAbility fight = EffectAbilityUtils.findOutcome(
                opportunity.root(), ApiType.Fight);
        if (fight == null || !isSupported(fight)) {
            return List.of();
        }
        fight.setActivatingPlayer(source.getController());
        fight.resetTargets();
        final Card fighter = resolveFighter(fight);
        if (fighter == null || !fighter.isInPlay() || fighter.isPhasedOut()
                || !fighter.isCreature()) {
            return List.of();
        }
        final Card opponent = EffectCardTargetSelector.chooseBestFightTarget(fight, fighter);
        if (opponent == null) {
            return List.of();
        }

        final List<EffectEvent> events = new ArrayList<>();
        events.add(createEvent(fighter, opponent, opportunity.root()));
        events.add(createEvent(opponent, fighter, opportunity.root()));
        return List.of(new EffectProduction(source, EffectType.FOUGHT, events,
                opportunity.expectedBatches()));
    }

    private static boolean isSupported(final SpellAbility fight) {
        return !EffectAbilityUtils.hasUnsupportedControlFlow(fight)
                && !fight.hasParam("Choices")
                && !fight.hasParam("TargetsAtRandom")
                && !fight.hasParam("Optional")
                && !fight.hasParam("TargetMin")
                && !fight.hasParam("TargetMax")
                && fight.hasParam("Defined")
                && ("Self".equals(fight.getParam("Defined"))
                        || fight.getParam("Defined").contains("TriggeredCard"))
                && AffectedCardResolver.supportsSingleBattlefieldTarget(fight);
    }

    private static Card resolveFighter(final SpellAbility fight) {
        final String defined = fight.getParam("Defined");
        final List<Card> definedCards = new ArrayList<>(AbilityUtils.getDefinedCards(
                fight.getHostCard(), defined, fight));
        for (final Card card : definedCards) {
            if (card != null && card.isInPlay() && !card.isPhasedOut() && card.isCreature()) {
                return card;
            }
        }
        // TriggeredCard(LKI) is the normal ETB/enrage representation. During static analysis
        // there is no live triggering object, so the known host is the safe approximation.
        return defined.contains("TriggeredCard") && fight.getHostCard().isCreature()
                ? fight.getHostCard() : null;
    }

    private static EffectEvent createEvent(final Card fighter, final Card opponent,
            final SpellAbility cause) {
        final Map<AbilityKey, Object> parameters = new EnumMap<>(AbilityKey.class);
        parameters.put(AbilityKey.Fighter, fighter);
        parameters.put(AbilityKey.Fighters, List.of(fighter, opponent));
        parameters.put(AbilityKey.Cause, cause);
        return new EffectEvent(EffectType.FOUGHT, fighter.getController(),
                List.of(new EffectEvent.Subject(fighter, 1),
                        new EffectEvent.Subject(opponent, 1)), parameters);
    }
}
