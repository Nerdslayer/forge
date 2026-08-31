package forge.ai.effect;

import java.util.List;

import forge.ai.ComputerUtilCard;
import forge.ai.ability.AnimateAi;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

/** Values persistent power/toughness changes to battlefield cards. */
final class PermanentPtOutcomeEvaluator implements OutcomeEvaluator {
    static final PermanentPtOutcomeEvaluator INSTANCE = new PermanentPtOutcomeEvaluator();

    private PermanentPtOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        if (outcome == null || !isSupportedApi(outcome.getApi())
                || (!"Permanent".equals(outcome.getParam("Duration"))
                        && !"Perpetual".equals(outcome.getParam("Duration")))
                || !changesPowerOrToughness(outcome)
                || hasUnsupportedControlFlow(outcome)
                || !affectsBattlefield(outcome)) {
            return false;
        }

        if (isGroupEffect(outcome.getApi())) {
            return !outcome.usesTargeting() && !outcome.hasParam("Defined");
        }
        return !outcome.usesTargeting()
                || AffectedCardResolver.supportsSingleBattlefieldTarget(outcome);
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome, final OutcomeEvaluationContext context) {
        try {
            final AffectedCardResolver.Resolution resolution = resolveAffectedCards(outcome, context);
            return CardStateDeltaEvaluator.evaluate(outcome, context, resolution,
                    affected -> evaluateCardDelta(outcome, affected, context));
        } catch (final RuntimeException ignored) {
            // Dynamic or malformed script forms contribute no outcome value.
            return 0;
        }
    }

    private static AffectedCardResolver.Resolution resolveAffectedCards(
            final SpellAbility outcome, final OutcomeEvaluationContext context) {
        if (isGroupEffect(outcome.getApi())) {
            return AffectedCardResolver.group(outcome, context, card -> true);
        }
        if (outcome.usesTargeting()) {
            return AffectedCardResolver.targeted(outcome, context, card -> true);
        }
        return AffectedCardResolver.defined(outcome, context, card -> true);
    }

    private static int evaluateCardDelta(final SpellAbility outcome, final Card affected,
            final OutcomeEvaluationContext context) {
        if (outcome.getApi() == ApiType.Animate || outcome.getApi() == ApiType.AnimateAll) {
            final Card changed = AnimateAi.becomeAnimatedForEvaluation(affected, outcome);
            return CardStateDeltaEvaluator.evaluateChange(context, affected, changed);
        }
        if (!affected.isCreature()) {
            return 0;
        }

        final int power = calculateChange(outcome, "NumAtt", affected.getNetPower());
        final int toughness = calculateChange(outcome, "NumDef", affected.getNetToughness());
        if (power == 0 && toughness == 0) {
            return 0;
        }

        final Card changed = ComputerUtilCard.getPumpedCreatureForEvaluation(
                context.evaluatingAi(), outcome, affected, toughness, power, List.of());
        return CardStateDeltaEvaluator.evaluateChange(context, affected, changed);
    }

    private static int calculateChange(final SpellAbility outcome, final String param,
            final int currentValue) {
        if (!outcome.hasParam(param)) {
            return 0;
        }
        return switch (outcome.getParam(param)) {
        case "Double" -> currentValue;
        case "Triple" -> EffectMath.multiply(currentValue, 2);
        default -> AbilityUtils.calculateAmount(outcome.getHostCard(), outcome.getParam(param), outcome, true);
        };
    }

    private static boolean affectsBattlefield(final SpellAbility outcome) {
        final String zoneParam = outcome.getApi() == ApiType.Animate
                || outcome.getApi() == ApiType.AnimateAll ? "Zone" : "PumpZone";
        return AffectedCardResolver.affectsOnlyBattlefield(outcome, zoneParam);
    }

    private static boolean isSupportedApi(final ApiType api) {
        return api == ApiType.Pump || api == ApiType.PumpAll
                || api == ApiType.Animate || api == ApiType.AnimateAll;
    }

    private static boolean isGroupEffect(final ApiType api) {
        return api == ApiType.PumpAll || api == ApiType.AnimateAll;
    }

    private static boolean changesPowerOrToughness(final SpellAbility outcome) {
        if (outcome.getApi() == ApiType.Animate || outcome.getApi() == ApiType.AnimateAll) {
            return outcome.hasParam("Power") || outcome.hasParam("Toughness");
        }
        return outcome.hasParam("NumAtt") || outcome.hasParam("NumDef");
    }

    private static boolean hasUnsupportedControlFlow(final SpellAbility outcome) {
        for (final String param : outcome.getMapParams().keySet()) {
            if (param.startsWith("Condition") || param.startsWith("Unless")
                    || "Optional".equals(param) || "Radiance".equals(param)) {
                return true;
            }
        }
        return false;
    }
}
