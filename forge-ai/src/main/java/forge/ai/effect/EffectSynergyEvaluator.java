package forge.ai.effect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilCombat;
import forge.ai.ability.TokenAi;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CounterEnumType;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.staticability.StaticAbilityCantCrew;
import forge.game.staticability.StaticAbilityDisableTriggers;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/**
 * Evaluates narrowly supported first-order relationships between effects on permanents.
 */
public final class EffectSynergyEvaluator {
    private static final Set<String> ATTACK_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> PHASE_TRIGGER_PARAMS = Set.of(
            "Mode", "Phase", "ValidPlayer", "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> TOKEN_CREATED_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidPlayer", "ValidToken", "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> TOKEN_ABILITY_PARAMS = Set.of(
            "DB", "TokenScript", "TokenOwner", "TokenAmount", "SpellDescription", "StackDescription");
    private static final Set<String> COUNTER_ABILITY_PARAMS = Set.of(
            "DB", "ValidTgts", "ValidTgtsDesc", "TgtPrompt", "CounterType", "CounterNum",
            "SpellDescription", "StackDescription");

    private EffectSynergyEvaluator() {
    }

    /**
     * Returns a raw synergy bonus for each supported permanent belonging to an opponent represented
     * in {@code candidates}. Unsupported or malformed effects are ignored.
     */
    public static Map<Card, Integer> evaluateRemovalSynergies(final Player evaluatingAi,
            final Iterable<Card> candidates) {
        if (evaluatingAi == null || candidates == null) {
            return Collections.emptyMap();
        }

        final Set<Player> analyzedControllers = new LinkedHashSet<>();
        for (final Card candidate : candidates) {
            if (candidate != null && candidate.getController().isOpponentOf(evaluatingAi)) {
                analyzedControllers.add(candidate.getController());
            }
        }
        if (analyzedControllers.isEmpty()) {
            return Collections.emptyMap();
        }

        final List<EffectProduction> productions = new ArrayList<>();
        final List<EffectConsequence> consequences = new ArrayList<>();
        for (final Player controller : analyzedControllers) {
            for (final Card permanent : controller.getCardsIn(ZoneType.Battlefield)) {
                for (final Trigger trigger : permanent.getTriggers()) {
                    try {
                        final EffectProduction production = extractProduction(permanent, trigger);
                        if (production != null) {
                            productions.add(production);
                        }
                        final EffectConsequence consequence = extractConsequence(permanent, trigger);
                        if (consequence != null) {
                            consequences.add(consequence);
                        }
                    } catch (final RuntimeException ignored) {
                        // Card scripts are data. Unknown or malformed forms must not disrupt AI decisions.
                    }
                }
            }
        }

        final Map<Card, Integer> bonuses = new HashMap<>();
        for (final EffectProduction production : productions) {
            for (final EffectConsequence consequence : consequences) {
                if (!matches(production, consequence)) {
                    continue;
                }
                final int targetDelta = findBestCounterDelta(production, consequence);
                final int relationshipValue = saturatingMultiply(production.expectedOccurrences(), targetDelta);
                if (relationshipValue <= 0) {
                    continue;
                }
                addSaturated(bonuses, production.source(), relationshipValue);
                if (production.source() != consequence.source()) {
                    addSaturated(bonuses, consequence.source(), relationshipValue);
                }
            }
        }
        return bonuses;
    }

    private static EffectProduction extractProduction(final Card source, final Trigger trigger) {
        if ((trigger.getMode() != TriggerType.Attacks && trigger.getMode() != TriggerType.Phase)
                || !isActiveBattlefieldTrigger(source, trigger)) {
            return null;
        }

        final int expectedOccurrences;
        if (trigger.getMode() == TriggerType.Attacks) {
            if (!hasOnlyParams(trigger, ATTACK_TRIGGER_PARAMS)
                    || !"Card.Self".equals(trigger.getParam("ValidCard"))) {
                return null;
            }
            expectedOccurrences = canProduceByAttacking(source) ? 1 : 0;
        } else if (trigger.getMode() == TriggerType.Phase) {
            if (!hasOnlyParams(trigger, PHASE_TRIGGER_PARAMS)
                    || !isSupportedPhase(trigger.getParam("Phase"))
                    || (trigger.hasParam("ValidPlayer") && !"You".equals(trigger.getParam("ValidPlayer")))) {
                return null;
            }
            expectedOccurrences = 1;
        } else {
            return null;
        }
        if (expectedOccurrences <= 0) {
            return null;
        }

        final SpellAbility outcome = getCopiedOutcome(source, trigger);
        if (!isSupportedTokenAbility(outcome)) {
            return null;
        }
        outcome.setActivatingPlayer(source.getController());

        final int tokenAmount = AbilityUtils.calculateAmount(source,
                outcome.getParamOrDefault("TokenAmount", "1"), outcome);
        if (tokenAmount <= 0) {
            return null;
        }
        final Card token = TokenAi.spawnToken(source.getController(), outcome);
        return new EffectProduction(source, EffectType.TOKEN_CREATED, token, source.getController(),
                saturatingMultiply(expectedOccurrences, tokenAmount));
    }

    private static EffectConsequence extractConsequence(final Card source, final Trigger trigger) {
        if (trigger.getMode() != TriggerType.TokenCreated
                || !isActiveBattlefieldTrigger(source, trigger)
                || !hasOnlyParams(trigger, TOKEN_CREATED_TRIGGER_PARAMS)
                || !"You".equals(trigger.getParam("ValidPlayer"))) {
            return null;
        }

        final SpellAbility outcome = getCopiedOutcome(source, trigger);
        if (!isSupportedCounterAbility(outcome)) {
            return null;
        }
        outcome.setActivatingPlayer(source.getController());
        outcome.resetTargets();

        final int counterAmount = Integer.parseInt(outcome.getParamOrDefault("CounterNum", "1"));
        return new EffectConsequence(source, EffectType.TOKEN_CREATED, trigger, outcome, counterAmount);
    }

    private static boolean isActiveBattlefieldTrigger(final Card source, final Trigger trigger) {
        return source.isInPlay()
                && !trigger.isSuppressed()
                && !source.getGame().getTriggerHandler().isTriggerSuppressed(trigger.getMode())
                && trigger.zonesCheck(source.getZone())
                && trigger.requirementsCheck(source.getGame());
    }

    private static SpellAbility getCopiedOutcome(final Card source, final Trigger trigger) {
        SpellAbility outcome = trigger.getOverridingAbility();
        if (outcome == null && trigger.hasParam("Execute")) {
            outcome = AbilityFactory.getAbility(source, trigger.getParam("Execute"), trigger);
        }
        return outcome == null ? null : outcome.copy(source, false);
    }

    private static boolean isSupportedTokenAbility(final SpellAbility outcome) {
        if (outcome == null || outcome.getApi() != ApiType.Token || outcome.getSubAbility() != null
                || !hasOnlyParams(outcome, TOKEN_ABILITY_PARAMS)
                || !"You".equals(outcome.getParam("TokenOwner"))
                || !outcome.hasParam("TokenScript")) {
            return false;
        }
        final String tokenScript = outcome.getParam("TokenScript");
        return !tokenScript.isBlank() && !tokenScript.contains(",");
    }

    private static boolean isSupportedCounterAbility(final SpellAbility outcome) {
        if (outcome == null || outcome.getApi() != ApiType.PutCounter || outcome.getSubAbility() != null
                || !hasOnlyParams(outcome, COUNTER_ABILITY_PARAMS)
                || !"P1P1".equals(outcome.getParam("CounterType"))
                || !outcome.getParamOrDefault("CounterNum", "1").matches("[1-9]\\d*")
                || !outcome.usesTargeting()) {
            return false;
        }

        final TargetRestrictions restrictions = outcome.getTargetRestrictions();
        outcome.setActivatingPlayer(outcome.getHostCard().getController());
        return outcome.getMinTargets() == 1
                && outcome.getMaxTargets() == 1
                && restrictions.getZone().size() == 1
                && restrictions.getZone().contains(ZoneType.Battlefield);
    }

    private static boolean matches(final EffectProduction production, final EffectConsequence consequence) {
        if (production.type() != consequence.observedType()) {
            return false;
        }
        final Map<AbilityKey, Object> runParams = new EnumMap<>(AbilityKey.class);
        runParams.put(AbilityKey.Player, production.eventPlayer());
        runParams.put(AbilityKey.Card, production.eventSubject());
        runParams.put(AbilityKey.Num, 1);
        try {
            return consequence.trigger().checkActivationLimit()
                    && consequence.trigger().meetsRequirementsOnTriggeredObjects(
                            consequence.source().getGame(), runParams)
                    && consequence.trigger().performTest(runParams)
                    && !StaticAbilityDisableTriggers.disabled(
                            consequence.source().getGame(), consequence.trigger(), runParams);
        } catch (final RuntimeException ignored) {
            return false;
        }
    }

    private static int findBestCounterDelta(final EffectProduction production,
            final EffectConsequence consequence) {
        int bestDelta = 0;
        final List<Card> potentialTargets = new ArrayList<>();
        potentialTargets.addAll(production.eventPlayer().getCreaturesInPlay());
        if (production.eventSubject().isCreature()) {
            potentialTargets.add(production.eventSubject());
        }

        for (final Card target : potentialTargets) {
            try {
                final SpellAbility outcome = consequence.outcome().copy(consequence.source(), false);
                outcome.setActivatingPlayer(consequence.source().getController());
                outcome.resetTargets();
                if (!target.canReceiveCounters(CounterEnumType.P1P1) || !outcome.canTarget(target)) {
                    continue;
                }

                final int before = ComputerUtilCard.evaluateCreature(target);
                final Card copy = CardCopyService.getLKICopy(target);
                if (target.getZone() != null) {
                    copy.setZone(target.getZone());
                }
                copy.setCounters(CounterEnumType.P1P1, saturatingAdd(
                        copy.getCounters(CounterEnumType.P1P1), consequence.counterAmount()));
                final int after = ComputerUtilCard.evaluateCreature(copy);
                bestDelta = Math.max(bestDelta, Math.max(0, after - before));
            } catch (final RuntimeException ignored) {
                // A target that cannot be safely evaluated contributes no relationship value.
            }
        }
        return bestDelta;
    }

    private static boolean canProduceByAttacking(final Card source) {
        if (source.isCreature()) {
            return ComputerUtilCombat.canAttackNextTurn(source);
        }
        if (!source.getType().hasSubtype("Vehicle") || !source.hasKeyword(Keyword.CREW)
                || !CombatUtil.getAllPossibleDefenders(source.getController()).anyMatch(
                        defender -> CombatUtil.canAttackNextTurn(source, defender))) {
            return false;
        }

        final int crewPowerNeeded = source.getKeywordMagnitude(Keyword.CREW);
        int availablePower = 0;
        for (final Card creature : source.getController().getCreaturesInPlay()) {
            if (creature != source && canCrewNextTurn(creature)) {
                availablePower = saturatingAdd(availablePower, Math.max(0, creature.getNetPower()));
            }
        }
        return availablePower >= crewPowerNeeded;
    }

    private static boolean canCrewNextTurn(final Card creature) {
        return !creature.isPhasedOut()
                && !StaticAbilityCantCrew.cantCrew(creature)
                && (!creature.isTapped() || (creature.getCounters(CounterEnumType.STUN) == 0
                        && creature.canUntap(creature.getController(), true)));
    }

    private static boolean isSupportedPhase(final String phase) {
        return "Upkeep".equalsIgnoreCase(phase) || "End of Turn".equalsIgnoreCase(phase);
    }

    private static boolean hasOnlyParams(final Trigger trigger, final Set<String> allowed) {
        return allowed.containsAll(trigger.getMapParams().keySet());
    }

    private static boolean hasOnlyParams(final SpellAbility ability, final Set<String> allowed) {
        return allowed.containsAll(ability.getMapParams().keySet());
    }

    private static void addSaturated(final Map<Card, Integer> values, final Card card, final int amount) {
        values.put(card, saturatingAdd(values.getOrDefault(card, 0), amount));
    }

    private static int saturatingAdd(final int left, final int right) {
        final long result = (long) left + right;
        return result >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static int saturatingMultiply(final int left, final int right) {
        final long result = (long) left * right;
        return result >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }
}
