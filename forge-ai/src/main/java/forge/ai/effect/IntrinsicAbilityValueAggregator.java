package forge.ai.effect;

import java.util.List;
import java.util.function.ToDoubleFunction;

import forge.ai.effect.IntrinsicAbilityEvaluator.AbilityValue;
import forge.ai.effect.IntrinsicAbilityEvaluator.SupportStatus;

/** Combines quantity/source-profile cases without discarding unsupported probability mass. */
final class IntrinsicAbilityValueAggregator {
    private IntrinsicAbilityValueAggregator() { }

    static AbilityValue aggregate(final List<WeightedValue<AbilityValue>> cases) {
        if (cases.isEmpty() || Math.abs(cases.stream().mapToDouble(WeightedValue::weight).sum() - 1) > 1e-9) {
            throw new IllegalArgumentException("Normalized ability cases required");
        }
        final String path = cases.get(0).value().path();
        if (cases.stream().anyMatch(reference -> !path.equals(reference.value().path()))) {
            throw new IllegalArgumentException("Cases must describe the same ability");
        }
        final IntrinsicReferenceAggregate combined = new IntrinsicReferenceAggregate(
                average(cases, value -> value.contribution().value()),
                probability(cases, value -> value.contribution().completeCaseProbability()),
                probability(cases, value -> value.contribution().unavailableCaseProbability()),
                probability(cases, value -> value.contribution().partialCaseProbability()),
                probability(cases, value -> value.contribution().unsupportedCaseProbability()),
                probability(cases, value -> value.contribution().unresolvedRandomProbability()),
                cases.stream().flatMap(reference -> reference.value().contribution().unresolvedReasons().stream())
                        .distinct().toList());
        final SupportStatus firstTrigger = cases.get(0).value().triggerStatus();
        final SupportStatus triggerStatus = cases.stream().allMatch(reference ->
                reference.value().triggerStatus() == firstTrigger) ? firstTrigger : SupportStatus.PARTIAL;
        final SupportStatus firstOutcome = cases.get(0).value().outcomeStatus();
        final SupportStatus outcomeStatus = cases.stream().allMatch(reference ->
                reference.value().outcomeStatus() == firstOutcome) ? firstOutcome
                : combined.complete() && combined.unresolvedRandomProbability() == 0
                ? SupportStatus.SUPPORTED : combined.knownCaseProbability() > 0
                        || combined.partialCaseProbability() > 0 ? SupportStatus.PARTIAL : SupportStatus.UNSUPPORTED;
        return new AbilityValue(path, average(cases, AbilityValue::expectedOccurrences), combined,
                triggerStatus, outcomeStatus, average(cases, AbilityValue::currentTurnUses));
    }

    private static double average(final List<WeightedValue<AbilityValue>> cases,
            final ToDoubleFunction<AbilityValue> extract) {
        return cases.stream().mapToDouble(reference -> reference.weight() * extract.applyAsDouble(reference.value())).sum();
    }

    private static double probability(final List<WeightedValue<AbilityValue>> cases,
            final ToDoubleFunction<AbilityValue> extract) {
        return Math.min(1, average(cases, extract));
    }
}
