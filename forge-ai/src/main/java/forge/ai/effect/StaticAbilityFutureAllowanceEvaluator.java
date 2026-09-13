package forge.ai.effect;

import java.util.Optional;
import java.util.Set;

import forge.ai.effect.IntrinsicReferenceModel.CreatureProfile;
import forge.card.CardType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;

/**
 * Adds a small, fixed allowance for simple static effects that can matter after new permanents
 * arrive. It intentionally does not project the board or reuse the current recipient set.
 */
final class StaticAbilityFutureAllowanceEvaluator {
    private static final int FUTURE_RECIPIENTS_PER_SIDE = 2;
    private static final double TRIBAL_FUTURE_RECIPIENTS_PER_SIDE = 1.5;
    private static final double FUTURE_RECIPIENT_SURVIVAL = .70;
    private static final Set<String> ALLOWED_PARAMS = Set.of(
            "Mode", "Affected", "AddPower", "AddToughness", "Description");

    private StaticAbilityFutureAllowanceEvaluator() {
    }

    static Optional<AbilityValueContribution> evaluate(final Player evaluatingAi,
            final Card source, final StaticAbility ability) {
        if (evaluatingAi == null || source == null || ability == null
                || !ability.isIntrinsic()
                || ability.getKeyword() != null
                || !ability.checkConditions(StaticAbilityMode.Continuous)) {
            return Optional.empty();
        }

        // Future value is intentionally stricter than live static relationship analysis. A
        // condition, dynamic amount, grant, cost, or second effect must not silently become a
        // generic anthem. TODO: Add validated adapters for keywords, restrictions, dynamic
        // predicates, characteristic-defining abilities, and other static forms.
        if (!ALLOWED_PARAMS.containsAll(ability.getMapParams().keySet())
                || !"Continuous".equals(ability.getParam("Mode"))) {
            return Optional.empty();
        }

        final Scope scope = Scope.parse(ability.getParamOrDefault("Affected", ""));
        if (scope == null) {
            return Optional.empty();
        }

        final boolean hasPower = ability.hasParam("AddPower");
        final boolean hasToughness = ability.hasParam("AddToughness");
        if (!hasPower && !hasToughness) {
            return Optional.empty();
        }
        final Integer powerChange = hasPower ? literalInteger(ability.getParam("AddPower")) : 0;
        final Integer toughnessChange = hasToughness
                ? literalInteger(ability.getParam("AddToughness")) : 0;
        if (powerChange == null || toughnessChange == null) {
            return Optional.empty();
        }

        // Avoid pretending to score deaths or negative-power combat heuristics using a clamped
        // reference body. Those require richer state handling than this simple anthem delta.
        if (powerChange < -2 || toughnessChange <= -2 || powerChange > 20 || toughnessChange > 20) {
            return Optional.empty();
        }
        final int automaticPerRecipient = creatureDelta(powerChange, toughnessChange);
        // AIEffectValue is deliberately not in ALLOWED_PARAMS. Hints supplement current live
        // recipient evaluation, but are not enough evidence for a future generic recipient.
        final int perRecipient = automaticPerRecipient;
        if (perRecipient == 0) {
            return Optional.empty();
        }

        int signedValue = 0;
        if (scope.includesController()) {
            signedValue = EffectMath.add(signedValue,
                    signedForRecipient(evaluatingAi, source.getController(), perRecipient));
        }
        if (scope.includesOpponent()) {
            if (source.getGame() == null || source.getGame().getPlayers().size() != 2) {
                return Optional.empty();
            }
            signedValue = EffectMath.add(signedValue,
                    signedForRecipient(evaluatingAi, evaluatingAi, perRecipient));
        }
        final boolean tribal = java.util.Arrays.stream(ability.getParam("Affected")
                .replace("Creature.", "").split("\\+")).anyMatch(CardType::isACreatureType);
        final double futureRecipients = tribal ? TRIBAL_FUTURE_RECIPIENTS_PER_SIDE : FUTURE_RECIPIENTS_PER_SIDE;
        signedValue = EffectMath.multiply(futureRecipients * FUTURE_RECIPIENT_SURVIVAL,
                signedValue);
        if (signedValue == 0) {
            return Optional.empty();
        }

        final AbilityIdentity identity = AbilityIdentity.forStaticAbility(source, ability);
        return Optional.of(AbilityValueContribution.counted(source, source, identity, null, null,
                AbilityValueKind.INTRINSIC_FUTURE_ALLOWANCE, signedValue,
                identity.path() + ":future-recipients",
                "Fixed future static allowance for " + scope.description()
                        + " (" + perRecipient + " points x " + futureRecipients
                        + " future recipients x " + FUTURE_RECIPIENT_SURVIVAL + " allowance discount)"));
    }

    private static int creatureDelta(final int powerChange, final int toughnessChange) {
        final CreatureProfile before = new CreatureProfile(true, 2, 2, java.util.Set.of(), false, false);
        final CreatureProfile after = new CreatureProfile(true, Math.max(0, 2 + powerChange),
                Math.max(0, 2 + toughnessChange), java.util.Set.of(), false, false);
        return new IntrinsicOutcomeEvaluator().evaluateCreatureDelta(before, after, true);
    }

    private static int signedForRecipient(final Player evaluatingAi, final Player recipient,
            final int valueToRecipient) {
        return recipient != null && recipient.isOpponentOf(evaluatingAi)
                ? valueToRecipient : EffectMath.negate(valueToRecipient);
    }

    private static Integer literalInteger(final String value) {
        if (value == null || !value.trim().matches("-?\\d+")) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (final NumberFormatException ignored) {
            return null;
        }
    }

    private enum Scope {
        CONTROLLER,
        OPPONENT,
        BOTH;

        static Scope parse(final String affected) {
            if (affected == null) {
                return null;
            }
            return switch (affected.trim()) {
            case "Creature.YouCtrl", "Creature.YouCtrl+Other" -> CONTROLLER;
            case "Creature.OppCtrl", "Creature.OppCtrl+Other" -> OPPONENT;
            case "Creature", "Creature.Other" -> BOTH;
            default -> simpleTribalScope(affected);
            };
        }

        private static Scope simpleTribalScope(final String affected) {
            if (affected == null) {
                return null;
            }
            // Parse only a conjunction of controller/other qualifiers and one real creature
            // subtype. A capitalized validity predicate (Tapped, Attacking, etc.) is NOT a tribe.
            if (!affected.startsWith("Creature.")) { return null; }
            final String[] pieces = affected.substring("Creature.".length()).split("\\+", -1);
            Scope scope = BOTH;
            boolean controller = false;
            boolean tribe = false;
            boolean other = false;
            for (final String piece : pieces) {
                if ("YouCtrl".equals(piece) || "OppCtrl".equals(piece)) {
                    if (controller) { return null; }
                    controller = true;
                    scope = "YouCtrl".equals(piece) ? CONTROLLER : OPPONENT;
                } else if ("Other".equals(piece)) {
                    if (other) { return null; }
                    other = true;
                } else if (CardType.isACreatureType(piece)) {
                    if (tribe) { return null; }
                    tribe = true;
                } else { return null; }
            }
            return scope;
        }

        boolean includesController() {
            return this == CONTROLLER || this == BOTH;
        }

        boolean includesOpponent() {
            return this == OPPONENT || this == BOTH;
        }

        String description() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
