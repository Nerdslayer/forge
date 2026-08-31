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
import forge.ai.ComputerUtilCost;
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
import forge.game.player.PlayerCollection;
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
final class TriggeredEffectAnalyzer {
    private static final Set<String> ATTACK_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> PHASE_TRIGGER_PARAMS = Set.of(
            "Mode", "Phase", "ValidPlayer", "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> TOKEN_CREATED_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidPlayer", "ValidToken", "OnlyFirst", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> TOKEN_CREATED_ONCE_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidToken", "OnlyFirst", "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> COUNTER_ABILITY_PARAMS = Set.of(
            "DB", "ValidTgts", "ValidTgtsDesc", "TgtPrompt", "CounterType", "CounterNum",
            "SpellDescription", "StackDescription");
    private TriggeredEffectAnalyzer() {
    }

    /**
     * Returns a raw synergy bonus for each supported permanent belonging to an opponent represented
     * in {@code candidates}. Unsupported or malformed effects are ignored.
     */
    static Map<Card, Integer> evaluateRelationships(final Player evaluatingAi,
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
                for (final SpellAbility ability : permanent.getSpellAbilities()) {
                    try {
                        final EffectProduction production = extractActivatedProduction(permanent, ability);
                        if (production != null) {
                            productions.add(production);
                        }
                    } catch (final RuntimeException ignored) {
                        // Card scripts are data. Unknown or malformed forms must not disrupt AI decisions.
                    }
                }
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
                final int relationshipValue = evaluateRelationship(production, consequence);
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

        final SpellAbility outcome = findDirectTokenOutcome(getCopiedOutcome(source, trigger));
        if (outcome == null) {
            return null;
        }
        return createTokenProduction(source, outcome, expectedOccurrences);
    }

    private static EffectProduction extractActivatedProduction(final Card source, final SpellAbility ability) {
        if (!source.isInPlay() || !ability.isActivatedAbility()) {
            return null;
        }
        final SpellAbility copied = ability.copy(source, false);
        copied.setActivatingPlayer(source.getController());
        if (!copied.getRestrictions().checkZoneRestrictions(source, copied)
                || !copied.getRestrictions().checkOtherRestrictions(
                        source, copied, source.getController())
                || (copied.getConditions() != null && !copied.getConditions().areMet(copied))
                || !ComputerUtilCost.canPayCost(copied, source.getController(), false)) {
            return null;
        }
        final SpellAbility outcome = findDirectTokenOutcome(copied);
        return outcome == null ? null : createTokenProduction(source, outcome, 1);
    }

    private static EffectConsequence extractConsequence(final Card source, final Trigger trigger) {
        if ((trigger.getMode() != TriggerType.TokenCreated
                && trigger.getMode() != TriggerType.TokenCreatedOnce)
                || !isActiveBattlefieldTrigger(source, trigger)
                || (trigger.getMode() == TriggerType.TokenCreated
                        && (!hasOnlyParams(trigger, TOKEN_CREATED_TRIGGER_PARAMS)
                                || !"You".equals(trigger.getParam("ValidPlayer"))))
                || (trigger.getMode() == TriggerType.TokenCreatedOnce
                        && !hasOnlyParams(trigger, TOKEN_CREATED_ONCE_TRIGGER_PARAMS))) {
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

    private static SpellAbility findDirectTokenOutcome(final SpellAbility outcome) {
        SpellAbility current = outcome;
        while (current != null) {
            if (current.getApi() == ApiType.Token) {
                return isSupportedTokenAbility(current) ? current : null;
            }
            current = current.getSubAbility();
        }
        return null;
    }

    private static boolean isSupportedTokenAbility(final SpellAbility outcome) {
        if (!outcome.hasParam("TokenScript")
                || !"You".equals(outcome.getParamOrDefault("TokenOwner", "You"))) {
            return false;
        }
        for (final String param : outcome.getMapParams().keySet()) {
            if (param.startsWith("Condition") || param.startsWith("Unless")) {
                return false;
            }
        }
        return !outcome.getParam("TokenScript").isBlank();
    }

    private static EffectProduction createTokenProduction(final Card source,
            final SpellAbility outcome, final int expectedBatches) {
        outcome.setActivatingPlayer(source.getController());
        final int tokenAmount = AbilityUtils.calculateAmount(source,
                outcome.getParamOrDefault("TokenAmount", "1"), outcome);
        if (tokenAmount <= 0) {
            return null;
        }

        final List<EffectProduction.ProducedEvent> events = new ArrayList<>();
        for (final String script : outcome.getParam("TokenScript").split(",")) {
            if (script.isBlank()) {
                return null;
            }
            final SpellAbility tokenAbility = outcome.copy(source, false);
            tokenAbility.setActivatingPlayer(source.getController());
            tokenAbility.getMapParams().put("TokenScript", script.trim());
            final Card token = TokenAi.spawnToken(source.getController(), tokenAbility);
            if (outcome.hasParam("TokenTapped")) {
                token.setTapped(true);
            }
            events.add(new EffectProduction.ProducedEvent(token, tokenAmount));
        }
        return events.isEmpty() ? null : new EffectProduction(source, EffectType.TOKEN_CREATED,
                List.copyOf(events), source.getController(), expectedBatches);
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

    private static int evaluateRelationship(final EffectProduction production,
            final EffectConsequence consequence) {
        if (production.type() != consequence.observedType()) {
            return 0;
        }
        if (consequence.trigger().getMode() == TriggerType.TokenCreatedOnce) {
            return evaluateBatchRelationship(production, consequence);
        }

        int value = 0;
        int eventNumber = production.eventPlayer().getNumTokenCreatedThisTurn();
        for (final EffectProduction.ProducedEvent event : production.events()) {
            final int firstEventNumber = saturatingAdd(eventNumber, 1);
            if (matchesIndividualEvent(production, consequence, event.subject(), firstEventNumber)) {
                final int matchingOccurrences = consequence.trigger().hasParam("OnlyFirst")
                        ? 1 : event.occurrences();
                value = saturatingAdd(value, saturatingMultiply(matchingOccurrences,
                        findBestCounterDelta(production.eventPlayer(), event.subject(), consequence)));
            }
            eventNumber = saturatingAdd(eventNumber, event.occurrences());
        }
        return saturatingMultiply(production.expectedBatches(), value);
    }

    private static int evaluateBatchRelationship(final EffectProduction production,
            final EffectConsequence consequence) {
        final Map<AbilityKey, Object> runParams = new EnumMap<>(AbilityKey.class);
        final List<Card> tokens = new ArrayList<>();
        for (final EffectProduction.ProducedEvent event : production.events()) {
            tokens.add(event.subject());
        }
        runParams.put(AbilityKey.Cards, tokens);
        if (production.eventPlayer().getNumTokenCreatedThisTurn() == 0) {
            runParams.put(AbilityKey.FirstTime, new PlayerCollection(production.eventPlayer()));
        } else {
            runParams.put(AbilityKey.FirstTime, new PlayerCollection());
        }
        try {
            if (!(consequence.trigger().checkActivationLimit()
                    && consequence.trigger().meetsRequirementsOnTriggeredObjects(
                            consequence.source().getGame(), runParams)
                    && consequence.trigger().performTest(runParams)
                    && !StaticAbilityDisableTriggers.disabled(
                            consequence.source().getGame(), consequence.trigger(), runParams))) {
                return 0;
            }
        } catch (final RuntimeException ignored) {
            return 0;
        }

        int bestDelta = 0;
        for (final EffectProduction.ProducedEvent event : production.events()) {
            bestDelta = Math.max(bestDelta,
                    findBestCounterDelta(production.eventPlayer(), event.subject(), consequence));
        }
        return saturatingMultiply(production.expectedBatches(), bestDelta);
    }

    private static boolean matchesIndividualEvent(final EffectProduction production,
            final EffectConsequence consequence, final Card token, final int eventNumber) {
        final Map<AbilityKey, Object> runParams = new EnumMap<>(AbilityKey.class);
        runParams.put(AbilityKey.Player, production.eventPlayer());
        runParams.put(AbilityKey.Card, token);
        runParams.put(AbilityKey.Num, eventNumber);
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

    private static int findBestCounterDelta(final Player eventPlayer, final Card eventSubject,
            final EffectConsequence consequence) {
        int bestDelta = 0;
        final List<Card> potentialTargets = new ArrayList<>();
        potentialTargets.addAll(eventPlayer.getCreaturesInPlay());
        if (eventSubject.isCreature()) {
            potentialTargets.add(eventSubject);
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
