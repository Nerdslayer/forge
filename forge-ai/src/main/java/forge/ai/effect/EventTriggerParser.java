package forge.ai.effect;

import java.util.Map;
import java.util.Set;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;

/** Shared event recognition; activity, outcome support and occurrence belong to consumers. */
final class EventTriggerParser {
    private EventTriggerParser() { }
    // TODO: Broader conditions/parameters still need separate adapters; intrinsic occurrence for
    // the relationship-supported event families is supplied by IntrinsicEventTriggerAdapter.
    // SpellCast is intentionally handled by IntrinsicSpellCastTriggerAdapter because it is an
    // intrinsic reference event, not yet a live EffectType relationship family.
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
            "FirstTime", "ActivationLimit", "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> COUNTER_ADDED_ALL_TRIGGER_PARAMS = Set.of(
            "Mode", "Valid", "ValidSource", "CounterType", "ActivationLimit", "Execute",
            "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> COUNTER_REMOVED_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "ValidPlayer", "CounterType", "NewCounterAmount",
            "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> COUNTER_REMOVED_ONCE_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "CounterType", "Remaining", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> LIFE_GAINED_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidPlayer", "ValidSource", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> LIFE_LOST_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidPlayer", "LifeAmount", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> LIFE_LOST_ALL_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidPlayer", "ValidAmountEach", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> CARD_DRAWN_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "ValidPlayer", "Number", "FirstCardInDrawStep",
            "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> CARD_DRAWN_VALID_CARDS = Set.of(
            "Card", "Card.YouCtrl", "Card.YouOwn", "Card.OppCtrl", "Card.OppOwn");
    private static final Set<String> CARD_DRAWN_VALID_PLAYERS = Set.of(
            "You", "Player", "Opponent", "Player.Opponent");
    private static final Set<String> CARD_DISCARDED_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "ValidPlayer", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary", "ActivationLimit");
    private static final Set<String> CARD_DISCARDED_VALID_CARDS = Set.of(
            "Card", "Card.YouCtrl", "Card.YouOwn", "Card.OppCtrl", "Card.OppOwn");
    private static final Set<String> CARD_DISCARDED_VALID_PLAYERS = Set.of(
            "Player", "You", "Opponent", "Player.Opponent");
    private static final Set<String> MILLED_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "ValidPlayer", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> DAMAGE_DONE_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidSource", "ValidTarget", "CombatDamage", "DamageAmount",
            "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> DAMAGE_DONE_ONCE_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidSource", "ValidTarget", "CombatDamage", "DamageAmount",
            "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> DAMAGE_DEALT_ONCE_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidSource", "ValidTarget", "CombatDamage", "AtLeastOneInstance",
            "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> DAMAGE_ALL_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidSource", "ValidTarget", "CombatDamage", "Execute",
            "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> FIGHT_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> CHANGES_CONTROLLER_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "ValidOriginalController", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
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
    private static final Set<String> TAPS_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "Attacker", "FirstTime", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> SECOND_MAIN_TAPPED_TRIGGER_PARAMS = Set.of(
            "Mode", "Phase", "PhaseCount", "ValidPlayer", "PresentDefined", "IsPresent",
            "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> CHANGES_ZONE_TRIGGER_PARAMS = Set.of(
            "Mode", "Origin", "Destination", "ValidCard", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> CHANGES_ZONE_ALL_TRIGGER_PARAMS = Set.of(
            "Mode", "Origin", "Destination", "ValidCards", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> EXILED_TRIGGER_PARAMS = Set.of(
            "Mode", "Origin", "ValidCard", "ValidCause", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> SACRIFICED_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "ValidPlayer", "ValidCause", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> SACRIFICED_ONCE_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "ValidPlayer", "ValidCause", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> LAND_PLAYED_TRIGGER_PARAMS = Set.of(
            "Mode", "Origin", "ValidCard", "ValidSA", "NotFirstLand", "Execute",
            "TriggerZones", "TriggerDescription", "Secondary");


    static EffectType observedType(final Trigger trigger) {
        return isSecondMainTappedCheckpoint(trigger) ? EffectType.TAPPED_OR_UNTAPPED
                : observedType(trigger.getMode());
    }

    static EffectType observedType(final Map<String, String> parameters) {
        return isSecondMainTappedCheckpoint(parameters) ? EffectType.TAPPED_OR_UNTAPPED
                : observedType(mode(parameters));
    }

    private static EffectType observedType(final TriggerType mode) {
        if (mode == TriggerType.TokenCreated || mode == TriggerType.TokenCreatedOnce) {
            return EffectType.TOKEN_CREATED;
        }
        if (mode == TriggerType.LifeGained) {
            return EffectType.LIFE_GAINED;
        }
        if (mode == TriggerType.LifeLost || mode == TriggerType.LifeLostAll) {
            return EffectType.LIFE_LOST;
        }
        if (mode == TriggerType.Drawn) {
            return EffectType.CARD_DRAWN;
        }
        if (mode == TriggerType.Discarded || mode == TriggerType.DiscardedAll) {
            return EffectType.CARD_DISCARDED;
        }
        if (mode == TriggerType.Milled || mode == TriggerType.MilledOnce
                || mode == TriggerType.MilledAll) {
            return EffectType.CARD_MILLED;
        }
        if (mode == TriggerType.DamageDone || mode == TriggerType.DamageDoneOnce
                || mode == TriggerType.DamageAll
                || mode == TriggerType.DamageDealtOnce) {
            return EffectType.DAMAGE_DEALT;
        }
        if (mode == TriggerType.Fight || mode == TriggerType.FightOnce) {
            return EffectType.FOUGHT;
        }
        if (mode == TriggerType.ChangesZone || mode == TriggerType.ChangesZoneAll
                || mode == TriggerType.Exiled) {
            return EffectType.ZONE_CHANGED;
        }
        if (mode == TriggerType.ChangesController) {
            return EffectType.CONTROL_CHANGED;
        }
        if (mode == TriggerType.Sacrificed || mode == TriggerType.SacrificedOnce) {
            return EffectType.SACRIFICED;
        }
        if (mode == TriggerType.Attacks || mode == TriggerType.Blocks
                || mode == TriggerType.AttackerBlocked
                || mode == TriggerType.AttackerBlockedByCreature
                || mode == TriggerType.AttackerUnblocked) {
            return EffectType.ATTACKED_OR_BLOCKED;
        }
        if (mode == TriggerType.Taps) {
            return EffectType.TAPPED_OR_UNTAPPED;
        }
        if (mode == TriggerType.CounterAdded || mode == TriggerType.CounterAddedOnce
                || mode == TriggerType.CounterAddedAll) {
            return EffectType.COUNTER_ADDED;
        }
        if (mode == TriggerType.CounterRemoved || mode == TriggerType.CounterRemovedOnce) {
            return EffectType.COUNTER_REMOVED;
        }
        return null;
    }

    static boolean hasSupportedParameters(final Map<String, String> parameters) {
        final TriggerType mode = mode(parameters);
        if (mode == null) {
            return false;
        }
        return hasSupportedParameters(mode, parameters);
    }

    private static boolean hasSupportedParameters(final TriggerType mode,
            final Map<String, String> parameters) {
        if (mode == TriggerType.TokenCreated) {
            return hasOnlyParams(parameters, TOKEN_CREATED_TRIGGER_PARAMS)
                    && "You".equals(parameters.get("ValidPlayer"));
        }
        if (mode == TriggerType.TokenCreatedOnce) {
            return hasOnlyParams(parameters, TOKEN_CREATED_ONCE_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.CounterAdded) {
            return hasOnlyParams(parameters, COUNTER_ADDED_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.CounterAddedAll) {
            return hasOnlyParams(parameters, COUNTER_ADDED_ALL_TRIGGER_PARAMS)
                    && (!parameters.containsKey("ActivationLimit")
                            || "1".equals(parameters.get("ActivationLimit")));
        }
        if (mode == TriggerType.CounterRemoved) {
            return hasOnlyParams(parameters, COUNTER_REMOVED_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.CounterRemovedOnce) {
            return hasOnlyParams(parameters, COUNTER_REMOVED_ONCE_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.LifeGained) {
            return hasOnlyParams(parameters, LIFE_GAINED_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.LifeLost) {
            return hasOnlyParams(parameters, LIFE_LOST_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.LifeLostAll) {
            return hasOnlyParams(parameters, LIFE_LOST_ALL_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.Drawn) {
            return hasSupportedCardDrawParameters(parameters);
        }
        if (mode == TriggerType.LandPlayed) {
            return hasSupportedLandPlayedParameters(parameters);
        }
        if (mode == TriggerType.Discarded || mode == TriggerType.DiscardedAll) {
            return hasSupportedCardDiscardParameters(parameters);
        }
        if (mode == TriggerType.Milled || mode == TriggerType.MilledOnce
                || mode == TriggerType.MilledAll) {
            return hasOnlyParams(parameters, MILLED_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.DamageDone) {
            return hasOnlyParams(parameters, DAMAGE_DONE_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.DamageDoneOnce) {
            return hasOnlyParams(parameters, DAMAGE_DONE_ONCE_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.DamageDealtOnce) {
            return hasOnlyParams(parameters, DAMAGE_DEALT_ONCE_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.DamageAll) {
            return hasOnlyParams(parameters, DAMAGE_ALL_TRIGGER_PARAMS)
                    && (!parameters.containsKey("CombatDamage")
                        || isBoolean(parameters.get("CombatDamage")));
        }
        if (mode == TriggerType.Fight || mode == TriggerType.FightOnce) {
            return hasOnlyParams(parameters, FIGHT_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.ChangesZone) {
            return hasOnlyParams(parameters, CHANGES_ZONE_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.ChangesZoneAll) {
            return hasOnlyParams(parameters, CHANGES_ZONE_ALL_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.Exiled) {
            return hasOnlyParams(parameters, EXILED_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.ChangesController) {
            return hasOnlyParams(parameters, CHANGES_CONTROLLER_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.Sacrificed) {
            return hasOnlyParams(parameters, SACRIFICED_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.SacrificedOnce) {
            return hasOnlyParams(parameters, SACRIFICED_ONCE_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.Attacks) {
            return hasOnlyParams(parameters, ATTACKS_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.Blocks) {
            return hasOnlyParams(parameters, BLOCKS_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.AttackerBlocked) {
            return hasOnlyParams(parameters, ATTACKER_BLOCKED_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.AttackerBlockedByCreature) {
            return hasOnlyParams(parameters, ATTACKER_BLOCKED_BY_CREATURE_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.AttackerUnblocked) {
            return hasOnlyParams(parameters, ATTACKER_UNBLOCKED_TRIGGER_PARAMS);
        }
        if (mode == TriggerType.Taps) {
            return hasOnlyParams(parameters, TAPS_TRIGGER_PARAMS);
        }
        if (isSecondMainTappedCheckpoint(parameters)) {
            return hasOnlyParams(parameters, SECOND_MAIN_TAPPED_TRIGGER_PARAMS);
        }
        return mode == TriggerType.CounterAddedOnce
                && hasOnlyParams(parameters, COUNTER_ADDED_ONCE_TRIGGER_PARAMS);
    }

    static TriggerType mode(final Map<String, String> parameters) {
        final String value = parameters == null ? null : parameters.get("Mode");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return TriggerType.smartValueOf(value);
        } catch (final RuntimeException ignored) {
            return null;
        }
    }

    private static boolean hasOnlyParams(final Map<String, String> parameters,
            final Set<String> allowed) {
        return allowed.containsAll(parameters.keySet());
    }

    private static boolean hasSupportedCardDrawParameters(final Map<String, String> parameters) {
        if (!hasOnlyParams(parameters, CARD_DRAWN_TRIGGER_PARAMS)
                || (parameters.containsKey("ValidCard")
                        && !CARD_DRAWN_VALID_CARDS.contains(parameters.get("ValidCard")))
                || (parameters.containsKey("ValidPlayer")
                        && !CARD_DRAWN_VALID_PLAYERS.contains(parameters.get("ValidPlayer")))
                || (parameters.containsKey("FirstCardInDrawStep")
                        && !isBoolean(parameters.get("FirstCardInDrawStep")))) {
            return false;
        }
        if (!parameters.containsKey("Number")) {
            return true;
        }
        try {
            return Integer.parseInt(parameters.get("Number")) > 0;
        } catch (final NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean hasSupportedCardDiscardParameters(final Map<String, String> parameters) {
        return hasOnlyParams(parameters, CARD_DISCARDED_TRIGGER_PARAMS)
                && (!parameters.containsKey("ValidCard")
                        || CARD_DISCARDED_VALID_CARDS.contains(parameters.get("ValidCard")))
                && (!parameters.containsKey("ValidPlayer")
                        || CARD_DISCARDED_VALID_PLAYERS.contains(parameters.get("ValidPlayer")));
    }

    private static boolean hasSupportedLandPlayedParameters(final Map<String, String> parameters) {
        return hasOnlyParams(parameters, LAND_PLAYED_TRIGGER_PARAMS)
                && (!parameters.containsKey("NotFirstLand")
                        || isBoolean(parameters.get("NotFirstLand")));
    }

    private static boolean isBoolean(final String value) {
        return "True".equalsIgnoreCase(value) || "False".equalsIgnoreCase(value);
    }

    static boolean hasSupportedParameters(final Trigger trigger) {
        return hasSupportedParameters(trigger.getMode(), trigger.getMapParams());
    }

    static boolean isSecondMainTappedCheckpoint(final Map<String, String> parameters) {
        return mode(parameters) == TriggerType.Phase
                && "Main".equals(parameters.get("Phase"))
                && "2".equals(parameters.get("PhaseCount"))
                && "You".equals(parameters.get("ValidPlayer"))
                && "Self".equals(parameters.get("PresentDefined"))
                && "Card.tapped".equals(parameters.get("IsPresent"));
    }

    static boolean isSecondMainTappedCheckpoint(final Trigger trigger) {
        return isSecondMainTappedCheckpoint(trigger.getMapParams());
    }
}
