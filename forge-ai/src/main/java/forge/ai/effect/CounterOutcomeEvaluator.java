package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

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

    // The planner handles single-counter choices, target groups, and sequences. Basic CounterTypes
    // lists (including fixed counters plus ChosenFromList) are also evaluated as a card-state
    // change. TODO(effect analysis): Support player/group recipients, optional/distributed choices,
    // ChooseDifferent, RandomType, UniqueType, CounterTypePerDefined, and similar modifiers.
    // Dynamic amounts that cannot currently be calculated fail closed. Stun counters receive
    // CreatureEvaluator's counter penalty but do not simulate the associated tap.

    private static final Set<CounterEnumType> SUPPORTED_COUNTER_TYPES = Set.of(
            CounterEnumType.P1P1, CounterEnumType.M1M1, CounterEnumType.LOYALTY,
            CounterEnumType.SHIELD, CounterEnumType.STUN);

    private static final Set<String> SUPPORTED_PARAMS = Set.of(
            "DB", "ValidTgts", "ValidTgtsDesc", "TgtPrompt", "CounterType", "CounterNum",
            "CounterTypes", "TypeList", "Defined", "ValidCards", "EachFromSource",
            "SpellDescription", "StackDescription");

    private CounterOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        if (outcome == null || (outcome.getApi() != ApiType.PutCounter
                && outcome.getApi() != ApiType.PutCounterAll)
                || outcome.getSubAbility() != null) {
            return false;
        }
        if (outcome.hasParam("CounterTypes")) {
            return supportsCounterTypeList(outcome);
        }
        if (!outcome.hasParam("CounterType")) { return false; }
        if (outcome.getApi() == ApiType.PutCounterAll && !outcome.hasParam("ValidCards")) {
            return false;
        }
        if (outcome.getApi() == ApiType.PutCounter && outcome.hasParam("ValidCards")) {
            return false;
        }
        final boolean transfer = "EachFromSource".equals(outcome.getParam("CounterType"))
                && outcome.hasParam("EachFromSource") && !outcome.getParam("EachFromSource").isBlank();
        final List<CounterType> counterTypes = transfer ? List.of() : parseCounterTypes(outcome.getParam("CounterType"));
        return SUPPORTED_PARAMS.containsAll(outcome.getMapParams().keySet())
                && (transfer || !outcome.hasParam("EachFromSource") && !counterTypes.isEmpty())
                && counterTypes.stream().allMatch(CounterOutcomeEvaluator::supportsCounterType)
                && !outcome.getParamOrDefault("CounterNum", "1").isBlank()
                && (outcome.usesTargeting()
                        ? AffectedCardResolver.supportsSingleBattlefieldTarget(outcome)
                        : !outcome.hasParam("Defined")
                                || !outcome.getParam("Defined").isBlank());
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome, final OutcomeEvaluationContext context) {
        if (outcome.hasParam("CounterTypes")) {
            return evaluateCounterTypeList(outcome, context);
        }
        if (outcome.hasParam("EachFromSource")) { return evaluateTransfer(outcome, context); }
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
        final AffectedCardResolver.Resolution resolution;
        if (outcome.getApi() == ApiType.PutCounterAll) {
            resolution = AffectedCardResolver.group(outcome, context,
                    card -> supportsRecipient(card, counterType) && card.canReceiveCounters(counterType));
        } else {
            resolution = outcome.usesTargeting()
                    ? AffectedCardResolver.targeted(outcome, context,
                            card -> supportsRecipient(card, counterType)
                                    && card.canReceiveCounters(counterType))
                    : AffectedCardResolver.defined(outcome, context,
                            card -> supportsRecipient(card, counterType)
                                    && card.canReceiveCounters(counterType));
        }
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

    private static int evaluateTransfer(final SpellAbility outcome, final OutcomeEvaluationContext context) {
        // TODO(effect analysis): Counter replacements and unsupported counter types; this copies
        // counters, not MoveCounter (which must also remove them from its source).
        try {
            final Map<CounterType, Integer> counters = new LinkedHashMap<>();
            final String definition = outcome.getParam("EachFromSource");
            final List<Card> sources = AbilityUtils.getDefinedCards(outcome.getHostCard(), definition, outcome);
            if (sources.isEmpty()) { return context.unsupported(); }
            for (final Card original : sources) {
                // Explicit LKI references must retain the counters at the time the card died.
                final Card source = context.state() == null || definition.contains("LKI")
                        ? original : context.state().card(original);
                if (source == null) { continue; }
                for (final var entry : source.getCounters().entrySet()) {
                    if (!supportsCounterType(entry.getElement())) { return context.unsupported(); }
                    final int amount = outcome.hasParam("CounterNum")
                            ? AbilityUtils.calculateAmount(outcome.getHostCard(), outcome.getParam("CounterNum"), outcome)
                            : entry.getCount();
                    if (amount > 0) { counters.merge(entry.getElement(), amount, EffectMath::add); }
                }
            }
            final AffectedCardResolver.Resolution recipients = outcome.usesTargeting()
                    ? AffectedCardResolver.targeted(outcome, context, card -> true)
                    : AffectedCardResolver.defined(outcome, context, card -> true);
            return CardStateDeltaEvaluator.evaluate(outcome, context, recipients, target -> {
                final Card changed = CardCopyService.getLKICopy(target);
                if (target.getZone() != null) { changed.setZone(target.getZone()); }
                for (final var entry : counters.entrySet()) {
                    final CounterType type = entry.getKey();
                    if (!target.canReceiveCounters(type)) { continue; }
                    if (!supportsRecipient(target, type)) { return context.unsupported(); }
                    changed.setCounters(type, EffectMath.add(changed.getCounters(type), entry.getValue()));
                    if (type instanceof CounterKeywordType) {
                        changed.addChangedCardKeywords(List.of(type.toString()), List.of(), false,
                                context.timestamp(target.getGame()), null, false);
                    }
                }
                changed.updateKeywordsCache();
                return CardStateDeltaEvaluator.evaluateChange(context, target, changed);
            });
        } catch (final RuntimeException ignored) {
            return context.unsupported();
        }
    }

    private static boolean supportsCounterType(final CounterType counterType) {
        return SUPPORTED_COUNTER_TYPES.contains(counterType)
                || counterType instanceof CounterKeywordType;
    }

    private static boolean supportsCounterTypeList(final SpellAbility outcome) {
        if (!SUPPORTED_PARAMS.containsAll(outcome.getMapParams().keySet())
                || !outcome.hasParam("CounterTypes")
                || outcome.hasParam("CounterType")
                || outcome.getParam("CounterTypes").isBlank()
                || outcome.getParamOrDefault("CounterNum", "1").isBlank()) {
            return false;
        }
        if (outcome.getApi() == ApiType.PutCounterAll && !outcome.hasParam("ValidCards")) {
            return false;
        }
        if (outcome.getApi() == ApiType.PutCounter && outcome.hasParam("ValidCards")) {
            return false;
        }

        final List<CounterType> fixed = new ArrayList<>();
        final String[] definitions = outcome.getParam("CounterTypes").split(",");
        boolean choosesFromList = false;
        for (final String definition : definitions) {
            final String name = definition.trim();
            if ("ChosenFromList".equals(name) && !choosesFromList) {
                choosesFromList = true;
                continue;
            }
            final CounterType type = CounterType.getType(name);
            if (!supportsCounterType(type)) { return false; }
            fixed.add(type);
        }
        if (choosesFromList) {
            if (!outcome.hasParam("TypeList") || outcome.getParam("TypeList").isBlank()) {
                return false;
            }
            final List<CounterType> options = parseCounterTypesStrict(outcome.getParam("TypeList"));
            if (options == null || options.isEmpty()
                    || options.stream().anyMatch(type -> !supportsCounterType(type))) {
                return false;
            }
        } else if (outcome.hasParam("TypeList")) {
            return false;
        }
        if (fixed.isEmpty() && !choosesFromList) { return false; }

        return outcome.usesTargeting()
                ? AffectedCardResolver.supportsSingleBattlefieldTarget(outcome)
                : !outcome.hasParam("Defined") || !outcome.getParam("Defined").isBlank();
    }

    private static int evaluateCounterTypeList(final SpellAbility outcome,
            final OutcomeEvaluationContext context) {
        final int counterAmount = AbilityUtils.calculateAmount(outcome.getHostCard(),
                outcome.getParamOrDefault("CounterNum", "1"), outcome);
        if (counterAmount <= 0) { return 0; }

        final List<CounterType> fixed = new ArrayList<>();
        final boolean choosesFromList = List.of(outcome.getParam("CounterTypes").split(","))
                .stream().map(String::trim).anyMatch("ChosenFromList"::equals);
        for (final String name : outcome.getParam("CounterTypes").split(",")) {
            if (!"ChosenFromList".equals(name.trim())) {
                fixed.add(CounterType.getType(name.trim()));
            }
        }
        final List<CounterType> choices = choosesFromList
                ? parseCounterTypesStrict(outcome.getParam("TypeList")) : List.of();
        final AffectedCardResolver.Resolution resolution = outcome.getApi() == ApiType.PutCounterAll
                ? AffectedCardResolver.group(outcome, context,
                        card -> canReceiveCounterList(card, fixed, choices))
                : outcome.usesTargeting()
                        ? AffectedCardResolver.targeted(outcome, context,
                                card -> canReceiveCounterList(card, fixed, choices))
                        : AffectedCardResolver.defined(outcome, context,
                                card -> canReceiveCounterList(card, fixed, choices));
        return CardStateDeltaEvaluator.evaluate(outcome, context, resolution,
                target -> evaluateCounterTypeListTarget(outcome, target, fixed, choices,
                        counterAmount, context));
    }

    private static int evaluateCounterTypeListTarget(final SpellAbility outcome,
            final Card target, final List<CounterType> fixed, final List<CounterType> choices,
            final int amount, final OutcomeEvaluationContext context) {
        final List<CounterType> options = choices.isEmpty()
                ? java.util.Collections.singletonList(null) : choices;
        final boolean maximize = outcome.getActivatingPlayer().isOpponentOf(context.evaluatingAi());
        final long timestamp = context.timestamp(target.getGame());
        Card best = null;
        int bestValue = maximize ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        for (final CounterType choice : options) {
            final List<CounterType> types = new ArrayList<>(fixed);
            if (choice != null) { types.add(choice); }
            final Card changed = CardCopyService.getLKICopy(target);
            if (target.getZone() != null) { changed.setZone(target.getZone()); }
            for (final CounterType type : types) {
                changed.setCounters(type, EffectMath.add(changed.getCounters(type), amount));
                if (type instanceof CounterKeywordType) {
                    changed.addChangedCardKeywords(List.of(type.toString()), List.of(), false,
                            timestamp, null, false);
                }
            }
            changed.updateKeywordsCache();
            final OutcomeState branchState = context.state() == null ? null : context.state().copy();
            final int value = CardStateDeltaEvaluator.evaluateChange(
                    new OutcomeEvaluationContext(context.evaluatingAi(), context.event(), branchState),
                    target, changed);
            if (best == null || (maximize ? value > bestValue : value < bestValue)) {
                best = changed;
                bestValue = value;
            }
        }
        return best == null ? 0 : CardStateDeltaEvaluator.evaluateChange(context, target, best);
    }

    private static boolean canReceiveCounterList(final Card card,
            final List<CounterType> fixed, final List<CounterType> choices) {
        if (fixed.stream().anyMatch(type -> !supportsRecipient(card, type)
                || !card.canReceiveCounters(type))) {
            return false;
        }
        return choices.isEmpty() || choices.stream().anyMatch(type -> supportsRecipient(card, type)
                && card.canReceiveCounters(type));
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

    private static List<CounterType> parseCounterTypesStrict(final String definition) {
        final List<CounterType> types = new ArrayList<>();
        if (definition == null || definition.isBlank()) { return null; }
        for (final String name : definition.split(",")) {
            final CounterType type = CounterType.getType(name.trim());
            if (type == null) { return null; }
            types.add(type);
        }
        return types;
    }
}
