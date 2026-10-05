package forge.ai.effect;

import java.util.LinkedHashMap;
import java.util.Map;

import forge.ai.effect.CardAbilityTraversal.AbilityDescription;
import forge.ai.effect.CardAbilityTraversal.Origin;

/** Equivalent script encodings normalized only when the watched recipient is unambiguous. */
final class IntrinsicTriggerBindingNormalizer {
    private IntrinsicTriggerBindingNormalizer() { }

    static AbilityDescription normalize(final AbilityDescription ability) {
        if (ability.origin() != Origin.TRIGGER) {
            return ability;
        }
        final var triggerParameters = AbilityOptionality.triggerParameters(ability.parameters());
        final String mode = triggerParameters.getOrDefault("Mode", "");
        final boolean cast = "SpellCast".equals(mode) || "AbilityCast".equals(mode);
        final boolean targeted = "BecomesTarget".equals(mode) || "BecomesTargetOnce".equals(mode);
        final String eventPlayer = cast ? fixedPlayer(triggerParameters.get("ValidActivatingPlayer"))
                : targeted ? switch (triggerParameters.getOrDefault("ValidSource", "")) {
                    case "Spell.YouCtrl", "SpellAbility.YouCtrl" -> "You";
                    case "Spell.OppCtrl", "SpellAbility.OppCtrl" -> "Opponent";
                    default -> null;
                } : null;
        if (eventPlayer != null) {
            return new AbilityDescription(ability.path(), ability.origin(), ability.provenance(), ability.parameters(),
                    bindRecipient(ability.outcome(), eventPlayer,
                            cast ? java.util.Set.of("TriggeredActivator") : targetingAliases(triggerParameters), 0));
        }
        // TODO: Source-card controllers
        // may differ from ability activators; do not equate their event identities or resolve
        // payment choices (ward/taxes) merely because the payer's identity is known.
        final var damagedPlayer = IntrinsicOutgoingCombatDamageBinding.describe(triggerParameters, null).isPresent()
                ? java.util.Optional.of("Opponent") : IntrinsicGroupCombatDamageAdapter.damagedPlayer(triggerParameters);
        if (damagedPlayer.isPresent()) {
            return new AbilityDescription(ability.path(), ability.origin(), ability.provenance(), ability.parameters(),
                    bindDamagedPlayer(ability.outcome(), damagedPlayer.get(), 0));
        }
        if (IntrinsicSelfDeathTriggerAdapter.bindableSourceEvent(AbilityOptionality.triggerParameters(ability.parameters()))) {
            return new AbilityDescription(ability.path(), ability.origin(), ability.provenance(), ability.parameters(),
                    bindController(ability.outcome(), "You", false, 0));
        }
        if (!"Drawn".equals(ability.parameters().get("Mode"))) { return ability; }
        final String player = switch (ability.parameters().getOrDefault("ValidPlayer", "")) {
        case "You" -> "You";
        case "Opponent", "Player.Opponent" -> "Opponent";
        default -> null;
        };
        final String recipient = switch (ability.parameters().getOrDefault("ValidCard", "")) {
        case "Card.YouCtrl", "Card.YouOwn" -> "You";
        case "Card.OppCtrl", "Card.OppOwn" -> "Opponent";
        case "" -> player;
        default -> null;
        };
        if (recipient == null || ability.parameters().containsKey("ValidPlayer")
                && !recipient.equals(player)) {
            return ability;
        }
        final Map<String, String> parameters = new LinkedHashMap<>(ability.parameters());
        parameters.remove("ValidCard");
        parameters.put("ValidPlayer", recipient);
        return new AbilityDescription(ability.path(), ability.origin(), ability.provenance(), parameters,
                bindController(ability.outcome(), recipient, true, 0));
    }

    static java.util.Optional<java.util.List<WeightedValue<AbilityDescription>>> recipientCases(
            final AbilityDescription ability, final IntrinsicReferenceModel model) {
        if (ability.origin() != Origin.TRIGGER) { return java.util.Optional.empty(); }
        final var parameters = AbilityOptionality.triggerParameters(ability.parameters());
        final String mode = parameters.getOrDefault("Mode", "");
        final boolean cast = "SpellCast".equals(mode) || "AbilityCast".equals(mode);
        final boolean targeted = "BecomesTarget".equals(mode) || "BecomesTargetOnce".equals(mode);
        if (cast && !java.util.Set.of("", "Player").contains(parameters.getOrDefault("ValidActivatingPlayer", ""))
                || targeted && !java.util.Set.of("", "Spell", "SpellAbility", "Activated")
                        .contains(parameters.getOrDefault("ValidSource", ""))
                || !cast && !targeted) { return java.util.Optional.empty(); }
        final var aliases = cast ? java.util.Set.of("TriggeredActivator") : targetingAliases(parameters);
        final var friendly = bindRecipient(ability.outcome(), "You", aliases, 0);
        if (java.util.Objects.equals(friendly, ability.outcome())) { return java.util.Optional.empty(); }
        final boolean self = targeted && java.util.Set.of("Card.Self", "Creature.Self")
                .contains(parameters.getOrDefault("ValidTarget", ""));
        final var quantity = self ? IntrinsicReferenceQuantities.Quantity.SELF_TARGETING_CASTER_IS_OPPONENT
                : IntrinsicReferenceQuantities.Quantity.EVENT_CASTER_IS_OPPONENT;
        // One event identity is shared by all choices/continuations. Keep trigger parameters
        // untouched so conditional recipient weights do not double or recalibrate occurrences.
        // TODO: Multiplayer, joint caster/target populations and caster-dependent conditions.
        return java.util.Optional.of(model.quantities().distribution(quantity).entries().stream().map(reference ->
                new WeightedValue<>(new AbilityDescription(ability.path(), ability.origin(), ability.provenance(), ability.parameters(),
                        reference.value() == 0 ? friendly : bindRecipient(ability.outcome(), "Opponent", aliases, 0)), reference.weight())).toList());
    }

    private static java.util.Set<String> targetingAliases(final Map<String, String> parameters) {
        // The source card of a spell on the stack has that spell's controller. An activated
        // ability can instead be activated by someone other than its host card's controller.
        return java.util.Set.of("Spell", "Spell.YouCtrl", "Spell.OppCtrl").contains(parameters.getOrDefault("ValidSource", ""))
                ? java.util.Set.of("TriggeredSourceSAController", "TriggeredSourceController")
                : java.util.Set.of("TriggeredSourceSAController");
    }

    private static String fixedPlayer(final String player) {
        if (player == null) { return null; }
        return switch (player) {
            case "You" -> "You";
            case "Opponent", "Player.Opponent" -> "Opponent";
            default -> null;
        };
    }

    private static AbilityOutcomeDescription bindRecipient(final AbilityOutcomeDescription node,
            final String recipient, final java.util.Set<String> aliases, final int depth) {
        if (node == null || depth > 24 || "ImmediateTrigger".equals(node.api()) || "DelayedTrigger".equals(node.api())) { return node; }
        final Map<String, String> parameters = bindRecipientParameters(node.parameters(), recipient, aliases);
        return new AbilityOutcomeDescription(node.path(), node.api(), parameters,
                node.choices().stream().map(choice -> bindRecipient(choice, recipient, aliases, depth + 1)).toList(),
                bindRecipient(node.next(), recipient, aliases, depth + 1), node.issue());
    }

    static Map<String, String> bindRecipientParameters(final Map<String, String> original,
            final String recipient, final java.util.Set<String> aliases) {
        final Map<String, String> parameters = new LinkedHashMap<>(original);
        // These are player-recipient fields. Never substitute an event card/ability object,
        // a validity expression, or a scalar just because it contains an alias as a substring.
        for (final String key : java.util.List.of("Defined", "TokenOwner", "DefinedPlayer")) {
            final String defined = parameters.get(key);
            if (defined == null) { continue; }
            final String[] parts = defined.split("&", -1);
            if (java.util.Arrays.stream(parts).allMatch(part -> aliases.contains(part.trim())
                    || java.util.Set.of("You", "Opponent").contains(part.trim()))) {
                parameters.put(key, java.util.Arrays.stream(parts).map(String::trim)
                        .map(part -> aliases.contains(part) ? recipient : part).distinct()
                        .collect(java.util.stream.Collectors.joining(" & ")));
            }
        }
        return parameters;
    }

    private static AbilityOutcomeDescription bindDamagedPlayer(final AbilityOutcomeDescription node,
            final String recipient, final int depth) {
        // TODO: Multiplayer, noncombat targets, event LKI and new event scopes must retain
        // their own identity; a creature target's controller is not this damaged player.
        return bindRecipient(node, recipient, java.util.Set.of("TriggeredTarget"), depth);
    }

    private static AbilityOutcomeDescription bindController(final AbilityOutcomeDescription node,
            final String recipient, final boolean drawer, final int depth) {
        // TODO: Generic/all-player draws need separate recipient cases; card-type/history filters,
        // ownership-changing draw replacements, watched targets, nested new events and LKI must
        // be bound explicitly, not globally substituted as this reference drawer.
        return bindRecipient(node, recipient, drawer ? java.util.Set.of("TriggeredCardController", "TriggeredPlayer")
                : java.util.Set.of("TriggeredCardController"), depth);
    }
}
