package forge.ai.effect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import forge.game.trigger.TriggerType;

/** Recognizes common battlefield attack and block trigger forms. */
final class IntrinsicCombatTriggerAdapter {
    private static final Set<String> SUPPORTED_PARAMETERS = Set.of(
            "Mode", "ValidCard", "ValidBlocked", "ValidBlocker", "ValidDefender",
            "AttackingPlayer", "AttackedTarget", "ValidAttackers", "ValidAttackersAmount",
            "ActivationLimit", "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> SELF_CARDS = Set.of("Card.Self", "Creature.Self");
    private static final Set<TriggerType> GROUP_MODES = Set.of(
            TriggerType.AttackersDeclared, TriggerType.AttackersDeclaredOneTarget,
            TriggerType.BlockersDeclared);
    private static final Set<String> CARD_FILTERS = Set.of(
            "Card.Self", "Creature.Self", "Creature", "Creature.YouCtrl", "Creature.OppCtrl",
            "Permanent", "Permanent.YouCtrl", "Permanent.OppCtrl");
    private static final Set<String> BLOCKED_FILTERS = Set.of(
            "Creature", "Creature.YouCtrl", "Creature.OppCtrl", "Permanent", "Permanent.YouCtrl",
            "Permanent.OppCtrl");
    private static final Set<String> DEFENDERS = Set.of("Player", "Opponent", "You", "Planeswalker");
    private static final Set<String> ATTACKING_PLAYERS =
            Set.of("You", "Controller", "Opponent", "Player", "Player.Other");
    private static final Set<String> ATTACKED_TARGETS =
            Set.of("Player", "You", "Opponent", "Planeswalker");
    private static final Set<String> ATTACKER_FILTERS =
            Set.of("Creature", "Creature.YouCtrl", "Creature.OppCtrl");

    private IntrinsicCombatTriggerAdapter() {
    }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters) {
        if (!supports(parameters)) {
            return Optional.empty();
        }
        final TriggerType mode = EventTriggerParser.mode(parameters);
        final boolean group = GROUP_MODES.contains(mode);
        return Optional.of(new IntrinsicEventTrigger(eventType(parameters),
                group ? turnScope(parameters) : IntrinsicEventTrigger.TurnScope.ANY_TURN,
                atMostOncePerTurn(parameters), occurrenceMultiplier(parameters),
                group ? minimumEventAmount(parameters) : null));
    }

    static boolean supports(final Map<String, String> parameters) {
        if (parameters == null || !SUPPORTED_PARAMETERS.containsAll(parameters.keySet())
                || !supportsBattlefield(parameters) || !supportsActivationLimit(parameters)) {
            return false;
        }
        final TriggerType mode = EventTriggerParser.mode(parameters);
        if (!Set.of(TriggerType.Attacks, TriggerType.Blocks, TriggerType.AttackerBlocked,
                TriggerType.AttackerBlockedOnce, TriggerType.AttackerBlockedByCreature,
                TriggerType.AttackerUnblocked, TriggerType.AttackerUnblockedOnce)
                .contains(mode) && !GROUP_MODES.contains(mode)) {
            return false;
        }
        if (GROUP_MODES.contains(mode)) {
            // The generic group event rate does not model attacker/blocker count or target
            // restrictions yet, so only the common bounded declaration filters are safe here.
            return !parameters.containsKey("ValidCard") && !parameters.containsKey("ValidBlocked")
                    && !parameters.containsKey("ValidBlocker") && !parameters.containsKey("ValidDefender")
                    && supportsGroupFilters(parameters);
        }
        if (parameters.containsKey("ValidCard")
                && !CARD_FILTERS.contains(parameters.get("ValidCard"))) {
            return false;
        }
        if (parameters.containsKey("ValidBlocked")
                && !BLOCKED_FILTERS.contains(parameters.get("ValidBlocked"))) {
            return false;
        }
        if (parameters.containsKey("ValidBlocker")
                && !BLOCKED_FILTERS.contains(parameters.get("ValidBlocker"))) {
            return false;
        }
        if (parameters.containsKey("ValidDefender")
                && !DEFENDERS.contains(parameters.get("ValidDefender"))) {
            return false;
        }
        if (mode == TriggerType.Attacks || mode == TriggerType.Blocks
                || mode == TriggerType.AttackerBlocked || mode == TriggerType.AttackerBlockedOnce
                || mode == TriggerType.AttackerBlockedByCreature
                || mode == TriggerType.AttackerUnblocked || mode == TriggerType.AttackerUnblockedOnce) {
            return !parameters.containsKey("ValidCard")
                    || SELF_CARDS.contains(parameters.get("ValidCard"));
        }
        return false;
    }

    private static IntrinsicReferenceModel.EventType eventType(
            final Map<String, String> parameters) {
        return switch (EventTriggerParser.mode(parameters)) {
        case Attacks -> IntrinsicReferenceModel.EventType.ATTACK;
        case Blocks -> IntrinsicReferenceModel.EventType.BLOCK;
        case AttackersDeclared, AttackersDeclaredOneTarget ->
                IntrinsicReferenceModel.EventType.ATTACKERS_DECLARED;
        case BlockersDeclared -> IntrinsicReferenceModel.EventType.BLOCKERS_DECLARED;
        case AttackerBlocked, AttackerBlockedOnce, AttackerBlockedByCreature ->
                IntrinsicReferenceModel.EventType.ATTACKER_BLOCKED;
        case AttackerUnblocked, AttackerUnblockedOnce ->
                IntrinsicReferenceModel.EventType.ATTACKER_UNBLOCKED;
        default -> throw new IllegalArgumentException("Unsupported combat trigger");
        };
    }

    private static boolean atMostOncePerTurn(final Map<String, String> parameters) {
        final TriggerType mode = EventTriggerParser.mode(parameters);
        return mode == TriggerType.AttackerBlockedOnce || mode == TriggerType.AttackerUnblockedOnce
                || parameters.containsKey("ActivationLimit");
    }

    private static double occurrenceMultiplier(final Map<String, String> parameters) {
        final TriggerType mode = EventTriggerParser.mode(parameters);
        final double event = switch (mode) {
        case Attacks -> .90;
        case Blocks -> .70;
        case AttackersDeclared, AttackersDeclaredOneTarget -> .90;
        case BlockersDeclared -> .70;
        case AttackerBlocked, AttackerBlockedOnce, AttackerBlockedByCreature -> .65;
        case AttackerUnblocked, AttackerUnblockedOnce -> .70;
        default -> 1;
        };
        final double blocked = parameters.containsKey("ValidBlocked")
                || parameters.containsKey("ValidBlocker") ? .75 : 1;
        final double defender = parameters.containsKey("ValidDefender") ? .75 : 1;
        return event * blocked * defender;
    }

    private static boolean supportsGroupFilters(final Map<String, String> parameters) {
        if (EventTriggerParser.mode(parameters) == TriggerType.BlockersDeclared) {
            return !parameters.containsKey("AttackingPlayer")
                    && !parameters.containsKey("AttackedTarget")
                    && !parameters.containsKey("ValidAttackers")
                    && !parameters.containsKey("ValidAttackersAmount");
        }
        if (parameters.containsKey("ValidAttackers")
                && !ATTACKER_FILTERS.contains(parameters.get("ValidAttackers"))) {
            return false;
        }
        if (parameters.containsKey("ValidAttackersAmount")
                && (!parameters.containsKey("ValidAttackers")
                        || !isSupportedMinimum(parameters.get("ValidAttackersAmount")))) {
            return false;
        }
        if (parameters.containsKey("AttackingPlayer")
                && !ATTACKING_PLAYERS.contains(parameters.get("AttackingPlayer"))) {
            return false;
        }
        return !parameters.containsKey("AttackedTarget")
                || ATTACKED_TARGETS.contains(parameters.get("AttackedTarget"));
    }

    private static boolean isSupportedMinimum(final String value) {
        if (value == null || !value.startsWith("GE")) {
            return false;
        }
        try {
            final int amount = Integer.parseInt(value.substring(2));
            return amount >= 1 && amount <= 4;
        } catch (final NumberFormatException ignored) {
            return false;
        }
    }

    private static Integer minimumEventAmount(final Map<String, String> parameters) {
        if (!parameters.containsKey("ValidAttackersAmount")) {
            return null;
        }
        return Integer.parseInt(parameters.get("ValidAttackersAmount").substring(2));
    }

    private static IntrinsicEventTrigger.TurnScope turnScope(
            final Map<String, String> parameters) {
        final String player = parameters.get("AttackingPlayer");
        if ("You".equalsIgnoreCase(player) || "Controller".equalsIgnoreCase(player)) {
            return IntrinsicEventTrigger.TurnScope.CONTROLLER_TURN;
        }
        if ("Opponent".equalsIgnoreCase(player) || "Player.Other".equalsIgnoreCase(player)) {
            return IntrinsicEventTrigger.TurnScope.OPPONENT_TURN;
        }
        return IntrinsicEventTrigger.TurnScope.ANY_TURN;
    }

    // TODO: Add attacker/blocker count predicates, target filters, complex combat predicates, and
    // source-specific combat likelihood once the intrinsic reference model can represent them.

    private static boolean supportsBattlefield(final Map<String, String> parameters) {
        return !parameters.containsKey("TriggerZones")
                || "Battlefield".equalsIgnoreCase(parameters.get("TriggerZones"));
    }

    private static boolean supportsActivationLimit(final Map<String, String> parameters) {
        return !parameters.containsKey("ActivationLimit")
                || "1".equals(parameters.get("ActivationLimit"));
    }
}
