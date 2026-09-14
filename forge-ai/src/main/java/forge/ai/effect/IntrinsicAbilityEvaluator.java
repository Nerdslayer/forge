package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Function;
import forge.card.CardStateName;
import forge.game.card.CardState;
import forge.item.IPaperCard;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;
import forge.ai.effect.CardAbilityTraversal.AbilityDescription;
import forge.ai.effect.IntrinsicDrawOutcomeBackend.State;

/** Evaluates prepared definition descriptions without constructing or querying a live game. */
public final class IntrinsicAbilityEvaluator {
    private static final Set<String> SAFE_EVENT_TRIGGER_PARAMETERS = Set.of(
            "Mode", "ValidPlayer", "ValidToken", "ValidCard", "Execute", "TriggerZones", "TriggerDescription", "Secondary");
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
        final PermanentProfile profile = new PermanentProfile(true, kind, true, Math.max(0, state.getBasePower()),
                Math.max(0, state.getBaseToughness()), keywords, type.isBasicLand());
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
        // TODO: Other origins, conditional and non-battlefield triggers, and granted abilities.
        if (ability.origin() != CardAbilityTraversal.Origin.TRIGGER
                || ability.provenance() == CardAbilityTraversal.Provenance.GRANTED) {
            return unsupported(ability, "unsupported intrinsic origin", SupportStatus.UNSUPPORTED,
                    SupportStatus.NOT_EVALUATED);
        }
        if (!"Battlefield".equalsIgnoreCase(
                ability.parameters().getOrDefault("TriggerZones", "Battlefield"))
                || !supportsTriggerParameters(ability.parameters(), schedule)) {
            return unsupported(ability, "unsupported intrinsic trigger filters", SupportStatus.UNSUPPORTED,
                    outcomeStatusBeforeEvaluation(ability));
        }
        if ("Attacks".equals(ability.parameters().get("Mode"))
                && source.kind() != PermanentKind.CREATURE && source.kind() != PermanentKind.TOKEN) {
            return unsupported(ability, "self attack requires a creature reference source",
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
        return evaluateOutcome(ability, source, occurrences, tokenProfileResolver, SupportStatus.SUPPORTED);
    }

    private AbilityValue evaluateOutcome(final AbilityDescription ability,
            final PermanentProfile source, final double occurrences,
            final Function<String, Optional<PermanentProfile>> tokenProfileResolver,
            final SupportStatus triggerStatus) {
        final IntrinsicDrawOutcomeBackend backend = new IntrinsicDrawOutcomeBackend(settings,
                source, tokenProfileResolver);
        if (ability.outcome() == null) {
            return unsupported(ability, "missing intrinsic outcome", triggerStatus, SupportStatus.UNSUPPORTED);
        }
        final Outcome<State> outcome;
        final List<ReferenceDimension> dimensions;
        final List<ReferenceCase> cases;
        try {
            dimensions = referenceDimensions(backend, ability.outcome());
            outcome = new OutcomeDescriptionCompiler<>(backend).compile(ability.outcome());
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
        if (!SAFE_EVENT_TRIGGER_PARAMETERS.containsAll(parameters.keySet())
                || parameters.get("Mode") == null) {
            return false;
        }
        final String mode = parameters.get("Mode");
        if ("TokenCreated".equals(mode)) {
            // The token reference rate describes this controller creating tokens; these filters
            // merely repeat that event's identity and introduce no narrower population.
            return "You".equals(parameters.get("ValidPlayer"))
                    && !parameters.containsKey("ValidCard")
                    && Set.of("Card", "Card.token", "Card.token+YouCtrl")
                            .contains(parameters.getOrDefault("ValidToken", "Card"));
        }
        if ("Attacks".equals(mode)) {
            return !parameters.containsKey("ValidPlayer") && !parameters.containsKey("ValidToken")
                    && Set.of("Card.Self", "Creature.Self").contains(parameters.getOrDefault("ValidCard", ""));
        }
        // TODO: Side-specific rates, event batching, thresholds and card/amount predicates need
        // validated reference adapters before admitting the other recognized event families.
        return "Drawn".equals(mode) && !parameters.containsKey("ValidToken") && !parameters.containsKey("ValidCard")
                && "Player".equals(parameters.getOrDefault("ValidPlayer", "Player"));
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
                profile.toughness(), profile.keywords(), profile.basicLand());
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
