package forge.ai.effect;

import java.util.Set;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CounterEnumType;
import forge.game.spellability.SpellAbility;

/** Values the initial supported targeted P1P1-counter consequence. */
final class CounterOutcomeEvaluator implements OutcomeEvaluator {
    static final CounterOutcomeEvaluator INSTANCE = new CounterOutcomeEvaluator();

    private static final Set<String> SUPPORTED_PARAMS = Set.of(
            "DB", "ValidTgts", "ValidTgtsDesc", "TgtPrompt", "CounterType", "CounterNum",
            "SpellDescription", "StackDescription");

    private CounterOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        return outcome != null
                && outcome.getApi() == ApiType.PutCounter
                && outcome.getSubAbility() == null
                && SUPPORTED_PARAMS.containsAll(outcome.getMapParams().keySet())
                && "P1P1".equals(outcome.getParam("CounterType"))
                && outcome.getParamOrDefault("CounterNum", "1").matches("[1-9]\\d*")
                && AffectedCardResolver.supportsSingleBattlefieldTarget(outcome);
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome, final OutcomeEvaluationContext context) {
        final int counterAmount = Integer.parseInt(outcome.getParamOrDefault("CounterNum", "1"));
        final AffectedCardResolver.Resolution resolution = AffectedCardResolver.targeted(
                outcome, context, card -> card.canReceiveCounters(CounterEnumType.P1P1));
        return CardStateDeltaEvaluator.evaluate(outcome, context, resolution,
                target -> evaluateTarget(context, target, counterAmount));
    }

    private static int evaluateTarget(final OutcomeEvaluationContext context,
            final Card target, final int counterAmount) {
        try {
            final Card changed = CardCopyService.getLKICopy(target);
            if (target.getZone() != null) {
                changed.setZone(target.getZone());
            }
            changed.setCounters(CounterEnumType.P1P1, EffectMath.add(
                    changed.getCounters(CounterEnumType.P1P1), counterAmount));
            return CardStateDeltaEvaluator.evaluateChange(context, target, changed);
        } catch (final RuntimeException ignored) {
            return 0;
        }
    }
}
