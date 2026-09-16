package forge.ai.effect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import forge.game.trigger.TriggerType;

/** Recognizes common battlefield attachment and detachment triggers. */
final class IntrinsicAttachmentTriggerAdapter {
    private static final Set<String> ATTACHED_PARAMETERS = Set.of(
            "Mode", "ValidSource", "ValidTarget", "TargetRelativeToSource", "ActivationLimit",
            "Execute", "TriggerZones", "TriggerDescription", "Static", "Secondary");
    private static final Set<String> UNATTACHED_PARAMETERS = Set.of(
            "Mode", "ValidObject", "ValidAttachment", "ActivationLimit", "Execute",
            "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> ATTACHED_SOURCES = Set.of(
            "Card.Self", "Aura", "Aura.YouCtrl", "Equipment", "Equipment.YouCtrl");
    private static final Set<String> ATTACHED_TARGETS = Set.of(
            "Card.Self", "Creature", "Creature.YouCtrl", "Creature.OppCtrl", "Permanent",
            "Permanent.YouCtrl", "Permanent.OppCtrl");
    private static final Set<String> RELATIVE_TARGETS = Set.of("YouCtrl", "OppCtrl", "Card.Self");
    private static final Set<String> UNATTACHED_OBJECTS = Set.of(
            "Card.Self", "Creature", "Creature.YouCtrl", "Permanent", "Permanent.YouCtrl");
    private static final Set<String> UNATTACHED_ATTACHMENTS = Set.of("Card.Self", "Aura", "Equipment");

    private IntrinsicAttachmentTriggerAdapter() {
    }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters) {
        if (!supports(parameters)) {
            return Optional.empty();
        }
        final TriggerType mode = EventTriggerParser.mode(parameters);
        return Optional.of(new IntrinsicEventTrigger(
                IntrinsicReferenceModel.EventType.ATTACHED_OR_UNATTACHED,
                IntrinsicEventTrigger.TurnScope.ANY_TURN, atMostOncePerTurn(parameters),
                occurrenceMultiplier(parameters)));
    }

    static boolean supports(final Map<String, String> parameters) {
        if (parameters == null) {
            return false;
        }
        final TriggerType mode = EventTriggerParser.mode(parameters);
        if (mode == TriggerType.Attached) {
            return supportsAttached(parameters);
        }
        if (mode == TriggerType.Unattached) {
            return supportsUnattached(parameters);
        }
        return false;
    }

    private static boolean supportsAttached(final Map<String, String> parameters) {
        if (!ATTACHED_PARAMETERS.containsAll(parameters.keySet())
                || !supportsBattlefield(parameters)
                || !supportsActivationLimit(parameters)
                || parameters.containsKey("Static") && !isBoolean(parameters.get("Static"))) {
            return false;
        }
        return (!parameters.containsKey("ValidSource")
                    || ATTACHED_SOURCES.contains(parameters.get("ValidSource")))
                && (!parameters.containsKey("ValidTarget")
                    || ATTACHED_TARGETS.contains(parameters.get("ValidTarget")))
                && (!parameters.containsKey("TargetRelativeToSource")
                    || RELATIVE_TARGETS.contains(parameters.get("TargetRelativeToSource")));
    }

    private static boolean supportsUnattached(final Map<String, String> parameters) {
        return UNATTACHED_PARAMETERS.containsAll(parameters.keySet())
                && supportsBattlefield(parameters)
                && supportsActivationLimit(parameters)
                && (!parameters.containsKey("ValidObject")
                    || UNATTACHED_OBJECTS.contains(parameters.get("ValidObject")))
                && (!parameters.containsKey("ValidAttachment")
                    || UNATTACHED_ATTACHMENTS.contains(parameters.get("ValidAttachment")));
    }

    private static boolean supportsBattlefield(final Map<String, String> parameters) {
        return !parameters.containsKey("TriggerZones")
                || "Battlefield".equalsIgnoreCase(parameters.get("TriggerZones"));
    }

    private static boolean supportsActivationLimit(final Map<String, String> parameters) {
        return !parameters.containsKey("ActivationLimit")
                || "1".equals(parameters.get("ActivationLimit"));
    }

    private static boolean atMostOncePerTurn(final Map<String, String> parameters) {
        return parameters.containsKey("ActivationLimit");
    }

    private static double occurrenceMultiplier(final Map<String, String> parameters) {
        final TriggerType mode = EventTriggerParser.mode(parameters);
        final double event = mode == TriggerType.Attached ? .80 : .55;
        final String source = parameters.getOrDefault("ValidSource", "Any");
        final double sourceFactor = switch (source) {
        case "Card.Self" -> .90;
        case "Aura", "Equipment" -> .70;
        case "Aura.YouCtrl", "Equipment.YouCtrl" -> .65;
        default -> 1;
        };
        final String target = parameters.getOrDefault(
                mode == TriggerType.Attached ? "ValidTarget" : "ValidObject", "Any");
        final double targetFactor = switch (target) {
        case "Card.Self" -> .85;
        case "Creature", "Permanent" -> .70;
        case "Creature.YouCtrl", "Permanent.YouCtrl" -> .75;
        case "Creature.OppCtrl", "Permanent.OppCtrl" -> .55;
        default -> 1;
        };
        final double relativeFactor = parameters.containsKey("TargetRelativeToSource") ? .75 : 1;
        return event * sourceFactor * targetFactor * relativeFactor;
    }

    private static boolean isBoolean(final String value) {
        return "True".equalsIgnoreCase(value) || "False".equalsIgnoreCase(value);
    }
}
