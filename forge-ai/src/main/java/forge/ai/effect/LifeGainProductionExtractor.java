package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.ai.ComputerUtilCombat;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;

/** Extracts direct life-gain productions with known recipients and amounts. */
final class LifeGainProductionExtractor implements EffectProductionExtractor {
    static final LifeGainProductionExtractor INSTANCE = new LifeGainProductionExtractor();
    private static final Set<String> COMBAT_DAMAGE_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidSource", "ValidTarget", "CombatDamage", "Execute",
            "TriggerZones", "TriggerDescription", "Secondary");

    // TODO(effect analysis): Support targeted/dynamic recipients, noncombat lifelink and other
    // damage-based gains, temporary or prospective lifelink, attribution to static-effect sources,
    // combat damage dealt while blocking, DamageDoneOnce aggregation, blockers and attack-choice
    // likelihood, spells and non-battlefield actions, replacement-modified amounts, optional and
    // conditional forms, additional trigger origins, and life gained by later consequence chains.

    private LifeGainProductionExtractor() {
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source) {
        if (!source.isInPlay() || source.isPhasedOut() || !source.isCreature()
                || !source.hasKeyword(Keyword.LIFELINK)
                || !ComputerUtilCombat.canAttackNextTurn(source)) {
            return List.of();
        }

        final int damagePerStep = source.getNetCombatDamage();
        if (damagePerStep <= 0 || !source.getController().canGainLife()) {
            return List.of();
        }

        final int damageSteps = source.hasDoubleStrike() ? 2 : 1;
        final List<EffectEvent> events = new ArrayList<>();
        for (int i = 0; i < damageSteps; i++) {
            events.add(createLifeGainEvent(source, source.getController(), damagePerStep,
                    null, source.getController().getLifeGainedTimesThisTurn() == 0 && i == 0));
        }
        return List.of(new EffectProduction(source, EffectType.LIFE_GAINED, events, 1));
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final Trigger trigger) {
        if (trigger.getMode() == TriggerType.DamageDone) {
            if (!EffectAbilityUtils.isActiveBattlefieldTrigger(source, trigger)) {
                return List.of();
            }
            final EffectProduction combatProduction = extractCombatDamageProduction(source, trigger);
            return combatProduction == null ? List.of() : List.of(combatProduction);
        }
        final ProductionOpportunity opportunity = ProductionOpportunity.fromTrigger(
                evaluatingAi, source, trigger);
        if (opportunity == null) {
            return List.of();
        }
        final SpellAbility outcome = findSupportedLifeGainOutcome(
                opportunity.root());
        final EffectProduction production = outcome == null
                ? null : createProduction(source, outcome, opportunity.expectedBatches());
        return production == null ? List.of() : List.of(production);
    }

    private static EffectProduction extractCombatDamageProduction(final Card source,
            final Trigger trigger) {
        // A missing CombatDamage restriction means that both combat and noncombat damage can
        // trigger the ability. This extractor models only its currently predictable combat use.
        if ((trigger.hasParam("CombatDamage")
                    && !"True".equalsIgnoreCase(trigger.getParam("CombatDamage")))
                || !EffectAbilityUtils.hasOnlyParams(trigger, COMBAT_DAMAGE_TRIGGER_PARAMS)) {
            return null;
        }
        final SpellAbility outcome = findSupportedLifeGainOutcome(
                EffectAbilityUtils.copyTriggerOutcome(source, trigger));
        if (outcome == null) {
            return null;
        }

        final List<EffectEvent> events = new ArrayList<>();
        for (final Card attacker : source.getController().getCreaturesInPlay()) {
            final int damagePerStep = attacker.getNetCombatDamage();
            if (damagePerStep <= 0) {
                continue;
            }
            final Player defender = findSupportedDefender(attacker, trigger, damagePerStep);
            if (defender == null) {
                continue;
            }
            final int damageSteps = attacker.hasDoubleStrike() ? 2 : 1;
            for (int i = 0; i < damageSteps; i++) {
                final Map<AbilityKey, Object> damageParameters = createDamageParameters(
                        attacker, defender, damagePerStep);
                trigger.setTriggeringObjects(outcome, damageParameters);
                final EffectProduction production = createProduction(
                        source, outcome, 1, damageParameters, events.size() == 0);
                if (production != null) {
                    events.addAll(production.events());
                }
            }
        }
        return events.isEmpty() ? null : new EffectProduction(
                source, EffectType.LIFE_GAINED, events, 1);
    }

    private static Player findSupportedDefender(final Card attacker, final Trigger trigger,
            final int damage) {
        for (final Player opponent : attacker.getController().getOpponents()) {
            if (!ComputerUtilCombat.canAttackNextTurn(attacker, opponent)) {
                continue;
            }
            final Map<AbilityKey, Object> damageParameters = createDamageParameters(
                    attacker, opponent, damage);
            if (trigger.performTest(damageParameters)) {
                return opponent;
            }
        }
        return null;
    }

    private static Map<AbilityKey, Object> createDamageParameters(final Card attacker,
            final Player defender, final int damage) {
        final Map<AbilityKey, Object> parameters = new EnumMap<>(AbilityKey.class);
        parameters.put(AbilityKey.DamageSource, attacker);
        parameters.put(AbilityKey.DamageTarget, defender);
        parameters.put(AbilityKey.DamageAmount, damage);
        parameters.put(AbilityKey.IsCombatDamage, true);
        parameters.put(AbilityKey.DefendingPlayer, defender);
        return parameters;
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final SpellAbility ability) {
        final ProductionOpportunity opportunity =
                ProductionOpportunity.fromActivatedAbility(source, ability);
        if (opportunity == null) {
            return List.of();
        }
        final SpellAbility outcome = findSupportedLifeGainOutcome(opportunity.root());
        final EffectProduction production = outcome == null
                ? null : createProduction(source, outcome, opportunity.expectedBatches());
        return production == null ? List.of() : List.of(production);
    }

    private static SpellAbility findSupportedLifeGainOutcome(final SpellAbility root) {
        SpellAbility current = root;
        while (current != null) {
            if (EffectAbilityUtils.hasUnsupportedControlFlow(current)) {
                return null;
            }
            if (current.getApi() == ApiType.GainLife) {
                return current.usesTargeting() || !current.hasParam("LifeAmount")
                        ? null : current;
            }
            current = current.getSubAbility();
        }
        return null;
    }

    private static EffectProduction createProduction(final Card source,
            final SpellAbility outcome, final int expectedBatches) {
        return createProduction(source, outcome, expectedBatches, Map.of(), true);
    }

    private static EffectProduction createProduction(final Card source,
            final SpellAbility outcome, final int expectedBatches,
            final Map<AbilityKey, Object> originatingParameters, final boolean mayBeFirstGain) {
        outcome.setActivatingPlayer(source.getController());
        final int amount = AbilityUtils.calculateAmount(
                source, outcome.getParam("LifeAmount"), outcome);
        if (amount <= 0) {
            return null;
        }

        final PlayerCollection recipients = AbilityUtils.getDefinedPlayers(source,
                outcome.getParamOrDefault("Defined", "You"), outcome);
        final List<EffectEvent> events = new ArrayList<>();
        for (final Player recipient : recipients) {
            if (!recipient.canGainLife()) {
                continue;
            }
            final Map<AbilityKey, Object> triggerParameters = new EnumMap<>(AbilityKey.class);
            triggerParameters.putAll(originatingParameters);
            events.add(createLifeGainEvent(source, recipient, amount, outcome,
                    mayBeFirstGain && recipient.getLifeGainedTimesThisTurn() == 0,
                    triggerParameters));
        }
        return events.isEmpty() ? null : new EffectProduction(
                source, EffectType.LIFE_GAINED, events, expectedBatches);
    }

    private static EffectEvent createLifeGainEvent(final Card source, final Player recipient,
            final int amount, final SpellAbility sourceAbility, final boolean firstTime) {
        return createLifeGainEvent(source, recipient, amount, sourceAbility, firstTime,
                new EnumMap<>(AbilityKey.class));
    }

    private static EffectEvent createLifeGainEvent(final Card source, final Player recipient,
            final int amount, final SpellAbility sourceAbility, final boolean firstTime,
            final Map<AbilityKey, Object> triggerParameters) {
        triggerParameters.put(AbilityKey.Player, recipient);
        triggerParameters.put(AbilityKey.LifeAmount, amount);
        triggerParameters.put(AbilityKey.Source, source);
        triggerParameters.put(AbilityKey.SourceSA, sourceAbility);
        triggerParameters.put(AbilityKey.FirstTime, firstTime);
        return new EffectEvent(EffectType.LIFE_GAINED, recipient,
                List.of(new EffectEvent.Subject(recipient, 1)), triggerParameters);
    }
}
