package forge.ai.combat;

import java.util.Optional;
import java.util.Set;

import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;

/** Frozen unconditional public prevention policy; source departure is checked per strike batch. */
public record CombatDamagePreventionRule(int providerId, int controllerId, Scope scope, boolean survivesCleanup) {
    public enum Scope { ALL, SELF, CONTROLLER, OPPONENT }

    public boolean applies(final PublicCombatSnapshot.Creature source, final Set<Integer> lostProviders) {
        return !lostProviders.contains(providerId) && switch (scope) {
            case ALL -> true;
            case SELF -> source.id() == providerId;
            case CONTROLLER -> source.controllerId() == controllerId;
            case OPPONENT -> source.controllerId() != controllerId;
        };
    }

    public static boolean supported(final StaticAbility ability) {
        final var params = ability.getMapParams();
        return "CantPreventDamage".equals(params.get("Mode"))
                && Set.of("Mode", "ValidSource", "IsCombat", "EffectZone", "Secondary", "Description").containsAll(params.keySet())
                && Set.of("", "Card", "Creature", "Card.Self", "Creature.Self", "Card.YouCtrl", "Creature.YouCtrl",
                        "Card.OppCtrl", "Creature.OppCtrl").contains(params.getOrDefault("ValidSource", ""))
                && Set.of("True", "False").contains(params.getOrDefault("IsCombat", "True"))
                && Set.of("Battlefield", "Command").contains(params.getOrDefault("EffectZone", "Battlefield"));
        // TODO: Conditional/source-property expressions and duration/zone variants need explicit
        // state-dependent recalculation, not a one-time global canDamagePrevented flag.
    }

    public static Optional<CombatDamagePreventionRule> parse(final StaticAbility ability) {
        if (!supported(ability) || !ability.checkConditions(StaticAbilityMode.CantPreventDamage)
                || "False".equals(ability.getMapParams().get("IsCombat"))) { return Optional.empty(); }
        final var host = ability.getHostCard();
        final Scope scope = switch (ability.getMapParams().getOrDefault("ValidSource", "")) {
            case "Card.Self", "Creature.Self" -> Scope.SELF;
            case "Card.YouCtrl", "Creature.YouCtrl" -> Scope.CONTROLLER;
            case "Card.OppCtrl", "Creature.OppCtrl" -> Scope.OPPONENT;
            default -> Scope.ALL;
        };
        return Optional.of(new CombatDamagePreventionRule(host.getId(), host.getController().getId(), scope, host.isInPlay()));
    }
}
