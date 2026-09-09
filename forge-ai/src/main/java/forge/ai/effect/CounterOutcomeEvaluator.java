package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterKeywordType;
import forge.game.card.CounterType;
import forge.game.spellability.SpellAbility;

/** Values supported counter consequences through permanent evaluation. */
final class CounterOutcomeEvaluator implements OutcomeEvaluator {
    static final CounterOutcomeEvaluator INSTANCE = new CounterOutcomeEvaluator();

    // The planner handles fixed counter choices, target groups, and sequences.
    // TODO(effect analysis): Support additional counter types, player/group recipients, optional
    // and multi-counter choices and distribution. Fixed lists mean "choose one";
    // ChooseDifferent, RandomType, UniqueType, CounterTypePerDefined,
    // and similar modifiers remain unsupported. Dynamic amounts that cannot currently be calculated
    // fail closed. Stun counters receive CreatureEvaluator's counter penalty but do not simulate
    // the associated tap.

    private static final Set<CounterEnumType> SUPPORTED_COUNTER_TYPES = Set.of(
            CounterEnumType.P1P1, CounterEnumType.M1M1, CounterEnumType.LOYALTY,
            CounterEnumType.SHIELD, CounterEnumType.STUN);

    private static final Set<String> SUPPORTED_PARAMS = Set.of(
            "DB", "ValidTgts", "ValidTgtsDesc", "TgtPrompt", "CounterType", "CounterNum",
            "Defined", "SpellDescription", "StackDescription");

    private CounterOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        if (outcome == null || outcome.getApi() != ApiType.PutCounter
                || outcome.getSubAbility() != null || !outcome.hasParam("CounterType")) {
            return false;
        }
        final List<CounterType> counterTypes = parseCounterTypes(
                outcome.getParam("CounterType"));
        return SUPPORTED_PARAMS.containsAll(outcome.getMapParams().keySet())
                && !counterTypes.isEmpty()
                && counterTypes.stream().allMatch(CounterOutcomeEvaluator::supportsCounterType)
                && !outcome.getParamOrDefault("CounterNum", "1").isBlank()
                && (outcome.usesTargeting()
                        ? AffectedCardResolver.supportsSingleBattlefieldTarget(outcome)
                        : !outcome.hasParam("Defined")
                                || !outcome.getParam("Defined").isBlank());
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome, final OutcomeEvaluationContext context) {
        final int counterAmount = AbilityUtils.calculateAmount(outcome.getHostCard(),
                outcome.getParamOrDefault("CounterNum", "1"), outcome);
        if (counterAmount <= 0) {
            return 0;
        }
        final List<CounterType> types = parseCounterTypes(outcome.getParam("CounterType"));
        if (types.size() > 1) {
            return PlannedOutcomeEvaluator.INSTANCE.evaluateOutcome(outcome, context);
        }
        final CounterType counterType = types.get(0);
        final AffectedCardResolver.Resolution resolution = outcome.usesTargeting()
                ? AffectedCardResolver.targeted(outcome, context,
                        card -> supportsRecipient(card, counterType) && card.canReceiveCounters(counterType))
                : AffectedCardResolver.defined(outcome, context,
                        card -> supportsRecipient(card, counterType) && card.canReceiveCounters(counterType));
        return CardStateDeltaEvaluator.evaluate(outcome, context, resolution,
                target -> evaluateTarget(context, target, counterType, counterAmount));
    }

    private static int evaluateTarget(final OutcomeEvaluationContext context,
            final Card target, final CounterType counterType, final int counterAmount) {
        try {
            final Card changed = CardCopyService.getLKICopy(target);
            if (target.getZone() != null) {
                changed.setZone(target.getZone());
            }
            changed.setCounters(counterType, EffectMath.add(
                    changed.getCounters(counterType), counterAmount));
            if (counterType instanceof CounterKeywordType) {
                changed.addChangedCardKeywords(List.of(counterType.toString()), List.of(),
                        false, context.timestamp(target.getGame()), null, false);
                changed.updateKeywordsCache();
            }
            return CardStateDeltaEvaluator.evaluateChange(context, target, changed);
        } catch (final RuntimeException ignored) {
            return context.unsupported();
        }
    }

    private static boolean supportsRecipient(final Card card, final CounterType counterType) {
        return counterType == CounterEnumType.LOYALTY
                ? card.isPlaneswalker() : card.isCreature();
    }

    private static boolean supportsCounterType(final CounterType counterType) {
        return SUPPORTED_COUNTER_TYPES.contains(counterType)
                || counterType instanceof CounterKeywordType;
    }

    private static List<CounterType> parseCounterTypes(final String definition) {
        final List<CounterType> types = new ArrayList<>();
        if (definition == null || definition.isBlank()) {
            return types;
        }
        for (final String name : definition.split(",")) {
            final CounterType type = CounterType.getType(name.trim());
            if (type == null || types.contains(type)) {
                continue;
            }
            types.add(type);
        }
        return types;
    }
}
