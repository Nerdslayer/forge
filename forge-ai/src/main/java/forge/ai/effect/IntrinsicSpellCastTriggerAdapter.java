package forge.ai.effect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import forge.game.trigger.TriggerType;

/** Recognizes the first conservative slice of intrinsic spell-cast trigger filters. */
final class IntrinsicSpellCastTriggerAdapter {
    private static final Set<String> SUPPORTED_PARAMETERS = Set.of(
            "Mode", "ValidCard", "ValidActivatingPlayer", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> SUPPORTED_CARD_FILTERS = Set.of(
            "Card", "Instant", "Sorcery", "Instant,Sorcery", "Sorcery,Instant", "Creature",
            "Artifact", "Enchantment", "Planeswalker", "Land", "nonCreature", "Card.nonCreature");

    private IntrinsicSpellCastTriggerAdapter() {
    }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters) {
        if (!supports(parameters)) {
            return Optional.empty();
        }
        // The reference rate is a controller-turn spell rate. Flash/instant-speed casting during
        // another player's turn and the frequency of copied spells remain intentionally deferred.
        return Optional.of(new IntrinsicEventTrigger(IntrinsicReferenceModel.EventType.SPELL_CAST,
                IntrinsicEventTrigger.TurnScope.CONTROLLER_TURN, false));
    }

    static boolean supports(final Map<String, String> parameters) {
        if (parameters == null || EventTriggerParser.mode(parameters) != TriggerType.SpellCast
                || !SUPPORTED_PARAMETERS.containsAll(parameters.keySet())
                || !"You".equals(parameters.get("ValidActivatingPlayer"))) {
            return false;
        }
        if (parameters.containsKey("TriggerZones")
                && !"Battlefield".equalsIgnoreCase(parameters.get("TriggerZones"))) {
            return false;
        }
        return SUPPORTED_CARD_FILTERS.contains(parameters.getOrDefault("ValidCard", "Card"));
    }
}
