package forge.ai.effect;

import java.util.Set;

import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;

/** Extracts supported event-triggered consequences independently of production origins. */
final class TriggeredConsequenceExtractor implements EffectConsequenceExtractor {
    static final TriggeredConsequenceExtractor INSTANCE = new TriggeredConsequenceExtractor();

    // TODO(effect analysis): Support the remaining trigger families and richer token/counter
    // forms, including optional/limited triggers, CounterAddedAll, broader player constraints,
    // conditions, intervening-if clauses, and triggers active outside the battlefield. Combat
    // support excludes group declaration/once triggers, attacked-alone, first-attack,
    // poisoned-player, complex blocker-count, and multi-defender conditions.

    private static final Set<String> TOKEN_CREATED_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidPlayer", "ValidToken", "OnlyFirst", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> TOKEN_CREATED_ONCE_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidToken", "OnlyFirst", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> COUNTER_ADDED_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "ValidPlayer", "ValidSource", "CounterType",
            "CounterAmount", "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> COUNTER_ADDED_ONCE_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidEntity", "ValidCard", "ValidPlayer", "ValidSource", "CounterType",
            "FirstTime", "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> LIFE_GAINED_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidPlayer", "ValidSource", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> CARD_DRAWN_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "ValidPlayer", "Number", "FirstCardInDrawStep",
            "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> CARD_DRAWN_VALID_CARDS = Set.of(
            "Card", "Card.YouCtrl", "Card.YouOwn", "Card.OppCtrl", "Card.OppOwn");
    private static final Set<String> CARD_DRAWN_VALID_PLAYERS = Set.of(
            "Player", "Opponent", "Player.Opponent");
    private static final Set<String> DAMAGE_DONE_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidSource", "ValidTarget", "CombatDamage", "DamageAmount",
            "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> DAMAGE_DONE_ONCE_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidSource", "ValidTarget", "CombatDamage", "DamageAmount",
            "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> DAMAGE_DEALT_ONCE_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidSource", "ValidTarget", "CombatDamage", "AtLeastOneInstance",
            "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> ATTACKS_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> BLOCKS_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "ValidBlocked", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> ATTACKER_BLOCKED_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> ATTACKER_BLOCKED_BY_CREATURE_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "ValidBlocker", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> ATTACKER_UNBLOCKED_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "ValidDefender", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> CHANGES_ZONE_TRIGGER_PARAMS = Set.of(
            "Mode", "Origin", "Destination", "ValidCard", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> CHANGES_ZONE_ALL_TRIGGER_PARAMS = Set.of(
            "Mode", "Origin", "Destination", "ValidCards", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");

    private TriggeredConsequenceExtractor() {
    }

    @Override
    public EffectConsequence extract(final Card source, final Trigger trigger) {
        final EffectType observedType = observedType(trigger);
        if (observedType == null || !EffectAbilityUtils.isActiveBattlefieldTrigger(source, trigger)
                || !hasSupportedParameters(trigger)) {
            return null;
        }

        final SpellAbility outcome = EffectAbilityUtils.copyTriggerOutcome(source, trigger);
        if (outcome == null) {
            return null;
        }
        outcome.setActivatingPlayer(source.getController());
        outcome.resetTargets();
        final OutcomeEvaluator outcomeEvaluator = OutcomeEvaluatorRegistry.find(outcome);
        if (outcomeEvaluator == null) {
            return null;
        }
        final Trigger normalizedTrigger = normalizedTrigger(source, trigger);
        return new EffectConsequence(source, observedType, normalizedTrigger, outcome, outcomeEvaluator);
    }

    private static EffectType observedType(final Trigger trigger) {
        if (trigger.getMode() == TriggerType.TokenCreated
                || trigger.getMode() == TriggerType.TokenCreatedOnce) {
            return EffectType.TOKEN_CREATED;
        }
        if (trigger.getMode() == TriggerType.LifeGained) {
            return EffectType.LIFE_GAINED;
        }
        if (trigger.getMode() == TriggerType.Drawn) {
            return EffectType.CARD_DRAWN;
        }
        if (trigger.getMode() == TriggerType.DamageDone
                || trigger.getMode() == TriggerType.DamageDoneOnce
                || trigger.getMode() == TriggerType.DamageDealtOnce) {
            return EffectType.DAMAGE_DEALT;
        }
        if (trigger.getMode() == TriggerType.ChangesZone
                || trigger.getMode() == TriggerType.ChangesZoneAll) {
            return EffectType.ZONE_CHANGED;
        }
        if (trigger.getMode() == TriggerType.Attacks
                || trigger.getMode() == TriggerType.Blocks
                || trigger.getMode() == TriggerType.AttackerBlocked
                || trigger.getMode() == TriggerType.AttackerBlockedByCreature
                || trigger.getMode() == TriggerType.AttackerUnblocked) {
            return EffectType.ATTACKED_OR_BLOCKED;
        }
        return trigger.getMode() == TriggerType.CounterAdded
                || trigger.getMode() == TriggerType.CounterAddedOnce
                ? EffectType.COUNTER_ADDED : null;
    }

    private static boolean hasSupportedParameters(final Trigger trigger) {
        if (trigger.getMode() == TriggerType.TokenCreated) {
            return EffectAbilityUtils.hasOnlyParams(trigger, TOKEN_CREATED_TRIGGER_PARAMS)
                    && "You".equals(trigger.getParam("ValidPlayer"));
        }
        if (trigger.getMode() == TriggerType.TokenCreatedOnce) {
            return EffectAbilityUtils.hasOnlyParams(trigger, TOKEN_CREATED_ONCE_TRIGGER_PARAMS);
        }
        if (trigger.getMode() == TriggerType.CounterAdded) {
            return EffectAbilityUtils.hasOnlyParams(trigger, COUNTER_ADDED_TRIGGER_PARAMS);
        }
        if (trigger.getMode() == TriggerType.LifeGained) {
            return EffectAbilityUtils.hasOnlyParams(trigger, LIFE_GAINED_TRIGGER_PARAMS);
        }
        if (trigger.getMode() == TriggerType.Drawn) {
            return hasSupportedCardDrawParameters(trigger);
        }
        if (trigger.getMode() == TriggerType.DamageDone) {
            return EffectAbilityUtils.hasOnlyParams(trigger, DAMAGE_DONE_TRIGGER_PARAMS);
        }
        if (trigger.getMode() == TriggerType.DamageDoneOnce) {
            return EffectAbilityUtils.hasOnlyParams(trigger, DAMAGE_DONE_ONCE_TRIGGER_PARAMS);
        }
        if (trigger.getMode() == TriggerType.DamageDealtOnce) {
            return EffectAbilityUtils.hasOnlyParams(trigger, DAMAGE_DEALT_ONCE_TRIGGER_PARAMS);
        }
        if (trigger.getMode() == TriggerType.ChangesZone) {
            return EffectAbilityUtils.hasOnlyParams(trigger, CHANGES_ZONE_TRIGGER_PARAMS);
        }
        if (trigger.getMode() == TriggerType.ChangesZoneAll) {
            return EffectAbilityUtils.hasOnlyParams(trigger, CHANGES_ZONE_ALL_TRIGGER_PARAMS);
        }
        if (trigger.getMode() == TriggerType.Attacks) {
            return EffectAbilityUtils.hasOnlyParams(trigger, ATTACKS_TRIGGER_PARAMS);
        }
        if (trigger.getMode() == TriggerType.Blocks) {
            return EffectAbilityUtils.hasOnlyParams(trigger, BLOCKS_TRIGGER_PARAMS);
        }
        if (trigger.getMode() == TriggerType.AttackerBlocked) {
            return EffectAbilityUtils.hasOnlyParams(trigger, ATTACKER_BLOCKED_TRIGGER_PARAMS);
        }
        if (trigger.getMode() == TriggerType.AttackerBlockedByCreature) {
            return EffectAbilityUtils.hasOnlyParams(
                    trigger, ATTACKER_BLOCKED_BY_CREATURE_TRIGGER_PARAMS);
        }
        if (trigger.getMode() == TriggerType.AttackerUnblocked) {
            return EffectAbilityUtils.hasOnlyParams(trigger, ATTACKER_UNBLOCKED_TRIGGER_PARAMS);
        }
        return trigger.getMode() == TriggerType.CounterAddedOnce
                && EffectAbilityUtils.hasOnlyParams(trigger, COUNTER_ADDED_ONCE_TRIGGER_PARAMS);
    }

    private static boolean hasSupportedCardDrawParameters(final Trigger trigger) {
        if (!EffectAbilityUtils.hasOnlyParams(trigger, CARD_DRAWN_TRIGGER_PARAMS)
                || (trigger.hasParam("ValidCard")
                        && !CARD_DRAWN_VALID_CARDS.contains(trigger.getParam("ValidCard")))
                || (trigger.hasParam("ValidPlayer")
                        && !CARD_DRAWN_VALID_PLAYERS.contains(trigger.getParam("ValidPlayer")))
                || (trigger.hasParam("FirstCardInDrawStep")
                        && !"True".equalsIgnoreCase(trigger.getParam("FirstCardInDrawStep"))
                        && !"False".equalsIgnoreCase(trigger.getParam("FirstCardInDrawStep")))) {
            return false;
        }
        if (!trigger.hasParam("Number")) {
            return true;
        }
        try {
            return Integer.parseInt(trigger.getParam("Number")) > 0;
        } catch (final NumberFormatException ignored) {
            return false;
        }
    }

    private static Trigger normalizedTrigger(final Card source, final Trigger trigger) {
        if ((trigger.getMode() == TriggerType.CounterAdded
                || trigger.getMode() == TriggerType.CounterAddedOnce)
                && "Any".equalsIgnoreCase(trigger.getParam("CounterType"))) {
            final Trigger normalized = trigger.copy(source, true);
            normalized.removeParam("CounterType");
            return normalized;
        }
        return trigger;
    }
}
