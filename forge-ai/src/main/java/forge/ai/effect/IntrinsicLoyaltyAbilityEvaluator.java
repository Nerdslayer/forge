package forge.ai.effect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.BiFunction;

import forge.ai.effect.CardAbilityTraversal.AbilityDescription;
import forge.ai.effect.IntrinsicAbilityEvaluator.AbilityValue;
import forge.ai.effect.IntrinsicAbilityEvaluator.SupportStatus;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;
import forge.game.card.CounterEnumType;
import forge.game.cost.Cost;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPutCounter;
import forge.game.cost.CostRemoveCounter;
import forge.game.zone.ZoneType;

/** Definition-level shared loyalty capacity with the existing probabilistic outcome backend. */
final class IntrinsicLoyaltyAbilityEvaluator {
    private IntrinsicLoyaltyAbilityEvaluator() { }
    private record PayoffKey(String path, int loyalty) { }

    static Map<String, AbilityValue> evaluate(final List<AbilityDescription> descriptions, final PermanentProfile source,
            final IntrinsicReferenceModel model, final IntrinsicEvaluationSettings settings, final EntryTiming timing,
            final BiFunction<AbilityDescription, PermanentProfile, AbilityValue> outcomes) {
        if (source.kind() != PermanentKind.PLANESWALKER) { return Map.of(); }
        final var modes = descriptions.stream().filter(ability -> ability.origin() == CardAbilityTraversal.Origin.ACTIVATION
                && "True".equals(ability.parameters().get("Planeswalker"))).toList();
        if (modes.isEmpty()) { return Map.of(); }
        final Map<String, AbilityDescription> byPath = new LinkedHashMap<>();
        final List<IntrinsicLoyaltyOpportunityPlanner.Option> options = new ArrayList<>();
        final List<String> unresolvedCosts = new ArrayList<>();
        final Map<String, AbilityValue> result = new LinkedHashMap<>();
        for (final var mode : modes) {
            final var cost = loyaltyChange(mode.parameters().get("Cost"));
            if (cost.isEmpty()) {
                unresolvedCosts.add(mode.path() + ": unsupported loyalty cost or additional resource cost");
                result.put(mode.path(), unsupported(mode.path(), unresolvedCosts.get(unresolvedCosts.size() - 1)));
                continue;
            }
            byPath.put(mode.path(), mode);
            options.add(new IntrinsicLoyaltyOpportunityPlanner.Option(mode.path(), cost.getAsInt()));
        }
        final Map<PayoffKey, AbilityValue> cache = new LinkedHashMap<>();
        final Map<String, List<AbilityValue>> evaluations = new LinkedHashMap<>();
        final var plan = IntrinsicLoyaltyOpportunityPlanner.estimate(options, source, model, settings, timing,
                (option, after, turn) -> {
                    final var mode = byPath.get(option.path());
                    if (!IntrinsicAbilityEvaluator.supportsIntrinsicActivationParameters(mode.parameters())) {
                        return new IntrinsicLoyaltyOpportunityPlanner.Payoff(0, false, false,
                                List.of(mode.path() + ": unsupported loyalty activation restrictions"));
                    }
                    if ("0".equals(mode.parameters().get("ActivationLimit"))) {
                        return new IntrinsicLoyaltyOpportunityPlanner.Payoff(0, false, true, List.of());
                    }
                    final var value = cache.computeIfAbsent(new PayoffKey(option.path(), after), key -> {
                        final var paidSource = new PermanentProfile(after > 0, source.kind(), source.controlledByAi(),
                                source.power(), source.toughness(), source.keywords(), source.basicLand(), after);
                        final var evaluated = outcomes.apply(mode, paidSource);
                        evaluations.computeIfAbsent(option.path(), path -> new ArrayList<>()).add(evaluated);
                        return evaluated;
                    });
                    final var aggregate = value.contribution();
                    // Do not use a wholly unsupported mode as a fabricated zero-value loyalty
                    // builder. Partial modes retain their supported subtotal with explicit gaps.
                    return new IntrinsicLoyaltyOpportunityPlanner.Payoff(aggregate.value(),
                            value.outcomeStatus() != SupportStatus.UNSUPPORTED && value.outcomeStatus() != SupportStatus.NOT_EVALUATED
                                    && aggregate.unavailableCaseProbability() < 1,
                            aggregate.complete() && aggregate.unresolvedRandomProbability() == 0, aggregate.unresolvedReasons());
                });
        for (final var option : options) {
            if (!plan.supported()) {
                result.put(option.path(), unsupported(option.path(), String.join("; ", plan.unresolvedReasons())));
                continue;
            }
            final var contribution = plan.contributions().getOrDefault(option.path(),
                    new IntrinsicLoyaltyOpportunityPlanner.Contribution(0, 0, 0));
            final var known = evaluations.getOrDefault(option.path(), List.of());
            final List<String> reasons = new ArrayList<>(plan.unresolvedReasons());
            reasons.addAll(unresolvedCosts);
            final boolean complete = plan.complete() && unresolvedCosts.isEmpty();
            final SupportStatus outcomeStatus = known.isEmpty() ? SupportStatus.NOT_EVALUATED
                    : known.stream().allMatch(value -> value.outcomeStatus() == SupportStatus.UNSUPPORTED) ? SupportStatus.UNSUPPORTED
                    : complete ? SupportStatus.SUPPORTED : SupportStatus.PARTIAL;
            // Coverage describes selection among the whole shared family. A zero contribution
            // may mean another mode was better, not that this mode lacks outcome support.
            final var aggregate = new IntrinsicReferenceAggregate(contribution.value(), complete ? 1 : 0, 0,
                    complete ? 0 : 1, 0, 0, reasons.stream().distinct().toList());
            result.put(option.path(), new AbilityValue(option.path(), contribution.expectedUses(), aggregate,
                    SupportStatus.SUPPORTED, outcomeStatus, contribution.currentTurnUses()));
        }
        // TODO: Activation conditions, loyalty-dependent survival, extra loyalty activations,
        // additional resources and loyalty changes caused by outcomes need explicit shared state.
        // The cost itself changes availability/source state; do not add a second independent
        // loyalty-characteristic bonus on top of base permanent evaluation and future payoffs.
        return Map.copyOf(result);
    }

    static OptionalInt loyaltyChange(final String encoded) {
        if (encoded == null || encoded.isBlank()) { return OptionalInt.empty(); }
        final Cost cost;
        try { cost = new Cost(encoded, true); }
        catch (final RuntimeException malformed) { return OptionalInt.empty(); }
        if (cost.getTotalMana().countX() > 0 || cost.getTotalMana().getCMC() != 0) { return OptionalInt.empty(); }
        int change = 0;
        boolean counter = false;
        for (final var part : cost.getCostParts()) {
            if (part instanceof CostPartMana) { continue; }
            if (counter || !"CARDNAME".equals(part.getType()) || !part.getAmount().matches("\\d+")) { return OptionalInt.empty(); }
            final int amount;
            try { amount = Integer.parseInt(part.getAmount()); }
            catch (final NumberFormatException invalid) { return OptionalInt.empty(); }
            if (amount > 1000) { return OptionalInt.empty(); }
            if (part instanceof CostPutCounter put && put.getCounter() != null && put.getCounter().is(CounterEnumType.LOYALTY)) {
                change = amount;
            } else if (part instanceof CostRemoveCounter remove && remove.counter != null && remove.counter.is(CounterEnumType.LOYALTY)
                    && !remove.oneOrMore && remove.zone.equals(List.of(ZoneType.Battlefield))) {
                change = -amount;
            } else { return OptionalInt.empty(); }
            counter = true;
        }
        return counter || "0".equals(encoded.trim()) ? OptionalInt.of(change) : OptionalInt.empty();
    }

    private static AbilityValue unsupported(final String path, final String reason) {
        return new AbilityValue(path, 0, new IntrinsicReferenceAggregate(0, 0, 0, 0, 1, 0, List.of(reason)),
                SupportStatus.UNSUPPORTED, SupportStatus.NOT_EVALUATED);
    }
}
