package forge.ai.effect;

import java.util.Set;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
import forge.game.spellability.SpellAbility;

/** Values supported targeted counter consequences through permanent evaluation. */
final class CounterOutcomeEvaluator implements OutcomeEvaluator {
    static final CounterOutcomeEvaluator INSTANCE = new CounterOutcomeEvaluator();

    // TODO(effect analysis): Support additional counter types, player and defined/group recipients,
    // dynamic and optional amounts, multiple targets, distribution, and subabilities. Stun counters
    // currently receive CreatureEvaluator's counter penalty but do not simulate the associated tap.

    private static final Set<CounterEnumType> SUPPORTED_COUNTER_TYPES = Set.of(
            CounterEnumType.P1P1, CounterEnumType.M1M1, CounterEnumType.LOYALTY,
            CounterEnumType.SHIELD, CounterEnumType.STUN);

    private static final Set<String> SUPPORTED_PARAMS = Set.of(
            "DB", "ValidTgts", "ValidTgtsDesc", "TgtPrompt", "CounterType", "CounterNum",
            "SpellDescription", "StackDescription");

    private CounterOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        if (outcome == null || outcome.getApi() != ApiType.PutCounter
                || outcome.getSubAbility() != null || !outcome.hasParam("CounterType")) {
            return false;
        }
        final CounterType counterType = CounterType.getType(outcome.getParam("CounterType"));
        return SUPPORTED_PARAMS.containsAll(outcome.getMapParams().keySet())
                && SUPPORTED_COUNTER_TYPES.contains(counterType)
                && outcome.getParamOrDefault("CounterNum", "1").matches("[1-9]\\d*")
                && AffectedCardResolver.supportsSingleBattlefieldTarget(outcome);
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome, final OutcomeEvaluationContext context) {
        final CounterEnumType counterType = (CounterEnumType) CounterType.getType(
                outcome.getParam("CounterType"));
        final int counterAmount = Integer.parseInt(outcome.getParamOrDefault("CounterNum", "1"));
        final AffectedCardResolver.Resolution resolution = AffectedCardResolver.targeted(
                outcome, context, card -> supportsRecipient(card, counterType)
                        && card.canReceiveCounters(counterType));
        return CardStateDeltaEvaluator.evaluate(outcome, context, resolution,
                target -> evaluateTarget(context, target, counterType, counterAmount));
    }

    private static int evaluateTarget(final OutcomeEvaluationContext context,
            final Card target, final CounterEnumType counterType, final int counterAmount) {
        try {
            final Card changed = CardCopyService.getLKICopy(target);
            if (target.getZone() != null) {
                changed.setZone(target.getZone());
            }
            changed.setCounters(counterType, EffectMath.add(
                    changed.getCounters(counterType), counterAmount));
            return CardStateDeltaEvaluator.evaluateChange(context, target, changed);
        } catch (final RuntimeException ignored) {
            return 0;
        }
    }

    private static boolean supportsRecipient(final Card card,
            final CounterEnumType counterType) {
        return counterType == CounterEnumType.LOYALTY
                ? card.isPlaneswalker() : card.isCreature();
    }
}
