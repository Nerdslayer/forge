package forge.ai.effect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import forge.game.trigger.TriggerType;

/** Recognizes common battlefield attack and block trigger forms. */
final class IntrinsicCombatTriggerAdapter {
    private static final Set<String> SUPPORTED_PARAMETERS = Set.of(
            "Mode", "ValidCard", "ValidBlocked", "ValidBlocker", "ValidDefender",
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

    private IntrinsicCombatTriggerAdapter() {
    }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters) {
        if (!supports(parameters)) {
            return Optional.empty();
        }
        return Optional.of(new IntrinsicEventTrigger(eventType(parameters),
                IntrinsicEventTrigger.TurnScope.ANY_TURN, atMostOncePerTurn(parameters),
                occurrenceMultiplier(parameters)));
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
            // restrictions yet, so only the unfiltered declaration event is safe here.
            return !parameters.containsKey("ValidCard") && !parameters.containsKey("ValidBlocked")
                    && !parameters.containsKey("ValidBlocker") && !parameters.containsKey("ValidDefender");
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
        case Attacks, AttackersDeclared, AttackersDeclaredOneTarget ->
                IntrinsicReferenceModel.EventType.ATTACK;
        case Blocks, BlockersDeclared -> IntrinsicReferenceModel.EventType.BLOCK;
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
        case Attacks, AttackersDeclared, AttackersDeclaredOneTarget -> .90;
        case Blocks, BlockersDeclared -> .70;
        case AttackerBlocked, AttackerBlockedOnce, AttackerBlockedByCreature -> .65;
        case AttackerUnblocked, AttackerUnblockedOnce -> .70;
        default -> 1;
        };
        final double blocked = parameters.containsKey("ValidBlocked")
                || parameters.containsKey("ValidBlocker") ? .75 : 1;
        final double defender = parameters.containsKey("ValidDefender") ? .75 : 1;
        return event * blocked * defender;
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
