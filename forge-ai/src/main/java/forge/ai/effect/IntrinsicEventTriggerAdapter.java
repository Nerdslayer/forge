package forge.ai.effect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import forge.game.trigger.TriggerType;

/** Converts relationship-supported event triggers into bounded intrinsic inputs. */
public final class IntrinsicEventTriggerAdapter {
    private IntrinsicEventTriggerAdapter() {
    }

    public static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters) {
        final Optional<IntrinsicEventTrigger> spellCast = IntrinsicSpellCastTriggerAdapter.describe(parameters);
        if (spellCast.isPresent()) {
            return spellCast;
        }
        if (!EventTriggerParser.hasSupportedParameters(parameters)) {
            return Optional.empty();
        }
        final TriggerType mode = EventTriggerParser.mode(parameters);
        final EffectType observed = EventTriggerParser.observedType(parameters);
        if (mode == null || observed == null) {
            return Optional.empty();
        }

        // Recipient/card restrictions are intentionally retained only as recognition gates here.
        // Their intrinsic probabilities need a richer reference population than this first slice.
        final IntrinsicReferenceModel.EventType eventType = eventType(mode, observed, parameters);
        if (eventType == null) {
            return Optional.empty();
        }
        final IntrinsicEventTrigger.TurnScope turnScope = turnScope(mode, parameters);
        return Optional.of(new IntrinsicEventTrigger(eventType, turnScope,
                atMostOncePerTurn(mode, parameters), occurrenceMultiplier(mode, parameters)));
    }

    /**
     * Returns whether the current reference model can safely interpret this trigger's filters.
     * Event recognition is intentionally broader: this method is the intrinsic evaluation gate.
     */
    static boolean supportsIntrinsicParameters(final Map<String, String> parameters) {
        if (IntrinsicSpellCastTriggerAdapter.supports(parameters)) {
            return true;
        }
        if (!EventTriggerParser.hasSupportedParameters(parameters)) {
            return false;
        }
        final TriggerType mode = EventTriggerParser.mode(parameters);
        if (mode == TriggerType.TokenCreated) {
            return "You".equals(parameters.get("ValidPlayer"))
                    && !parameters.containsKey("ValidCard")
                    && Set.of("Card", "Card.token", "Card.token+YouCtrl")
                            .contains(parameters.getOrDefault("ValidToken", "Card"));
        }
        if (mode == TriggerType.Attacks) {
            return !parameters.containsKey("ValidPlayer") && !parameters.containsKey("ValidToken")
                    && Set.of("Card.Self", "Creature.Self").contains(parameters.getOrDefault("ValidCard", ""));
        }
        if (mode == TriggerType.Drawn) {
            return !parameters.containsKey("ValidToken") && !parameters.containsKey("ValidCard")
                    && "Player".equals(parameters.getOrDefault("ValidPlayer", "Player"));
        }
        if (mode == TriggerType.CounterAdded || mode == TriggerType.CounterAddedOnce) {
            // Counter type is deliberately not used to change the generic counter rate yet. The
            // reference model assumes a supported counter event; thresholds, FirstTime, and
            // board-wide/other-object predicates need richer counter populations.
            return Set.of("Card.Self", "Creature.Self").contains(parameters.get("ValidCard"))
                    && (!parameters.containsKey("ValidSource")
                            || "You".equals(parameters.get("ValidSource")))
                    && !parameters.containsKey("CounterAmount")
                    && !parameters.containsKey("FirstTime")
                    && (!parameters.containsKey("ActivationLimit")
                            || "1".equals(parameters.get("ActivationLimit")));
        }
        if (mode == TriggerType.LifeGained) {
            // The reference life-gain rate represents the source controller's life events. A
            // different source or player scope needs a side-specific event population.
            return "You".equals(parameters.get("ValidPlayer"))
                    && !parameters.containsKey("ValidSource");
        }
        if (mode == TriggerType.LifeLost || mode == TriggerType.LifeLostAll) {
            // Opponent and controller life loss use the same conservative event rate for now.
            // Amount thresholds and per-turn clauses need a distribution of event sizes.
            return Set.of("You", "Opponent").contains(parameters.get("ValidPlayer"))
                    && !parameters.containsKey("LifeAmount")
                    && !parameters.containsKey("ValidAmountEach");
        }
        return false;
    }

    private static IntrinsicReferenceModel.EventType eventType(final TriggerType mode,
            final EffectType observed, final Map<String, String> parameters) {
        if (observed == EffectType.ATTACKED_OR_BLOCKED) {
            return switch (mode) {
            case Attacks -> IntrinsicReferenceModel.EventType.ATTACK;
            case Blocks -> IntrinsicReferenceModel.EventType.BLOCK;
            case AttackerBlocked, AttackerBlockedByCreature ->
                    IntrinsicReferenceModel.EventType.ATTACKER_BLOCKED;
            case AttackerUnblocked -> IntrinsicReferenceModel.EventType.ATTACKER_UNBLOCKED;
            default -> null;
            };
        }
        if (observed == EffectType.DAMAGE_DEALT
                && "True".equalsIgnoreCase(parameters.get("CombatDamage"))) {
            return IntrinsicReferenceModel.EventType.COMBAT_DAMAGE;
        }
        return switch (observed) {
        case TOKEN_CREATED -> IntrinsicReferenceModel.EventType.TOKEN_CREATED;
        case COUNTER_ADDED -> IntrinsicReferenceModel.EventType.COUNTER_ADDED;
        case LIFE_GAINED -> IntrinsicReferenceModel.EventType.LIFE_GAINED;
        case LIFE_LOST -> IntrinsicReferenceModel.EventType.LIFE_LOST;
        case CARD_DRAWN -> IntrinsicReferenceModel.EventType.CARD_DRAWN;
        case CARD_DISCARDED -> IntrinsicReferenceModel.EventType.CARD_DISCARDED;
        case DAMAGE_DEALT -> IntrinsicReferenceModel.EventType.DAMAGE_DEALT;
        case SACRIFICED -> IntrinsicReferenceModel.EventType.PERMANENT_SACRIFICED;
        case ZONE_CHANGED -> zoneEventType(parameters);
        case TAPPED_OR_UNTAPPED -> IntrinsicReferenceModel.EventType.TAPPED;
        default -> null;
        };
    }

    private static IntrinsicReferenceModel.EventType zoneEventType(
            final Map<String, String> parameters) {
        return "Battlefield".equalsIgnoreCase(parameters.get("Origin"))
                && "Graveyard".equalsIgnoreCase(parameters.get("Destination"))
                        ? IntrinsicReferenceModel.EventType.CREATURE_DIED
                        : IntrinsicReferenceModel.EventType.ZONE_CHANGED;
    }

    private static IntrinsicEventTrigger.TurnScope turnScope(final TriggerType mode,
            final Map<String, String> parameters) {
        if (mode == TriggerType.Attacks || mode == TriggerType.AttackerBlocked
                || mode == TriggerType.AttackerBlockedByCreature
                || mode == TriggerType.AttackerUnblocked
                || EventTriggerParser.isSecondMainTappedCheckpoint(parameters)) {
            return IntrinsicEventTrigger.TurnScope.CONTROLLER_TURN;
        }
        if (mode == TriggerType.Blocks) {
            return IntrinsicEventTrigger.TurnScope.OPPONENT_TURN;
        }
        return IntrinsicEventTrigger.TurnScope.ANY_TURN;
    }

    private static boolean atMostOncePerTurn(final TriggerType mode,
            final Map<String, String> parameters) {
        return mode == TriggerType.TokenCreatedOnce || mode == TriggerType.CounterAddedOnce
                || mode == TriggerType.DamageDoneOnce || mode == TriggerType.DamageDealtOnce
                || mode == TriggerType.SacrificedOnce || mode == TriggerType.DiscardedAll
                || mode == TriggerType.LifeLostAll
                || EventTriggerParser.isSecondMainTappedCheckpoint(parameters)
                || "True".equalsIgnoreCase(parameters.get("FirstCardInDrawStep"));
    }

    private static double occurrenceMultiplier(final TriggerType mode,
            final Map<String, String> parameters) {
        if (mode != TriggerType.Drawn || !parameters.containsKey("Number")) {
            return 1;
        }
        try {
            final int number = Integer.parseInt(parameters.get("Number"));
            // The reference model does not simulate a draw sequence. A threshold is therefore
            // treated as a lower-frequency draw event rather than as an unconditional trigger.
            return 1.0 / Math.max(1, number);
        } catch (final NumberFormatException ignored) {
            return 0;
        }
    }
}
