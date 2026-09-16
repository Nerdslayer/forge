package forge.ai.effect;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.spellability.Spell;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/** Extracts a conservative future event for a mandatory counter-target-spell ability. */
final class CounteredProductionExtractor implements EffectProductionExtractor {
    static final CounteredProductionExtractor INSTANCE = new CounteredProductionExtractor();

    // TODO(effect analysis): Support countering activated/triggered abilities, multiple or
    // defined stack targets, unless costs, optional targets, replacement destinations, card-level
    // predicates such as wasCastByYou, and countered spells' own characteristics.

    private CounteredProductionExtractor() {
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
        final SpellAbility counter = EffectAbilityUtils.findOutcome(opportunity.root(), ApiType.Counter);
        if (counter == null || !isSupported(counter)) {
            return List.of();
        }
        counter.setActivatingPlayer(source.getController());
        counter.resetTargets();

        // The target spell is intentionally a characteristic-free analysis placeholder. Countered
        // triggers that require a specific card property are rejected by EventTriggerParser rather
        // than guessing from hidden future stack information.
        final Card targetCard = EffectAnalysisCardFactory.createUnknownCard(
                source.getController().getOpponents().get(0), ZoneType.Stack);
        final SpellAbility counteredSpell = new Spell(source, Cost.Zero) {
            @Override
            public void resolve() {
                // Analysis-only stack placeholder.
            }
        };
        counteredSpell.setActivatingPlayer(source.getController().getOpponents().get(0));
        final Map<AbilityKey, Object> parameters = new EnumMap<>(AbilityKey.class);
        parameters.put(AbilityKey.Card, targetCard);
        parameters.put(AbilityKey.Cause, opportunity.root());
        parameters.put(AbilityKey.SpellAbility, counteredSpell);
        final EffectEvent event = new EffectEvent(EffectType.SPELL_OR_ABILITY_COUNTERED,
                source.getController(), List.of(new EffectEvent.Subject(targetCard, 1)), parameters);
        return List.of(new EffectProduction(source, EffectType.SPELL_OR_ABILITY_COUNTERED,
                List.of(event), opportunity.expectedBatches()));
    }

    private static boolean isSupported(final SpellAbility counter) {
        if (counter.usesTargeting()
                && !"Spell".equalsIgnoreCase(counter.getParam("TargetType"))) {
            return false;
        }
        if (!counter.usesTargeting() || counter.hasParam("Defined")
                || (counter.hasParam("ValidTgts")
                        && !"Card".equalsIgnoreCase(counter.getParam("ValidTgts")))) {
            return false;
        }
        if (counter.hasParam("TargetMin") && !"1".equals(counter.getParam("TargetMin"))) {
            return false;
        }
        if (counter.hasParam("TargetMax") && !"1".equals(counter.getParam("TargetMax"))) {
            return false;
        }
        for (final String parameter : counter.getMapParams().keySet()) {
            if (parameter.startsWith("Condition") || parameter.startsWith("Unless")
                    || parameter.equals("Optional") || parameter.equals("DestinationChoice")
                    || parameter.equals("RememberCountered") || parameter.equals("RememberCounteredSA")
                    || parameter.equals("DestroyPermanent")) {
                return false;
            }
        }
        return true;
    }
}
