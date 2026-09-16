package forge.ai.effect;

import java.util.Optional;
import java.util.Set;

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
            "Mode", "Affected", "AddPower", "AddToughness", "SetPower", "SetToughness",
            "AddKeyword", "AIEffectValue", "Description");

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
        // generic anthem. TODO: Add validated adapters for restrictions, dynamic predicates,
        // characteristic-defining abilities, permissions, and other static forms.
        if (!ALLOWED_PARAMS.containsAll(ability.getMapParams().keySet())
                || !"Continuous".equals(ability.getParam("Mode"))) {
            return Optional.empty();
        }

        final String affected = ability.getParamOrDefault("Affected", "");
        final StaticAbilityScope scope = StaticAbilityScope.parse(affected);
        if (scope == null || scope == StaticAbilityScope.SELF
                || scope == StaticAbilityScope.ATTACHED) {
            return Optional.empty();
        }

        final boolean hasPower = ability.hasParam("AddPower");
        final boolean hasToughness = ability.hasParam("AddToughness");
        final boolean hasSetPower = ability.hasParam("SetPower");
        final boolean hasSetToughness = ability.hasParam("SetToughness");
        final boolean hasKeyword = ability.hasParam("AddKeyword");
        final boolean hasAutomaticChange = hasPower || hasToughness || hasSetPower
                || hasSetToughness || hasKeyword;
        final boolean hasHint = ability.hasParam("AIEffectValue");
        if (!hasAutomaticChange && !hasHint) {
            return Optional.empty();
        }
        final Integer powerChange = hasPower ? literalInteger(ability.getParam("AddPower")) : 0;
        final Integer toughnessChange = hasToughness
                ? literalInteger(ability.getParam("AddToughness")) : 0;
        final Integer setPower = hasSetPower ? literalInteger(ability.getParam("SetPower")) : Integer.MIN_VALUE;
        final Integer setToughness = hasSetToughness
                ? literalInteger(ability.getParam("SetToughness")) : Integer.MIN_VALUE;
        final Integer hintedValue = hasHint
                ? literalInteger(ability.getParam("AIEffectValue")) : 0;
        if (powerChange == null || toughnessChange == null || setPower == null || setToughness == null
                || hintedValue == null) {
            return Optional.empty();
        }

        // Avoid pretending to score deaths or negative-power combat heuristics using a clamped
        // reference body. Those require richer state handling than this simple anthem delta.
        if (powerChange < -2 || toughnessChange <= -2 || powerChange > 20 || toughnessChange > 20
                || (hasSetPower && (setPower < 0 || setPower > 20))
                || (hasSetToughness && (setToughness <= 0 || setToughness > 20))) {
            return Optional.empty();
        }
        final Set<String> addedKeywords = IntrinsicStaticAbilityEvaluator.parseSupportedKeywords(
                ability.getParam("AddKeyword"));
        if (addedKeywords == null) {
            return Optional.empty();
        }
        if (hasAutomaticChange && !scope.isCreatureScope(affected)) {
            return Optional.empty();
        }
        final int automaticPerRecipient = hasAutomaticChange
                ? creatureDelta(powerChange, toughnessChange, setPower, setToughness, addedKeywords) : 0;
        // A literal AIEffectValue supplements the automatic generic-recipient delta. It is also
        // sufficient for non-creature future recipients, such as a hinted artifact tax.
        final int perRecipient = EffectMath.add(automaticPerRecipient, hintedValue);
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
        final boolean tribal = scope.isTribal(affected);
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
                        + " (" + automaticPerRecipient + " automatic + " + hintedValue
                        + " AIEffectValue supplement = " + perRecipient + " points x " + futureRecipients
                        + " future recipients x " + FUTURE_RECIPIENT_SURVIVAL + " allowance discount)"));
    }

    private static int creatureDelta(final int powerChange, final int toughnessChange,
            final int setPower, final int setToughness, final Set<String> addedKeywords) {
        return IntrinsicStaticAbilityEvaluator.evaluateGenericCreatureDelta(powerChange,
                toughnessChange, setPower, setToughness, addedKeywords);
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

}
