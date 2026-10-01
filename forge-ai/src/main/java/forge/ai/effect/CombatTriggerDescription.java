package forge.ai.effect;

import java.util.Optional;
import java.util.Set;
import java.util.Map;

import forge.ai.combat.CombatAbilityKey;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

/** Frozen trigger/outcome structure admitted for concrete first-order combat evaluation. */
public record CombatTriggerDescription(CombatAbilityKey ability, Kind kind, Map<String, String> parameters,
        AbilityOutcomeDescription outcome) {
    public CombatTriggerDescription {
        if (ability == null || kind == null || outcome == null) { throw new IllegalArgumentException("Complete trigger structure required"); }
        parameters = Map.copyOf(parameters);
    }
    public enum Kind { SELF_ATTACK, SELF_BLOCK, SELF_COMBAT_PLAYER_DAMAGE, SELF_DIES }

    public static Optional<CombatTriggerDescription> parse(final Trigger trigger) {
        final var source = trigger.getHostCard();
        if (source == null || source.isFaceDown() || !source.isCreature() || !source.isInPlay() || trigger.isSuppressed()
                || !trigger.zonesCheck(source.getZone())) { return Optional.empty(); }
        final var params = trigger.getMapParams();
        if (!"Battlefield".equals(params.getOrDefault("TriggerZones", "Battlefield"))) {
            return Optional.empty();
        }
        final Kind kind;
        if ("ChangesZone".equals(params.get("Mode"))) {
            if (!EventTriggerParser.hasSupportedParameters(params)
                    || !Set.of("Mode", "ValidCard", "Origin", "Destination", "TriggerZones", "Execute", "TriggerDescription")
                            .containsAll(params.keySet())
                    || !Set.of("Card.Self", "Creature.Self").contains(params.getOrDefault("ValidCard", ""))
                    || !"Battlefield".equals(params.get("Origin"))
                    || !"Graveyard".equals(params.get("Destination"))) { return Optional.empty(); }
            kind = Kind.SELF_DIES;
        } else if ("DamageDone".equals(params.get("Mode"))) {
            // Shared recognition policy still owns which damage parameters are understood.
            if (!EventTriggerParser.hasSupportedParameters(params)
                    || !Set.of("Mode", "ValidSource", "ValidTarget", "CombatDamage", "TriggerZones", "Execute", "TriggerDescription")
                            .containsAll(params.keySet())
                    || !Set.of("Card.Self", "Creature.Self").contains(params.getOrDefault("ValidSource", ""))
                    || !Set.of("Player", "Player.Opponent", "Opponent").contains(params.getOrDefault("ValidTarget", ""))
                    || !"True".equalsIgnoreCase(params.getOrDefault("CombatDamage", "True"))) { return Optional.empty(); }
            kind = Kind.SELF_COMBAT_PLAYER_DAMAGE;
        } else {
            if (!Set.of("Mode", "ValidCard", "TriggerZones", "Execute", "TriggerDescription")
                    .containsAll(params.keySet())
                    || !Set.of("Card.Self", "Creature.Self").contains(params.getOrDefault("ValidCard", ""))) { return Optional.empty(); }
            if ("Attacks".equals(params.get("Mode"))) { kind = Kind.SELF_ATTACK; }
            else if ("Blocks".equals(params.get("Mode"))) { kind = Kind.SELF_BLOCK; }
            else { return Optional.empty(); }
        }
        try {
            final SpellAbility root = EffectAbilityUtils.resolveTriggerOutcome(source, trigger);
            if (root == null || root.getSubAbility() != null || root.usesTargeting()
                    || root.hasParam("OptionalDecider") || EffectAbilityUtils.hasUnsupportedControlFlow(root)) {
                return Optional.empty();
            }
            final var key = new CombatAbilityKey(source.getId(), AbilityIdentity.forTrigger(source, trigger).path());
            final var description = AbilityOutcomeParser.parse(root, key.path() + "/execute");
            final var prepared = new CombatTriggerDescription(key, kind, params, description);
            final var draw = prepared.drawOutcome();
            if (description.issue() != null && !description.issue().isBlank()
                    || !description.choices().isEmpty() || description.next() != null
                    || draw.isEmpty() || draw.orElseThrow().amount() < 0 || draw.orElseThrow().amount() > 128) {
                return Optional.empty();
            }
            return Optional.of(prepared);
        } catch (final RuntimeException unsupported) { return Optional.empty(); }
        // TODO: Shared match tables for other subjects/conditions, other atomic outcomes, targets,
        // choices/sequences, other damage/death/tap events and first-order characteristic updates.
    }

    public Optional<DrawOutcomeDescription> drawOutcome() {
        var params = outcome.parameters();
        if (kind == Kind.SELF_DIES && "TriggeredCardController".equals(params.get("Defined"))) {
            // The watched card is precisely Self. You is bound to its last-known controller in
            // the death batch, not to its owner or its controller after leaving the battlefield.
            final var bound = new java.util.LinkedHashMap<>(params);
            bound.put("Defined", "You");
            params = bound;
        }
        return DrawOutcomeDescription.parse(outcome.api(), params);
    }
}
