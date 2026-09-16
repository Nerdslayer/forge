package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Function;
import forge.card.CardStateName;
import forge.game.cost.Cost;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostTap;
import forge.game.card.CardState;
import forge.item.IPaperCard;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;
import forge.ai.effect.CardAbilityTraversal.AbilityDescription;
import forge.ai.effect.IntrinsicDrawOutcomeBackend.State;

/** Evaluates prepared definition descriptions without constructing or querying a live game. */
public final class IntrinsicAbilityEvaluator {
    private static final int MAX_REFERENCE_CASES = 4096;
    private final IntrinsicReferenceModel model;
    private final IntrinsicEvaluationSettings settings;
    public enum SupportStatus { SUPPORTED, PARTIAL, UNSUPPORTED, NOT_EVALUATED }
    public record AbilityValue(String path, double expectedOccurrences,
            IntrinsicReferenceAggregate contribution, SupportStatus triggerStatus,
            SupportStatus outcomeStatus) { }
    public record DefinitionEvaluation(List<AbilityDescription> descriptions,
            List<AbilityValue> values) {
        public DefinitionEvaluation {
            descriptions = List.copyOf(descriptions);
            values = List.copyOf(values);
        }
    }

    public IntrinsicAbilityEvaluator(final IntrinsicReferenceModel model, final IntrinsicEvaluationSettings settings) {
        this.model = model;
        this.settings = settings;
    }

    /** Game-free public entry point for a selected definition face. Results are per ability. */
    public List<AbilityValue> evaluateDefinition(final IPaperCard definition, final CardStateName face) {
        return evaluateDefinitionDetails(definition, face).values();
    }

    /**
     * Evaluates a definition while retaining the traversed descriptions used to identify each
     * result. Callers that need both should use this method so definition materialization and
     * ability traversal happen only once.
     */
    public DefinitionEvaluation evaluateDefinitionDetails(final IPaperCard definition,
            final CardStateName face) {
        final CardState state = CardAbilityTraversal.definitionState(definition, face);
        final forge.card.CardTypeView type = state.getType();
        final PermanentKind kind = type.isAura() ? PermanentKind.AURA : type.isCreature() ? PermanentKind.CREATURE
                : type.isPlaneswalker() ? PermanentKind.PLANESWALKER : type.isArtifact() ? PermanentKind.ARTIFACT
                : type.isEnchantment() ? PermanentKind.ENCHANTMENT : type.isLand() ? PermanentKind.LAND : PermanentKind.PERMANENT;
        final Set<String> keywords = state.getIntrinsicKeywords().stream().map(k -> k.getOriginal()).collect(Collectors.toSet());
        // TODO: Variable characteristic values and nonpermanent origins need dedicated models.
        if (type.isCreature() && (!state.getBasePowerString().matches("\\d+")
                || !state.getBaseToughnessString().matches("\\d+"))) {
            throw new IllegalArgumentException("Variable creature characteristics require a reference profile");
        }
        final String baseLoyalty = state.getBaseLoyalty();
        final int loyalty = type.isPlaneswalker() && baseLoyalty != null
                && baseLoyalty.matches("\\d+") ? Integer.parseInt(baseLoyalty) : 0;
        final PermanentProfile profile = new PermanentProfile(true, kind, true, Math.max(0, state.getBasePower()),
                Math.max(0, state.getBaseToughness()), keywords, type.isBasicLand(), loyalty);
        final List<AbilityDescription> descriptions = CardAbilityTraversal.inspect(state);
        final List<AbilityValue> values = evaluate(descriptions, profile,
                keywords.stream().anyMatch("Flash"::equalsIgnoreCase) ? EntryTiming.FLASH_LATE_TURN : EntryTiming.NORMAL_SPEED,
                IntrinsicTokenProfileResolver.forSource(definition));
        return new DefinitionEvaluation(descriptions, values);
    }

    /** Unsupported origins remain in results; callers must check aggregate completeness. */
    public List<AbilityValue> evaluate(final List<AbilityDescription> abilities,
            final IntrinsicReferenceModel.PermanentProfile source, final EntryTiming timing) {
        return abilities.stream().map(a -> evaluate(a, source, timing, script -> Optional.empty())).toList();
    }

    private List<AbilityValue> evaluate(final List<AbilityDescription> abilities,
            final IntrinsicReferenceModel.PermanentProfile source, final EntryTiming timing,
            final Function<String, Optional<PermanentProfile>> tokenProfileResolver) {
        return abilities.stream().map(a -> evaluate(a, source, timing, tokenProfileResolver)).toList();
    }

    private AbilityValue evaluate(final AbilityDescription ability,
            final IntrinsicReferenceModel.PermanentProfile source, final EntryTiming timing,
            final Function<String, Optional<PermanentProfile>> tokenProfileResolver) {
        final ScheduledTriggerParser.Schedule schedule = ScheduledTriggerParser.parse(ability.parameters()).orElse(null);
        if (ability.provenance() == CardAbilityTraversal.Provenance.GRANTED) {
            return unsupported(ability, "unsupported intrinsic origin", SupportStatus.UNSUPPORTED,
                    SupportStatus.NOT_EVALUATED);
        }
        if (ability.origin() == CardAbilityTraversal.Origin.STATIC) {
            return evaluateStatic(ability, source);
        }
        if (ability.origin() == CardAbilityTraversal.Origin.ACTIVATION) {
            return evaluateActivation(ability, source, timing, tokenProfileResolver);
        }
        if (ability.origin() == CardAbilityTraversal.Origin.SPELL) {
            // A normal instant or sorcery resolves once. The same backend used by triggers and
            // activations still evaluates its targets, choices, sequences and partial branches.
            // TODO: Add alternative/additional costs, X values, timing, and cast-from-zone rules.
            return evaluateOutcome(ability, withoutExecutionMetadata(ability.outcome(), 0), source,
                    1, tokenProfileResolver, SupportStatus.SUPPORTED);
        }
        // TODO: Static, replacement, conditional and non-battlefield origins need dedicated
        // reference adapters. Delayed-trigger discovery and granted abilities are also deferred.
        if (ability.origin() != CardAbilityTraversal.Origin.TRIGGER) {
            return unsupported(ability, "unsupported intrinsic origin", SupportStatus.UNSUPPORTED,
                    outcomeStatusBeforeEvaluation(ability));
        }
        if (!"Battlefield".equalsIgnoreCase(
                ability.parameters().getOrDefault("TriggerZones", "Battlefield"))
                || !supportsTriggerParameters(ability.parameters(), schedule)) {
            return unsupported(ability, "unsupported intrinsic trigger filters", SupportStatus.UNSUPPORTED,
                    outcomeStatusBeforeEvaluation(ability));
        }
        if (("Attacks".equals(ability.parameters().get("Mode"))
                || "True".equalsIgnoreCase(ability.parameters().get("Attacker")))
                && source.kind() != PermanentKind.CREATURE && source.kind() != PermanentKind.TOKEN) {
            return unsupported(ability, "self attack/tap-as-attacker requires a creature reference source",
                    SupportStatus.UNSUPPORTED, outcomeStatusBeforeEvaluation(ability));
        }
        final double occurrences;
        if (schedule != null) {
            final IntrinsicScheduledTrigger trigger = new IntrinsicScheduledTrigger(
                    IntrinsicScheduledTrigger.Schedule.valueOf(schedule.timing().name()),
                    IntrinsicScheduledTrigger.PlayerScope.valueOf(schedule.playerScope().name()));
            occurrences = IntrinsicScheduledTriggerEstimator
                    .estimate(trigger, source, model, settings, timing).expectedOccurrences();
        } else {
            final IntrinsicEventTrigger trigger = IntrinsicEventTriggerAdapter
                    .describe(ability.parameters()).orElse(null);
            if (trigger == null) {
                return unsupported(ability, "unsupported intrinsic trigger", SupportStatus.UNSUPPORTED,
                        outcomeStatusBeforeEvaluation(ability));
            }
            final IntrinsicEventTriggerEstimate estimate = IntrinsicEventTriggerEstimator
                    .estimate(trigger, source, model, settings, timing);
            if (!estimate.supported()) {
                return unsupported(ability, estimate.reason(), SupportStatus.UNSUPPORTED,
                        outcomeStatusBeforeEvaluation(ability));
            }
            occurrences = estimate.expectedOccurrences();
        }
        return evaluateOutcome(ability, ability.outcome(), source, occurrences, tokenProfileResolver,
                SupportStatus.SUPPORTED);
    }

    private AbilityValue evaluateActivation(final AbilityDescription ability,
            final PermanentProfile source, final EntryTiming timing,
            final Function<String, Optional<PermanentProfile>> tokenProfileResolver) {
        if (!supportsIntrinsicActivationParameters(ability.parameters())) {
            return unsupported(ability, "unsupported intrinsic activation restrictions",
                    SupportStatus.UNSUPPORTED, outcomeStatusBeforeEvaluation(ability));
        }
        final IntrinsicActivationCost cost = intrinsicActivationCost(ability.parameters()).orElse(null);
        if (cost == null) {
            return unsupported(ability, "unsupported intrinsic activation cost", SupportStatus.UNSUPPORTED,
                    outcomeStatusBeforeEvaluation(ability));
        }
        final IntrinsicActivationOccurrenceEstimate occurrence = IntrinsicActivationOccurrenceEstimator
                .estimate(cost.manaCost(), cost.hasTapCost(), cost.lifeCost(), source, model, settings,
                        timing);
        if (!occurrence.supported()) {
            return unsupported(ability, occurrence.reason(), SupportStatus.SUPPORTED,
                    outcomeStatusBeforeEvaluation(ability));
        }
        // Cost/AB/SP are execution metadata, not outcomes. Removing them lets the same backend
        // evaluate an activation's already-supported outcome without treating its cost as free.
        final AbilityOutcomeDescription outcome = withoutExecutionMetadata(ability.outcome(), 0);
        return evaluateOutcome(ability, outcome, source, occurrence.expectedOccurrences(),
                tokenProfileResolver, SupportStatus.SUPPORTED);
    }

    /**
     * The occurrence model currently assumes an ordinary battlefield activation. Timing, zone,
     * conditional, optional, and cost-modifying parameters need their own reference inputs.
     */
    private static boolean supportsIntrinsicActivationParameters(final Map<String, String> parameters) {
        for (final String parameter : parameters.keySet()) {
            if (parameter.startsWith("Activation") || parameter.startsWith("Condition")
                    || parameter.startsWith("Check") || Set.of("Optional", "PlayerTurn",
                            "SorcerySpeed", "PowerUp", "XMax", "ReduceCost").contains(parameter)) {
                return false;
            }
        }
        return true;
    }

    private AbilityValue evaluateStatic(final AbilityDescription ability,
            final PermanentProfile source) {
        final IntrinsicStaticAbilityEvaluator.Evaluation evaluation =
                IntrinsicStaticAbilityEvaluator.evaluate(ability, source);
        if (!evaluation.supported()) {
            return unsupported(ability, evaluation.reason(), SupportStatus.UNSUPPORTED,
                    SupportStatus.NOT_EVALUATED);
        }
        return new AbilityValue(ability.path(), 1,
                new IntrinsicReferenceAggregate(evaluation.value(), 1, 0, 0, 0, 0, List.of()),
                SupportStatus.SUPPORTED, SupportStatus.SUPPORTED);
    }

    private AbilityValue evaluateOutcome(final AbilityDescription ability,
            final AbilityOutcomeDescription outcomeDescription, final PermanentProfile source,
            final double occurrences,
            final Function<String, Optional<PermanentProfile>> tokenProfileResolver,
            final SupportStatus triggerStatus) {
        final IntrinsicDrawOutcomeBackend backend = new IntrinsicDrawOutcomeBackend(settings,
                source, tokenProfileResolver);
        if (outcomeDescription == null) {
            return unsupported(ability, "missing intrinsic outcome", triggerStatus, SupportStatus.UNSUPPORTED);
        }
        final Outcome<State> outcome;
        final List<ReferenceDimension> dimensions;
        final List<ReferenceCase> cases;
        try {
            dimensions = referenceDimensions(backend, outcomeDescription);
            outcome = new OutcomeDescriptionCompiler<>(backend).compile(outcomeDescription);
            if (referenceCaseCount(dimensions) > MAX_REFERENCE_CASES) {
                return unsupported(ability, "intrinsic reference case limit exceeded", triggerStatus,
                        SupportStatus.UNSUPPORTED);
            }
            cases = ReferenceCaseCombiner.combine(dimensions);
        } catch (final RuntimeException unsupported) {
            // A malformed or too-rich description must not turn a card definition into a
            // fabricated intrinsic value. The backend and compiler already retain safe reasons
            // for ordinary unsupported leaves; this is only the setup boundary.
            return unsupported(ability, "intrinsic reference setup failed", triggerStatus,
                    SupportStatus.UNSUPPORTED);
        }
        final IntrinsicReferenceAggregate aggregate = IntrinsicReferenceAggregator.aggregate(cases, reference -> {
            final State state = referenceState(reference, source);
            final OutcomePlan<State> plan = new OutcomePlanner<State>(settings.maximumOutcomeSearchBudget())
                    .evaluate(outcome, state);
            // Repeated uses share a per-resolution expectation, not projected later hand sizes.
            return new OutcomePlan<>(plan.value() * occurrences, plan.state(), plan.decisions(), plan.branches(),
                    plan.supported(), plan.reason(), plan.completeness(), plan.unresolvedProbability(),
                    plan.unresolvedAlternatives());
        });
        final SupportStatus outcomeStatus = aggregate.complete()
                && aggregate.unresolvedRandomProbability() == 0 ? SupportStatus.SUPPORTED
                        : aggregate.unsupportedCaseProbability() > 0 && aggregate.partialCaseProbability() == 0
                                ? SupportStatus.UNSUPPORTED : SupportStatus.PARTIAL;
        return new AbilityValue(ability.path(), occurrences, aggregate, triggerStatus, outcomeStatus);
    }

    private static Optional<IntrinsicActivationCost> intrinsicActivationCost(
            final Map<String, String> parameters) {
        final String encoded = parameters.get("Cost");
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        final Cost cost;
        try {
            cost = new Cost(encoded, true);
        } catch (final RuntimeException invalidCost) {
            return Optional.empty();
        }
        final boolean hasLifeCost = cost.getCostPartByType(CostPayLife.class) != null;
        if ((!cost.hasManaCost() && !cost.hasTapCost() && !hasLifeCost)
                || cost.getTotalMana().countX() > 0) {
            return Optional.empty();
        }
        int lifeCost = 0;
        for (final CostPart part : cost.getCostParts()) {
            if (part instanceof CostPayLife) {
                if (!part.getAmount().matches("\\d+")) {
                    return Optional.empty();
                }
                try {
                    lifeCost = Math.addExact(lifeCost, Integer.parseInt(part.getAmount()));
                } catch (final ArithmeticException | NumberFormatException invalidAmount) {
                    return Optional.empty();
                }
            } else if (!(part instanceof CostPartMana) && !(part instanceof CostTap)) {
                return Optional.empty();
            }
        }
        return Optional.of(new IntrinsicActivationCost(cost.getTotalMana().getCMC(),
                cost.hasTapCost(), lifeCost));
    }

    private static AbilityOutcomeDescription withoutExecutionMetadata(
            final AbilityOutcomeDescription node, final int depth) {
        if (node == null || depth > 24) {
            return node;
        }
        final Map<String, String> parameters = new java.util.HashMap<>(node.parameters());
        parameters.remove("Cost");
        parameters.remove("AB");
        parameters.remove("SP");
        final List<AbilityOutcomeDescription> choices = node.choices().stream()
                .map(choice -> withoutExecutionMetadata(choice, depth + 1)).toList();
        return new AbilityOutcomeDescription(node.path(), node.api(), parameters, choices,
                withoutExecutionMetadata(node.next(), depth + 1), node.issue());
    }

    private record IntrinsicActivationCost(int manaCost, boolean hasTapCost, int lifeCost) {
    }

    /**
     * Generic event recognition is broader than the reference probability model. Only retain
     * filters whose meaning is represented by the current event estimator; card/source/target
     * predicates are deliberately rejected until their reference populations are modeled.
     */
    private static boolean supportsTriggerParameters(final Map<String, String> parameters,
            final ScheduledTriggerParser.Schedule schedule) {
        if (schedule != null) {
            return true;
        }
        return IntrinsicEventTriggerAdapter.supportsIntrinsicParameters(parameters);
    }

    private static long referenceCaseCount(final List<ReferenceDimension> dimensions) {
        long count = 1;
        for (final ReferenceDimension dimension : dimensions) {
            count = Math.multiplyExact(count, dimension.distribution().entries().size());
        }
        return count;
    }

    private List<ReferenceDimension> referenceDimensions(final IntrinsicDrawOutcomeBackend backend,
            final AbilityOutcomeDescription outcome) {
        final List<ReferenceDimension> dimensions = new ArrayList<>();
        for (final String name : backend.referenceDimensions(outcome)) {
            switch (name) {
            case IntrinsicDrawOutcomeBackend.CONTROLLER_HAND,
                    IntrinsicDrawOutcomeBackend.OPPONENT_HAND -> dimensions.add(
                            new ReferenceDimension(name, model.handSizes()));
            case IntrinsicDrawOutcomeBackend.CONTROLLER_LIFE,
                    IntrinsicDrawOutcomeBackend.OPPONENT_LIFE -> dimensions.add(
                            new ReferenceDimension(name, model.lifeTotals()));
            case IntrinsicDrawOutcomeBackend.CONTROLLER_CREATURE,
                    IntrinsicDrawOutcomeBackend.OPPONENT_CREATURE -> dimensions.add(
                            new ReferenceDimension(name, model.creatureProfiles()));
            case IntrinsicDrawOutcomeBackend.CONTROLLER_PERMANENT -> dimensions.add(
                    new ReferenceDimension(name, model.permanentProfiles().map(
                            profile -> orientPermanent(profile, true))));
            case IntrinsicDrawOutcomeBackend.OPPONENT_PERMANENT -> dimensions.add(
                    new ReferenceDimension(name, model.permanentProfiles().map(
                            profile -> orientPermanent(profile, false))));
            case IntrinsicDrawOutcomeBackend.CONTROLLER_CREATURE_COUNT -> dimensions.add(
                    new ReferenceDimension(name, model.friendlyCreatureCounts()));
            case IntrinsicDrawOutcomeBackend.OPPONENT_CREATURE_COUNT -> dimensions.add(
                    new ReferenceDimension(name, model.opposingCreatureCounts()));
            default -> throw new IllegalArgumentException("Unknown intrinsic reference dimension " + name);
            }
        }
        return dimensions;
    }

    private State referenceState(final ReferenceCase reference, final PermanentProfile source) {
        return new State(integer(reference, IntrinsicDrawOutcomeBackend.CONTROLLER_HAND, 3),
                integer(reference, IntrinsicDrawOutcomeBackend.OPPONENT_HAND, 3),
                integer(reference, IntrinsicDrawOutcomeBackend.CONTROLLER_LIFE, 20),
                integer(reference, IntrinsicDrawOutcomeBackend.OPPONENT_LIFE, 20),
                3, 3,
                integer(reference, IntrinsicDrawOutcomeBackend.CONTROLLER_CREATURE_COUNT, 1),
                integer(reference, IntrinsicDrawOutcomeBackend.OPPONENT_CREATURE_COUNT, 1),
                reference.value(IntrinsicDrawOutcomeBackend.CONTROLLER_CREATURE,
                        IntrinsicReferenceModel.CreatureProfile.class),
                reference.value(IntrinsicDrawOutcomeBackend.OPPONENT_CREATURE,
                        IntrinsicReferenceModel.CreatureProfile.class),
                reference.value(IntrinsicDrawOutcomeBackend.CONTROLLER_PERMANENT,
                        IntrinsicReferenceModel.PermanentProfile.class),
                reference.value(IntrinsicDrawOutcomeBackend.OPPONENT_PERMANENT,
                        IntrinsicReferenceModel.PermanentProfile.class),
                source, null);
    }

    private static int integer(final ReferenceCase reference, final String name, final int fallback) {
        final Integer value = reference.value(name, Integer.class);
        return value == null ? fallback : value;
    }

    private static PermanentProfile orientPermanent(final PermanentProfile profile,
            final boolean controlledByAi) {
        return new PermanentProfile(profile.present(), profile.kind(), controlledByAi, profile.power(),
                profile.toughness(), profile.keywords(), profile.basicLand(), profile.loyalty());
    }

    private static AbilityValue unsupported(final AbilityDescription ability, final String reason) {
        return unsupported(ability, reason, SupportStatus.NOT_EVALUATED, SupportStatus.NOT_EVALUATED);
    }

    private static AbilityValue unsupported(final AbilityDescription ability, final String reason,
            final SupportStatus triggerStatus, final SupportStatus outcomeStatus) {
        return new AbilityValue(ability.path(), 0, new IntrinsicReferenceAggregate(0, 0, 0, 0, 1, 0,
                List.of(ability.path() + ": " + reason)), triggerStatus, outcomeStatus);
    }

    private static SupportStatus outcomeStatusBeforeEvaluation(final AbilityDescription ability) {
        return ability.outcome() == null || !ability.outcome().issue().isEmpty()
                ? SupportStatus.UNSUPPORTED : SupportStatus.NOT_EVALUATED;
    }
}
