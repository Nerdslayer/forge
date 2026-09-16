package forge.ai.effect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import forge.game.trigger.TriggerType;

/** Converts relationship-supported event triggers into bounded intrinsic inputs. */
public final class IntrinsicEventTriggerAdapter {
    private static final Set<String> SUPPORTED_TOKEN_FILTERS = Set.of(
            "Card", "Card.token", "Card.token+YouCtrl", "Creature", "Creature.YouCtrl",
            "Creature.YouOwn");
    private static final Set<String> SUPPORTED_LAND_FILTERS = Set.of(
            "Land", "Land.YouCtrl", "Land.OppCtrl");

    private IntrinsicEventTriggerAdapter() {
    }

    public static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters) {
        final Optional<IntrinsicEventTrigger> spellCast = IntrinsicSpellCastTriggerAdapter.describe(parameters);
        if (spellCast.isPresent()) {
            return spellCast;
        }
        final Optional<IntrinsicEventTrigger> abilityCast = IntrinsicAbilityCastTriggerAdapter.describe(parameters);
        if (abilityCast.isPresent()) {
            return abilityCast;
        }
        if (isSupportedManaTrigger(parameters)) {
            return Optional.of(new IntrinsicEventTrigger(
                    IntrinsicReferenceModel.EventType.MANA_ADDED_OR_SPENT,
                    manaTurnScope(parameters), false, manaOccurrenceMultiplier(parameters)));
        }
        if (!EventTriggerParser.hasSupportedParameters(parameters)) {
            return Optional.empty();
        }
        final TriggerType mode = EventTriggerParser.mode(parameters);
        if (mode == TriggerType.LandPlayed) {
            return describeLandPlayed(parameters);
        }
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
        if (IntrinsicAbilityCastTriggerAdapter.supports(parameters)) {
            return true;
        }
        if (isSupportedManaTrigger(parameters)) {
            return true;
        }
        if (!EventTriggerParser.hasSupportedParameters(parameters)) {
            return false;
        }
        final TriggerType mode = EventTriggerParser.mode(parameters);
        if (mode == TriggerType.LandPlayed) {
            return supportsLandPlayed(parameters);
        }
        if (EventTriggerParser.isSecondMainTappedCheckpoint(parameters)) {
            return true;
        }
        if (mode == TriggerType.TokenCreated || mode == TriggerType.TokenCreatedOnce) {
            // TokenCreatedOnce commonly omits ValidPlayer because it is already scoped to the
            // active token-creation batch. Keep both forms limited to the same known token
            // population; the event rate supplies the once-per-turn distinction.
            return (!parameters.containsKey("ValidPlayer")
                    || "You".equals(parameters.get("ValidPlayer")))
                    && !parameters.containsKey("ValidCard")
                    && SUPPORTED_TOKEN_FILTERS.contains(
                            parameters.getOrDefault("ValidToken", "Card"));
        }
        if (mode == TriggerType.Attacks) {
            return !parameters.containsKey("ValidPlayer") && !parameters.containsKey("ValidToken")
                    && Set.of("Card.Self", "Creature.Self").contains(parameters.getOrDefault("ValidCard", ""));
        }
        if (mode == TriggerType.Taps) {
            // A self tap is represented by the generic tapped-event population. If the script
            // explicitly requires tapping as an attacker, use the narrower attack population
            // instead; non-attacker and attached/board-wide filters need richer populations.
            return Set.of("Card.Self", "Creature.Self").contains(parameters.getOrDefault("ValidCard", ""))
                    && !parameters.containsKey("ValidPlayer")
                    && (!parameters.containsKey("Attacker")
                        || "True".equalsIgnoreCase(parameters.get("Attacker")))
                    && (!parameters.containsKey("FirstTime") || isBoolean(parameters.get("FirstTime")));
        }
        if (mode == TriggerType.Drawn) {
            // Draws can happen on either player's turn, so the player recipient changes the
            // population being observed, not the turn scope used by the reference model.
            return !parameters.containsKey("ValidToken") && !parameters.containsKey("ValidCard")
                    && Set.of("You", "Opponent", "Player", "Player.Opponent")
                            .contains(parameters.getOrDefault("ValidPlayer", "Player"));
        }
        if (mode == TriggerType.CounterAdded || mode == TriggerType.CounterAddedOnce) {
            // Counter type is deliberately not used to change the generic counter rate yet. The
            // reference model assumes a supported counter event; thresholds, FirstTime, and
            // board-wide/other-object predicates need richer counter populations.
            return parameters.get("ValidCard") != null
                    && Set.of("Card.Self", "Creature.Self").contains(parameters.get("ValidCard"))
                    && (!parameters.containsKey("ValidSource")
                            || "You".equals(parameters.get("ValidSource")))
                    && !parameters.containsKey("CounterAmount")
                    && !parameters.containsKey("FirstTime")
                    && (!parameters.containsKey("ActivationLimit")
                            || "1".equals(parameters.get("ActivationLimit")));
        }
        if (mode == TriggerType.LifeGained) {
            // The reference life-gain rate represents the source controller's life events. A
            // different source or player scope is still represented by the same coarse rate; a
            // future live/reference model can split event populations by recipient.
            return Set.of("You", "Opponent", "Player", "Player.Opponent")
                    .contains(parameters.get("ValidPlayer"))
                    && !parameters.containsKey("ValidSource");
        }
        if (mode == TriggerType.LifeLost || mode == TriggerType.LifeLostAll) {
            // Opponent and controller life loss use the same conservative event rate for now.
            // Amount thresholds and per-turn clauses need a distribution of event sizes.
            return parameters.get("ValidPlayer") != null
                    && Set.of("You", "Opponent").contains(parameters.get("ValidPlayer"))
                    && !parameters.containsKey("LifeAmount")
                    && !parameters.containsKey("ValidAmountEach");
        }
        if (mode == TriggerType.Discarded || mode == TriggerType.DiscardedAll) {
            // Discard event rates do not know card identity, but explicit controller/opponent
            // scopes are represented well enough by the reference hand-size distribution.
            final String player = parameters.get("ValidPlayer");
            final String card = parameters.get("ValidCard");
            final boolean playerScope = player != null
                    && Set.of("You", "Opponent", "Player", "Player.Opponent").contains(player);
            final boolean controllerCard = card != null
                    && Set.of("Card.YouCtrl", "Card.YouOwn").contains(card);
            final boolean opponentCard = card != null
                    && Set.of("Card.OppCtrl", "Card.OppOwn").contains(card);
            final boolean allCards = "Card".equals(card);
            return !parameters.containsKey("ValidCause")
                    && (!parameters.containsKey("ActivationLimit")
                            || "1".equals(parameters.get("ActivationLimit")))
                    && (playerScope && (card == null || allCards
                            || ("You".equals(player) && controllerCard)
                            || ("Opponent".equals(player) && opponentCard))
                        || !playerScope && (controllerCard || opponentCard));
        }
        if (mode == TriggerType.DamageDone || mode == TriggerType.DamageDoneOnce) {
            final String source = parameters.get("ValidSource");
            final String target = parameters.get("ValidTarget");
            final boolean selfSource = source != null
                    && Set.of("Card.Self", "Creature.Self").contains(source);
            final boolean playerTarget = target != null
                    && Set.of("Player", "Opponent").contains(target);
            final boolean selfTarget = target != null
                    && Set.of("Card.Self", "Creature.Self").contains(target);
            final boolean combatFilter = !parameters.containsKey("CombatDamage")
                    || "True".equalsIgnoreCase(parameters.get("CombatDamage"))
                    || "False".equalsIgnoreCase(parameters.get("CombatDamage"));
            return combatFilter && !parameters.containsKey("DamageAmount")
                    && ((selfSource && playerTarget)
                        || (mode == TriggerType.DamageDoneOnce && selfTarget && source == null));
        }
        if (mode == TriggerType.DamageDealtOnce) {
            return parameters.get("ValidSource") != null
                    && Set.of("Card.Self", "Creature.Self").contains(parameters.get("ValidSource"))
                    && !parameters.containsKey("AtLeastOneInstance");
        }
        if (mode == TriggerType.ChangesZone) {
            return supportsCreatureDeath(parameters);
        }
        if (mode == TriggerType.Sacrificed || mode == TriggerType.SacrificedOnce) {
            return supportsSacrifice(parameters);
        }
        return false;
    }

    private static Optional<IntrinsicEventTrigger> describeLandPlayed(
            final Map<String, String> parameters) {
        return supportsLandPlayed(parameters)
                ? Optional.of(new IntrinsicEventTrigger(
                        IntrinsicReferenceModel.EventType.LAND_PLAYED,
                        landPlayedTurnScope(parameters), false,
                        landPlayedOccurrenceMultiplier(parameters)))
                : Optional.empty();
    }

    private static boolean supportsLandPlayed(final Map<String, String> parameters) {
        if (parameters.containsKey("ValidSA") || parameters.containsKey("Origin")
                && !Set.of("Any", "Hand").contains(parameters.get("Origin"))) {
            return false;
        }
        return SUPPORTED_LAND_FILTERS.contains(parameters.getOrDefault("ValidCard", "Land"))
                && (!parameters.containsKey("NotFirstLand")
                        || isBoolean(parameters.get("NotFirstLand")));
    }

    private static IntrinsicEventTrigger.TurnScope landPlayedTurnScope(
            final Map<String, String> parameters) {
        return switch (parameters.getOrDefault("ValidCard", "Land")) {
        case "Land.YouCtrl" -> IntrinsicEventTrigger.TurnScope.CONTROLLER_TURN;
        case "Land.OppCtrl" -> IntrinsicEventTrigger.TurnScope.OPPONENT_TURN;
        default -> IntrinsicEventTrigger.TurnScope.ANY_TURN;
        };
    }

    private static double landPlayedOccurrenceMultiplier(final Map<String, String> parameters) {
        return "True".equalsIgnoreCase(parameters.get("NotFirstLand")) ? .25 : 1;
    }

    /**
     * Supports the first intrinsic slice of {@code TapsForMana}. The live relationship analyzer
     * does not yet normalize mana events, so this adapter intentionally remains intrinsic-only.
     * We model broad land and self mana-source filters, while subtype, attachment, produced-mana,
     * and condition-dependent filters need a richer reference resource model.
     */
    private static boolean isSupportedManaTrigger(final Map<String, String> parameters) {
        if (parameters == null || EventTriggerParser.mode(parameters) != TriggerType.TapsForMana) {
            return false;
        }
        final Set<String> supportedParameters = Set.of("Mode", "ValidCard", "Activator",
                "TriggerZones", "Execute", "TriggerDescription", "Static");
        if (!supportedParameters.containsAll(parameters.keySet())
                || !validBooleanParameter(parameters, "Static")) {
            return false;
        }
        if (parameters.containsKey("TriggerZones")
                && !"Battlefield".equalsIgnoreCase(parameters.get("TriggerZones"))) {
            return false;
        }
        final String validCard = parameters.get("ValidCard");
        return Set.of("Land", "Land.Basic", "Land.nonBasic", "Card.Self", "Creature.Self",
                "Creature").contains(validCard)
                && (!parameters.containsKey("Activator")
                    || Set.of("You", "Opponent").contains(parameters.get("Activator")));
    }

    private static IntrinsicEventTrigger.TurnScope manaTurnScope(final Map<String, String> parameters) {
        return switch (parameters.getOrDefault("Activator", "Any")) {
        case "You" -> IntrinsicEventTrigger.TurnScope.CONTROLLER_TURN;
        case "Opponent" -> IntrinsicEventTrigger.TurnScope.OPPONENT_TURN;
        default -> IntrinsicEventTrigger.TurnScope.ANY_TURN;
        };
    }

    private static double manaOccurrenceMultiplier(final Map<String, String> parameters) {
        return switch (parameters.getOrDefault("ValidCard", "Land")) {
        case "Land" -> 1;
        case "Land.Basic" -> .65;
        case "Land.nonBasic" -> .35;
        case "Creature" -> .45;
        case "Card.Self", "Creature.Self" -> .40;
        default -> 0;
        };
    }

    private static boolean validBooleanParameter(final Map<String, String> parameters,
            final String name) {
        return !parameters.containsKey(name) || isBoolean(parameters.get(name));
    }

    private static boolean supportsCreatureDeath(final Map<String, String> parameters) {
        // This first intrinsic zone slice is deliberately limited to deaths observed while the
        // source remains on the battlefield. Self-only death triggers need a departure-aware
        // occurrence model; do not value them using the source-survival event estimator.
        if (!"Battlefield".equalsIgnoreCase(parameters.get("Origin"))
                || !"Graveyard".equalsIgnoreCase(parameters.get("Destination"))) {
            return false;
        }
        return supportsDeathFilter(parameters.get("ValidCard"));
    }

    private static boolean supportsDeathFilter(final String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        boolean hasNonSelfCreature = false;
        for (final String part : value.split(",")) {
            final String filter = part.trim();
            if ("Creature".equalsIgnoreCase(filter)
                    || "Creature.Other".equalsIgnoreCase(filter)
                    || "Creature.YouCtrl".equalsIgnoreCase(filter)
                    || "Creature.OppCtrl".equalsIgnoreCase(filter)
                    || "Creature.Other+YouCtrl".equalsIgnoreCase(filter)
                    || "Creature.Other+OppCtrl".equalsIgnoreCase(filter)) {
                hasNonSelfCreature = true;
            } else if (!"Card.Self".equalsIgnoreCase(filter)
                    && !"Creature.Self".equalsIgnoreCase(filter)) {
                return false;
            }
        }
        return hasNonSelfCreature;
    }

    private static boolean supportsSacrifice(final Map<String, String> parameters) {
        if (parameters.containsKey("ValidCause")) {
            return false;
        }
        final String player = parameters.get("ValidPlayer");
        if (player != null && !Set.of("You", "Opponent", "Player", "Player.Opponent").contains(player)) {
            return false;
        }
        final String value = parameters.get("ValidCard");
        if (value == null || value.isBlank()) {
            return false;
        }
        for (final String part : value.split(",")) {
            final String filter = part.trim();
            if (!Set.of("Permanent", "Permanent.Other", "Permanent.YouCtrl", "Permanent.OppCtrl",
                    "Creature", "Creature.Other", "Creature.YouCtrl", "Creature.OppCtrl",
                    "Creature.Other+YouCtrl", "Creature.Other+OppCtrl").contains(filter)) {
                return false;
            }
        }
        return true;
    }

    private static IntrinsicReferenceModel.EventType eventType(final TriggerType mode,
            final EffectType observed, final Map<String, String> parameters) {
        if (mode == TriggerType.Taps && "True".equalsIgnoreCase(parameters.get("Attacker"))) {
            return IntrinsicReferenceModel.EventType.ATTACK;
        }
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
                || mode == TriggerType.Taps && "True".equalsIgnoreCase(parameters.get("Attacker"))
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
                || mode == TriggerType.Taps && "True".equalsIgnoreCase(parameters.get("FirstTime"))
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

    private static boolean isBoolean(final String value) {
        return "True".equalsIgnoreCase(value) || "False".equalsIgnoreCase(value);
    }
}
