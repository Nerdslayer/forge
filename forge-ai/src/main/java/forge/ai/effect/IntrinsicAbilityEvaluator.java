package forge.ai.effect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Function;
import forge.card.CardStateName;
import forge.card.ICardFace;
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
    // Allows the complete 199-profile paired-creature sample while bounding richer Cartesian cases.
    private static final int MAX_GENERATED_CREATURE_CASES = 50_000;
    private static final int MAX_CACHED_DEFINITIONS = 256;
    private final IntrinsicReferenceModel model;
    private final IntrinsicEvaluationSettings settings;
    private final IntrinsicWatchedCreatureBinding watchedCreatureBinding;
    /**
     * Definition ability evaluation is independent of the live game state, so retain its first
     * result for repeated card valuations. Keep this bounded because card-creator previews can
     * generate a fresh definition after every edit.
     */
    private final Map<DefinitionCacheKey, DefinitionEvaluation> definitionCache =
            new LinkedHashMap<>(32, .75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(final Map.Entry<DefinitionCacheKey,
                        DefinitionEvaluation> eldest) {
                    return size() > MAX_CACHED_DEFINITIONS;
                }
            };

    private record DefinitionCacheKey(String name, String edition, String functionalVariant,
            String splitType, CardStateName face, List<FaceCacheKey> faces) {
    }

    /** Structural snapshot prevents stale hits if a custom card's CardRules is reinitialized. */
    private record FaceCacheKey(String name, String type, String manaCost, String color,
            String power, String toughness, String loyalty, String defense, String oracleText,
            List<String> keywords, List<String> deckRules, List<String> abilities,
            List<String> staticAbilities, List<String> triggers, List<String> replacements,
            List<String> draftActions, List<String> variables, String nonAbilityText) {
    }
    public enum SupportStatus { SUPPORTED, PARTIAL, UNSUPPORTED, NOT_EVALUATED }
    public record AbilityValue(String path, double expectedOccurrences,
            IntrinsicReferenceAggregate contribution, SupportStatus triggerStatus,
            SupportStatus outcomeStatus, double currentTurnUses) {
        public AbilityValue(final String path, final double expectedOccurrences,
                final IntrinsicReferenceAggregate contribution, final SupportStatus triggerStatus,
                final SupportStatus outcomeStatus) {
            this(path, expectedOccurrences, contribution, triggerStatus, outcomeStatus, 0);
        }
    }
    public record DefinitionEvaluation(List<AbilityDescription> descriptions,
            List<AbilityValue> values) {
        public DefinitionEvaluation {
            descriptions = List.copyOf(descriptions);
            values = List.copyOf(values);
        }
    }

    public IntrinsicAbilityEvaluator(final IntrinsicReferenceModel model, final IntrinsicEvaluationSettings settings) {
        this(model, settings, null);
    }

    private IntrinsicAbilityEvaluator(final IntrinsicReferenceModel model, final IntrinsicEvaluationSettings settings,
            final IntrinsicWatchedCreatureBinding watchedCreatureBinding) {
        this.model = model;
        this.settings = settings;
        this.watchedCreatureBinding = watchedCreatureBinding;
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
        final DefinitionCacheKey cacheKey = cacheKey(definition, face);
        synchronized (definitionCache) {
            final DefinitionEvaluation cached = definitionCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        final DefinitionEvaluation result = evaluateDefinitionUncached(definition, face);
        synchronized (definitionCache) {
            final DefinitionEvaluation existing = definitionCache.get(cacheKey);
            if (existing != null) {
                return existing;
            }
            definitionCache.put(cacheKey, result);
        }
        return result;
    }

    private DefinitionEvaluation evaluateDefinitionUncached(final IPaperCard definition,
            final CardStateName face) {
        final CardState state = CardAbilityTraversal.definitionState(definition, face);
        final forge.card.CardTypeView type = IntrinsicSourceProfileResolver.definitionTypeFacts(state);
        final PermanentKind kind = type.isAura() ? PermanentKind.AURA : type.isCreature() ? PermanentKind.CREATURE
                : type.isPlaneswalker() ? PermanentKind.PLANESWALKER : type.isArtifact() ? PermanentKind.ARTIFACT
                : type.isEnchantment() ? PermanentKind.ENCHANTMENT : type.isLand() ? PermanentKind.LAND : PermanentKind.PERMANENT;
        final Set<String> keywords = state.getIntrinsicKeywords().stream().map(k -> k.getOriginal()).collect(Collectors.toSet());
        final String baseLoyalty = state.getBaseLoyalty();
        final int loyalty = type.isPlaneswalker() && baseLoyalty != null
                && baseLoyalty.matches("\\d+") ? Integer.parseInt(baseLoyalty) : 0;
        final PermanentProfile profile = new PermanentProfile(true, kind, true, Math.max(0, state.getBasePower()),
                Math.max(0, state.getBaseToughness()), keywords, type.isBasicLand(), loyalty);
        final List<AbilityDescription> descriptions = CardAbilityTraversal.inspect(state);
        final var profiles = IntrinsicSourceProfileResolver.resolveCases(state, profile, model)
                .orElse(null);
        if (profiles == null) {
            return new DefinitionEvaluation(descriptions, descriptions.stream().map(ability -> unsupported(ability,
                    "Variable creature characteristics require a supported reference quantity",
                    SupportStatus.UNSUPPORTED, SupportStatus.NOT_EVALUATED)).toList());
        }
        final EntryTiming timing = keywords.stream().anyMatch("Flash"::equalsIgnoreCase)
                ? EntryTiming.FLASH_LATE_TURN : EntryTiming.NORMAL_SPEED;
        final Function<String, Optional<PermanentProfile>> tokenResolver = IntrinsicTokenProfileResolver.forSource(definition, model, settings);
        final List<AbilityValue> values = descriptions.stream().map(original -> {
            final AbilityDescription ability = IntrinsicGroupCombatDamageAdapter.prepare(
                    IntrinsicCreatureEntryTriggerAdapter.excludeNonmatchingSource(
                            IntrinsicCreatureDeathTriggerAdapter.excludeNonmatchingSource(original, type), type), type);
            if (ability.origin() == CardAbilityTraversal.Origin.STATIC
                    && type.isCreature() && IntrinsicSourceProfileResolver.ownsCharacteristic(ability.parameters())) {
                // The body owns these characteristics. The resolved profiles affect survival and
                // ability outcomes; do not also award a second characteristic ability bonus.
                return new AbilityValue(ability.path(), 1,
                        new IntrinsicReferenceAggregate(0, 1, 0, 0, 0, 0, List.of()),
                        SupportStatus.SUPPORTED, SupportStatus.SUPPORTED);
            }
            if (profiles.size() == 1) {
                return evaluateDefinitionAbility(ability, profiles.get(0).value().profile(), timing, tokenResolver,
                        state.getSVars(), profiles.get(0).value().quantities());
            }
            return IntrinsicAbilityValueAggregator.aggregate(profiles.stream().map(reference ->
                    new WeightedValue<>(evaluateDefinitionAbility(ability, reference.value().profile(), timing,
                            tokenResolver, state.getSVars(), reference.value().quantities()), reference.weight())).toList());
        }).toList();
        final var loyaltyCases = profiles.stream().map(reference -> new WeightedValue<>(IntrinsicLoyaltyAbilityEvaluator.evaluate(
                descriptions, reference.value().profile(), model, settings, timing, (ability, paidSource) -> {
                    final var parameters = new LinkedHashMap<>(ability.parameters());
                    for (final String metadata : Set.of("Cost", "AB", "Planeswalker", "Ultimate", "SorcerySpeed", "PlayerTurn", "ActivationLimit")) {
                        parameters.remove(metadata);
                    }
                    // One resolving activation, after payment; the shared planner owns occurrence
                    // and cost legality. All ordinary outcome/quantity/target adapters are reused.
                    return evaluateDefinitionAbility(new AbilityDescription(ability.path(), CardAbilityTraversal.Origin.SPELL,
                            ability.provenance(), parameters, withoutExecutionMetadata(ability.outcome(), 0)), paidSource, timing,
                            tokenResolver, state.getSVars(), reference.value().quantities());
                }), reference.weight())).toList();
        final List<AbilityValue> sharedValues = values.stream().map(value -> {
            if (loyaltyCases.stream().anyMatch(reference -> !reference.value().containsKey(value.path()))) { return value; }
            return IntrinsicAbilityValueAggregator.aggregate(loyaltyCases.stream().map(reference ->
                    new WeightedValue<>(reference.value().get(value.path()), reference.weight())).toList());
        }).toList();
        return new DefinitionEvaluation(descriptions, sharedValues);
    }

    private AbilityValue evaluateDefinitionAbility(final AbilityDescription ability, final PermanentProfile profile,
            final EntryTiming timing, final Function<String, Optional<PermanentProfile>> tokenResolver,
            final Map<String, String> variables, final Map<String, Integer> existingQuantities) {
        final var characteristicFilter = IntrinsicEventCharacteristicFilter.normalize(ability, variables, model, profile);
        if (characteristicFilter != ability) {
            return evaluateDefinitionAbility(characteristicFilter, profile, timing, tokenResolver, variables, existingQuantities);
        }
        final var recipientCases = IntrinsicTriggerBindingNormalizer.recipientCases(ability, model).orElse(null);
        if (recipientCases != null) {
            return IntrinsicAbilityValueAggregator.aggregate(recipientCases.stream().map(reference -> new WeightedValue<>(
                    evaluateDefinitionAbility(reference.value(), profile, timing, tokenResolver, variables, existingQuantities),
                    reference.weight())).toList());
        }
        final var playerComponent = IntrinsicOutgoingCombatDamageBinding.playerComponent(ability).orElse(null);
        if (playerComponent != null) {
            final var value = evaluateDefinitionAbility(playerComponent, profile, timing, tokenResolver, variables, existingQuantities);
            final var aggregate = value.contribution();
            final var reasons = new ArrayList<>(aggregate.unresolvedReasons());
            reasons.add(ability.path() + ": battle combat-hit opportunities are not modeled; value is the player-hit subtotal");
            return new AbilityValue(value.path(), value.expectedOccurrences(), new IntrinsicReferenceAggregate(aggregate.value(),
                    0, 0, Math.min(1, aggregate.knownCaseProbability() + aggregate.partialCaseProbability()),
                    aggregate.unsupportedCaseProbability(), aggregate.unresolvedRandomProbability(), reasons),
                    SupportStatus.PARTIAL, value.outcomeStatus(), value.currentTurnUses());
        }
        final var attackerCases = IntrinsicAttackCountBinding.cases(ability, variables, model).orElse(null);
        if (attackerCases != null) {
            if (attackerCases.isEmpty()) {
                return new AbilityValue(ability.path(), 0, new IntrinsicReferenceAggregate(0, 0, 1, 0, 0, 0, List.of()),
                        SupportStatus.SUPPORTED, SupportStatus.SUPPORTED);
            }
            return IntrinsicAbilityValueAggregator.aggregate(attackerCases.stream().map(reference -> new WeightedValue<>(
                    evaluateDefinitionAbility(new AbilityDescription(ability.path(), ability.origin(), ability.provenance(),
                            reference.value().bindVariables(ability.parameters()), reference.value().bindOutcome(ability.outcome())),
                            profile, timing, tokenResolver, reference.value().bindVariables(variables), existingQuantities), reference.weight())).toList());
        }
        final var outgoingCases = IntrinsicOutgoingCombatDamageBinding.cases(ability, variables, profile).orElse(null);
        if (outgoingCases != null) {
            if (outgoingCases.isEmpty()) {
                return new AbilityValue(ability.path(), 0, new IntrinsicReferenceAggregate(0, 0, 1, 0, 0, 0, List.of()),
                        SupportStatus.SUPPORTED, SupportStatus.SUPPORTED);
            }
            return IntrinsicAbilityValueAggregator.aggregate(outgoingCases.stream().map(reference -> new WeightedValue<>(
                    evaluateDefinitionAbility(new AbilityDescription(ability.path(), ability.origin(), ability.provenance(),
                            reference.value().bindVariables(ability.parameters()), reference.value().bindOutcome(ability.outcome())),
                            profile, timing, tokenResolver, reference.value().bindVariables(variables), existingQuantities), reference.weight())).toList());
        }
        final var damageCases = IntrinsicReceivedDamageBinding.cases(ability, variables, model).orElse(null);
        if (damageCases != null) {
            if (damageCases.isEmpty()) {
                return new AbilityValue(ability.path(), 0, new IntrinsicReferenceAggregate(0, 0, 1, 0, 0, 0, List.of()),
                        SupportStatus.SUPPORTED, SupportStatus.SUPPORTED);
            }
            return IntrinsicAbilityValueAggregator.aggregate(damageCases.stream().map(reference -> new WeightedValue<>(
                    evaluateDefinitionAbility(new AbilityDescription(ability.path(), ability.origin(), ability.provenance(),
                            reference.value().bindVariables(ability.parameters()), reference.value().bindOutcome(ability.outcome())), profile, timing,
                            tokenResolver, reference.value().bindVariables(variables), existingQuantities), reference.weight())).toList());
        }
        if (watchedCreatureBinding == null && IntrinsicWatchedCreatureBinding.needed(ability, variables)) {
            final var entries = IntrinsicWatchedCreatureBinding.cases(ability, model, profile).orElse(null);
            if (entries != null) {
                if (entries.isEmpty()) {
                    return new AbilityValue(ability.path(), 0,
                            new IntrinsicReferenceAggregate(0, 0, 1, 0, 0, 0, List.of()),
                            SupportStatus.SUPPORTED, SupportStatus.SUPPORTED);
                }
                return IntrinsicAbilityValueAggregator.aggregate(entries.stream().map(entry -> new WeightedValue<>(
                        new IntrinsicAbilityEvaluator(model, settings, entry.value()).evaluateDefinitionAbility(
                                ability, profile, timing, tokenResolver, variables, existingQuantities), entry.weight())).toList());
            }
        }
        final AbilityDescription normalized = IntrinsicTriggerBindingNormalizer.normalize(ability);
        if (watchedCreatureBinding != null
                && IntrinsicWatchedCreatureBinding.unresolvedMutableCharacteristics(normalized.outcome(), variables)) {
            return unsupported(ability, "Watched-object characteristic read after projected modification",
                    SupportStatus.NOT_EVALUATED, SupportStatus.UNSUPPORTED);
        }
        final boolean selfDeath = normalized.origin() == CardAbilityTraversal.Origin.TRIGGER
                && IntrinsicSelfDeathTriggerAdapter.bindableSourceEvent(AbilityOptionality.triggerParameters(normalized.parameters()));
        final Map<String, String> boundVariables = watchedCreatureBinding != null ? watchedCreatureBinding.bindVariables(variables)
                : selfDeath ? IntrinsicSelfDeathTriggerAdapter.bindSourceQuantities(variables) : variables;
        final boolean staticAbility = normalized.origin() == CardAbilityTraversal.Origin.STATIC;
        final boolean conditionalOrigin = staticAbility || normalized.origin() == CardAbilityTraversal.Origin.TRIGGER
                || normalized.origin() == CardAbilityTraversal.Origin.ACTIVATION;
        // Bind root conditions and executed outcomes in one tree, so a condition testing X and
        // an outcome using X share a sample rather than multiplying independent expectations.
        final AbilityOutcomeDescription quantityDescription = new AbilityOutcomeDescription(normalized.path(), "IntrinsicRoot",
                conditionalOrigin ? IntrinsicAbilityConditions.prepare(normalized.parameters()) : normalized.parameters(),
                List.of(), staticAbility ? null : IntrinsicRepeatedOutcomeNormalizer.normalize(watchedCreatureBinding == null
                        ? normalized.outcome() : watchedCreatureBinding.bindOutcome(normalized.outcome())), "");
        final var variants = IntrinsicOutcomeQuantityBinder.bindCases(
                quantityDescription, boundVariables, model, profile, existingQuantities);
        if (variants.size() == 1) {
            return evaluateDefinitionCase(normalized, variants.get(0).value(), profile, timing, tokenResolver, boundVariables);
        }
        return IntrinsicAbilityValueAggregator.aggregate(variants.stream().map(variant -> new WeightedValue<>(
                evaluateDefinitionCase(normalized, variant.value(), profile, timing, tokenResolver, boundVariables), variant.weight())).toList());
    }

    private AbilityValue evaluateDefinitionCase(final AbilityDescription description, final IntrinsicOutcomeQuantityBinder.BoundCase reference,
            final PermanentProfile source, final EntryTiming timing, final Function<String, Optional<PermanentProfile>> tokenResolver,
            final Map<String, String> variables) {
        final AbilityOutcomeDescription bound = reference.outcome();
        final boolean staticAbility = description.origin() == CardAbilityTraversal.Origin.STATIC;
        final boolean activation = description.origin() == CardAbilityTraversal.Origin.ACTIVATION;
        final boolean conditionalOrigin = staticAbility || description.origin() == CardAbilityTraversal.Origin.TRIGGER || activation;
        final IntrinsicAbilityConditions.Result condition = conditionalOrigin
                ? IntrinsicAbilityConditions.resolve(bound.parameters()) : new IntrinsicAbilityConditions.Result(bound.parameters(), false);
        if (condition.inactive()) {
            return new AbilityValue(description.path(), 0, new IntrinsicReferenceAggregate(0, 0, 1, 0, 0, 0, List.of()),
                    SupportStatus.SUPPORTED, SupportStatus.SUPPORTED);
        }
        final IntrinsicReferenceModel conditioned = model.withQuantityBindings(reference.quantities());
        final IntrinsicAbilityEvaluator evaluator = conditioned == model ? this : new IntrinsicAbilityEvaluator(conditioned, settings, watchedCreatureBinding);
        final AbilityOutcomeDescription outcome = activation
                ? withoutEvaluatedActivationConditions(bound.next(), description.parameters(), condition.parameters()) : bound.next();
        final var evaluated = new AbilityDescription(description.path(), description.origin(), description.provenance(),
                condition.parameters(), staticAbility ? description.outcome() : outcome);
        // Choose damage donors after population/condition quantities have been bound. The host
        // mixture must not be averaged from a different board population than occurrence uses.
        final var amounts = IntrinsicGroupCombatDamageAdapter.amountCases(evaluated, variables, conditioned, source).orElse(null);
        if (amounts != null) {
            if (amounts.isEmpty()) {
                return new AbilityValue(description.path(), 0, new IntrinsicReferenceAggregate(0, 0, 1, 0, 0, 0, List.of()),
                        SupportStatus.SUPPORTED, SupportStatus.SUPPORTED);
            }
            return IntrinsicAbilityValueAggregator.aggregate(amounts.stream().map(amount -> new WeightedValue<>(
                    evaluator.evaluateDefinitionAbility(new AbilityDescription(evaluated.path(), evaluated.origin(), evaluated.provenance(),
                            amount.value().bindVariables(evaluated.parameters()), amount.value().bindOutcome(evaluated.outcome())),
                            source, timing, tokenResolver, amount.value().bindVariables(variables), reference.quantities()), amount.weight())).toList());
        }
        return evaluator.evaluate(evaluated, source, timing, tokenResolver);
    }

    private static AbilityOutcomeDescription withoutEvaluatedActivationConditions(final AbilityOutcomeDescription outcome,
            final Map<String, String> original, final Map<String, String> remaining) {
        if (outcome == null) { return null; }
        final Map<String, String> parameters = new java.util.LinkedHashMap<>(outcome.parameters());
        for (final String field : Set.of("CheckSVar", "SVarCompare", "IsPresent", "PresentCompare",
                "PresentDefined", "PresentPlayer", "PresentZone", "LifeTotal", "LifeAmount")) {
            if (original.containsKey(field) && !remaining.containsKey(field)) { parameters.remove(field); }
        }
        // Only the root activation restriction was evaluated. Conditions on following effects
        // retain their resolution-time meaning and must not be stripped from the whole chain.
        return new AbilityOutcomeDescription(outcome.path(), outcome.api(), parameters,
                outcome.choices(), outcome.next(), outcome.issue());
    }

    private static DefinitionCacheKey cacheKey(final IPaperCard definition,
            final CardStateName face) {
        final List<FaceCacheKey> faces = definition.getAllFaces().stream()
                .map(IntrinsicAbilityEvaluator::faceCacheKey).toList();
        return new DefinitionCacheKey(definition.getName(), definition.getEdition(),
                definition.getFunctionalVariant(), definition.getRules().getSplitType().name(),
                face, faces);
    }

    private static FaceCacheKey faceCacheKey(final ICardFace face) {
        final List<String> variables = new ArrayList<>();
        face.getVariables().forEach(entry -> variables.add(entry.getKey() + "=" + entry.getValue()));
        variables.sort(String::compareTo);
        return new FaceCacheKey(face.getName(), face.getType().toString(),
                face.getManaCost().toString(), String.valueOf(face.getColor()), face.getPower(),
                face.getToughness(), face.getInitialLoyalty(), face.getDefense(), face.getOracleText(),
                iterableSnapshot(face.getKeywords()), iterableSnapshot(face.getDeckRules()),
                iterableSnapshot(face.getAbilities()), iterableSnapshot(face.getStaticAbilities()),
                iterableSnapshot(face.getTriggers()), iterableSnapshot(face.getReplacements()),
                iterableSnapshot(face.getDraftActions()), List.copyOf(variables),
                face.getNonAbilityText());
    }

    private static List<String> iterableSnapshot(final Iterable<String> values) {
        final List<String> result = new ArrayList<>();
        if (values != null) {
            values.forEach(result::add);
        }
        return List.copyOf(result);
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
        final Map<String, String> triggerParameters = AbilityOptionality.triggerParameters(ability.parameters());
        final ScheduledTriggerParser.Schedule schedule = ScheduledTriggerParser.parse(triggerParameters).orElse(null);
        if (ability.provenance() == CardAbilityTraversal.Provenance.GRANTED) {
            return unsupported(ability, "unsupported intrinsic origin", SupportStatus.UNSUPPORTED,
                    SupportStatus.NOT_EVALUATED);
        }
        if (ability.origin() == CardAbilityTraversal.Origin.STATIC) {
            return evaluateStatic(ability, source, model);
        }
        if (ability.origin() == CardAbilityTraversal.Origin.ACTIVATION) {
            return evaluateActivation(ability, source, timing, tokenProfileResolver);
        }
        if (ability.origin() == CardAbilityTraversal.Origin.SPELL) {
            // A normal instant or sorcery resolves once. The same backend used by triggers and
            // activations still evaluates its targets, choices, sequences and partial branches.
            // TODO: Add alternative/additional costs, X values, timing, and cast-from-zone rules.
            return evaluateOutcome(ability, withoutExecutionMetadata(ability.outcome(), 0), source,
                    1, tokenProfileResolver, SupportStatus.SUPPORTED, false);
        }
        // TODO: Static, replacement, conditional and non-battlefield origins need dedicated
        // reference adapters. Delayed-trigger discovery and granted abilities are also deferred.
        if (ability.origin() != CardAbilityTraversal.Origin.TRIGGER) {
            return unsupported(ability, "unsupported intrinsic origin", SupportStatus.UNSUPPORTED,
                    outcomeStatusBeforeEvaluation(ability));
        }
        final AbilityOptionality.Decision optionality = AbilityOptionality.trigger(ability.parameters());
        if (!optionality.supported()) {
            return unsupported(ability, optionality.issue(), SupportStatus.UNSUPPORTED,
                    outcomeStatusBeforeEvaluation(ability));
        }
        if (IntrinsicSelfDeathTriggerAdapter.supports(triggerParameters)) {
            if (source.kind() != PermanentKind.CREATURE && source.kind() != PermanentKind.TOKEN) {
                return unsupported(ability, "Self death requires a creature reference source",
                        SupportStatus.UNSUPPORTED, outcomeStatusBeforeEvaluation(ability));
            }
            final double deathOccurrences = new PermanentSurvivalEstimator(model).expectedSelfDeathOccurrences(
                    source, timing, settings.recurringTriggerResolutions() * 2);
            return evaluateOutcome(ability, ability.outcome(), source, deathOccurrences, tokenProfileResolver,
                    SupportStatus.SUPPORTED, false);
        }
        if (IntrinsicSelfEntryTriggerAdapter.supports(triggerParameters)) {
            // The definition is valued at deployment: its own unconditional ETB happens once,
            // without future survival or event-rate discounts. Battlefield callers must exclude
            // this already-realized benefit rather than turning it into a future allowance.
            return evaluateOutcome(ability, ability.outcome(), source, 1, tokenProfileResolver,
                    SupportStatus.SUPPORTED, false);
        }
        if (!"Battlefield".equalsIgnoreCase(
                ability.parameters().getOrDefault("TriggerZones", "Battlefield"))
                || !supportsTriggerParameters(triggerParameters, schedule, source, model)) {
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
                    .describe(triggerParameters, model, source).orElse(null);
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
                SupportStatus.SUPPORTED, false);
    }

    private AbilityValue evaluateActivation(final AbilityDescription ability,
            final PermanentProfile source, final EntryTiming timing,
            final Function<String, Optional<PermanentProfile>> tokenProfileResolver) {
        final AbilityOutcomeDescription activationParameters = new AbilityOutcomeDescription(
                ability.path(), ability.outcome().api(), ability.parameters(), List.of(), null, "");
        if (!AbilityOptionality.effect(activationParameters).supported()
                || !supportsIntrinsicActivationParameters(
                        AbilityOptionality.effectParameters(activationParameters).parameters())) {
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
                        timing, "True".equalsIgnoreCase(ability.parameters().get("SorcerySpeed"))
                                || "True".equals(ability.parameters().get("PlayerTurn")),
                        intrinsicActivationLimit(ability.parameters()));
        if (!occurrence.supported()) {
            return unsupported(ability, occurrence.reason(), SupportStatus.SUPPORTED,
                    outcomeStatusBeforeEvaluation(ability));
        }
        // Cost/AB/SP are execution metadata, not outcomes. Removing them lets the same backend
        // evaluate an activation's already-supported outcome without treating its cost as free.
        final AbilityOutcomeDescription outcome = withoutExecutionMetadata(ability.outcome(), 0);
        final AbilityValue evaluated = evaluateOutcome(ability, outcome, source,
                occurrence.expectedOccurrences(), tokenProfileResolver, SupportStatus.SUPPORTED, true);
        return new AbilityValue(evaluated.path(), evaluated.expectedOccurrences(),
                evaluated.contribution(), evaluated.triggerStatus(), evaluated.outcomeStatus(),
                occurrence.currentTurnUses());
    }

    /**
     * The occurrence model assumes a battlefield activation. Supported definition root conditions
     * are removed only after binding; unresolved timing, zone, history and cost-modifying rules
     * remain rejected. PlayerTurn and SorcerySpeed share the controller-turn resource window.
     */
    static boolean supportsIntrinsicActivationParameters(final Map<String, String> parameters) {
        // TODO: Opponent-turn/phase-specific windows, ActivationLifeTotal, richer conditions,
        // alternative activators, zones, shared/game limits and cost modifiers need explicit models.
        for (final String parameter : parameters.keySet()) {
            if ("PlayerTurn".equals(parameter)) {
                if (!"True".equals(parameters.get(parameter))) { return false; }
                continue;
            }
            if ("ActivationLimit".equals(parameter)) {
                if (intrinsicActivationLimit(parameters) < 0) { return false; }
                continue;
            }
            if ("SorcerySpeed".equals(parameter)) {
                if (!Set.of("True", "False").contains(parameters.get(parameter))) { return false; }
                continue;
            }
            if (parameter.startsWith("Activation") || parameter.startsWith("Condition")
                    || parameter.startsWith("Check") || Set.of("Optional", "OpponentTurn", "IsPresent", "PresentCompare",
                            "PresentDefined", "PresentPlayer", "PresentZone", "PowerUp", "XMax", "ReduceCost",
                            "GameActivationLimit").contains(parameter)) {
                return false;
            }
        }
        return true;
    }

    private static int intrinsicActivationLimit(final Map<String, String> parameters) {
        if (!parameters.containsKey("ActivationLimit")) { return Integer.MAX_VALUE; }
        final String encoded = parameters.get("ActivationLimit");
        if (encoded == null || !encoded.matches("\\d+")) { return -1; }
        try { return Integer.parseInt(encoded); }
        catch (final NumberFormatException invalid) { return -1; }
    }

    private AbilityValue evaluateStatic(final AbilityDescription ability,
            final PermanentProfile source, final IntrinsicReferenceModel referenceModel) {
        final IntrinsicStaticAbilityEvaluator.Evaluation evaluation =
                IntrinsicStaticAbilityEvaluator.evaluate(ability, source, referenceModel);
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
            final SupportStatus triggerStatus, final boolean canDecline) {
        final IntrinsicDrawOutcomeBackend backend = new IntrinsicDrawOutcomeBackend(settings,
                source, tokenProfileResolver, model.library(), watchedCreatureBinding == null ? null : watchedCreatureBinding.creature(),
                model.referenceIntegers("CAST_SPELL_MANA_VALUE",
                        model.quantities().distribution(IntrinsicReferenceQuantities.Quantity.CAST_SPELL_MANA_VALUE)));
        if (outcomeDescription == null) {
            return unsupported(ability, "missing intrinsic outcome", triggerStatus, SupportStatus.UNSUPPORTED);
        }
        final Outcome<State> outcome;
        final List<ReferenceDimension> dimensions;
        final List<ReferenceCase> cases;
        try {
            dimensions = referenceDimensions(backend, outcomeDescription);
            final Outcome<State> compiled = new OutcomeDescriptionCompiler<>(backend).compile(outcomeDescription);
            final AbilityOptionality.Decision optionality = ability.origin() == CardAbilityTraversal.Origin.TRIGGER
                    ? AbilityOptionality.trigger(ability.parameters())
                    : new AbilityOptionality.Decision(false, false, "");
            outcome = canDecline || optionality.optional()
                    ? OutcomeChoices.optional("optional:" + ability.path(), compiled, !optionality.opponent())
                    : compiled;
            List<ReferenceDimension> boundedDimensions = dimensions;
            final boolean hasCreatureDimension = dimensions.stream().anyMatch(dimension ->
                    IntrinsicDrawOutcomeBackend.CONTROLLER_CREATURE.equals(dimension.name())
                            || IntrinsicDrawOutcomeBackend.OPPONENT_CREATURE.equals(dimension.name()));
            final int caseLimit = model.usesGeneratedCreatureProfiles() && hasCreatureDimension
                    ? MAX_GENERATED_CREATURE_CASES : MAX_REFERENCE_CASES;
            if (caseLimit > MAX_REFERENCE_CASES && referenceCaseCount(boundedDimensions) > caseLimit) {
                // TODO: Add a bounded high-resolution aggregation for multiple creature references.
                // Until then, preserve the prior supported-outcome coverage with the coarse sample.
                boundedDimensions = useCoarseCreatureProfiles(boundedDimensions);
            }
            if (referenceCaseCount(boundedDimensions) > caseLimit) {
                return unsupported(ability, "intrinsic reference case limit exceeded", triggerStatus,
                        SupportStatus.UNSUPPORTED);
            }
            cases = ReferenceCaseCombiner.combine(boundedDimensions);
        } catch (final RuntimeException unsupported) {
            // A malformed or too-rich description must not turn a card definition into a
            // fabricated intrinsic value. The backend and compiler already retain safe reasons
            // for ordinary unsupported leaves; this is only the setup boundary.
            return unsupported(ability, "intrinsic reference setup failed", triggerStatus,
                    SupportStatus.UNSUPPORTED);
        }
        final IntrinsicReferenceAggregate aggregate = IntrinsicReferenceAggregator.aggregate(cases, reference -> {
            final boolean selfDeath = ability.origin() == CardAbilityTraversal.Origin.TRIGGER
                    && IntrinsicSelfDeathTriggerAdapter.supports(AbilityOptionality.triggerParameters(ability.parameters()));
            final PermanentProfile battlefieldSource = selfDeath
                    ? new PermanentProfile(false, source.kind(), source.controlledByAi(), source.power(),
                            source.toughness(), source.keywords(), source.basicLand(), source.loyalty()) : source;
            // The departing source is no longer a legal battlefield recipient, but its LKI
            // remains available to the supported source-damage and bound quantity evaluators.
            State state = referenceState(reference, battlefieldSource);
            if (watchedCreatureBinding != null) {
                final PermanentProfile watched = watchedCreatureBinding.creature();
                state = state.withWatchedCreature(new PermanentProfile(watchedCreatureBinding.battlefieldRecipient(),
                        watched.kind(), watched.controlledByAi(), watched.power(), watched.toughness(), watched.keywords()));
            }
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
        if (parameters.containsKey("AB")) {
            // The root activation occurrence estimator enforced this limit. Do not strip limits
            // from nested standalone DB effects that have no modeled activation opportunity.
            parameters.remove("ActivationLimit");
            parameters.remove("PlayerTurn");
            parameters.remove("Planeswalker");
            parameters.remove("Ultimate");
        }
        parameters.remove("Cost");
        parameters.remove("AB");
        parameters.remove("SP");
        parameters.remove("SorcerySpeed"); // Enforced by intrinsic activation occurrence, not an outcome.
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
            final ScheduledTriggerParser.Schedule schedule, final PermanentProfile source,
            final IntrinsicReferenceModel model) {
        if (schedule != null) {
            return true;
        }
        return IntrinsicCreatureEntryTriggerAdapter.describe(parameters, model, source).isPresent()
                || IntrinsicEventTriggerAdapter.supportsIntrinsicParameters(parameters);
    }

    private static long referenceCaseCount(final List<ReferenceDimension> dimensions) {
        long count = 1;
        for (final ReferenceDimension dimension : dimensions) {
            count = Math.multiplyExact(count, dimension.distribution().entries().size());
        }
        return count;
    }

    private static List<ReferenceDimension> useCoarseCreatureProfiles(
            final List<ReferenceDimension> dimensions) {
        return dimensions.stream().map(dimension -> {
            if (IntrinsicDrawOutcomeBackend.CONTROLLER_CREATURE.equals(dimension.name())
                    || IntrinsicDrawOutcomeBackend.OPPONENT_CREATURE.equals(dimension.name())) {
                return new ReferenceDimension(dimension.name(),
                        CreatureReferenceDistribution.coarseProfiles());
            }
            return dimension;
        }).toList();
    }

    private List<ReferenceDimension> referenceDimensions(final IntrinsicDrawOutcomeBackend backend,
            final AbilityOutcomeDescription outcome) {
        final List<ReferenceDimension> dimensions = new ArrayList<>();
        for (final String name : backend.referenceDimensions(outcome)) {
            switch (name) {
            case IntrinsicDrawOutcomeBackend.CONTROLLER_HAND,
                    IntrinsicDrawOutcomeBackend.OPPONENT_HAND -> dimensions.add(
                            new ReferenceDimension(name, model.referenceIntegers(name, model.handSizes())));
            case IntrinsicDrawOutcomeBackend.CONTROLLER_LIFE,
                    IntrinsicDrawOutcomeBackend.OPPONENT_LIFE -> dimensions.add(
                            new ReferenceDimension(name, model.referenceIntegers(name, model.lifeTotals())));
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
            case IntrinsicDrawOutcomeBackend.SOURCE_P1P1,
                    IntrinsicDrawOutcomeBackend.CONTROLLER_P1P1,
                    IntrinsicDrawOutcomeBackend.OPPONENT_P1P1 -> dimensions.add(new ReferenceDimension(name,
                            model.referenceIntegers(name, model.quantities().distribution(IntrinsicReferenceQuantities.Quantity.P1P1_COUNTERS))));
            default -> throw new IllegalArgumentException("Unknown intrinsic reference dimension " + name);
            }
        }
        return dimensions;
    }

    private State referenceState(final ReferenceCase reference, final PermanentProfile source) {
        State state = new State(integer(reference, IntrinsicDrawOutcomeBackend.CONTROLLER_HAND, 3),
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
        final int includedSourceCounters = model.referenceIntegers(IntrinsicDrawOutcomeBackend.SOURCE_INITIAL_P1P1,
                WeightedDistribution.of(new WeightedValue<>(0, 1))).entries().get(0).value();
        if (includedSourceCounters > 0) { state = state.withP1p1(IntrinsicDrawOutcomeBackend.TargetRef.SOURCE, includedSourceCounters); }
        for (final var recipient : java.util.Map.of(IntrinsicDrawOutcomeBackend.SOURCE_P1P1, IntrinsicDrawOutcomeBackend.TargetRef.SOURCE,
                IntrinsicDrawOutcomeBackend.CONTROLLER_P1P1, IntrinsicDrawOutcomeBackend.TargetRef.CONTROLLER_CREATURE,
                IntrinsicDrawOutcomeBackend.OPPONENT_P1P1, IntrinsicDrawOutcomeBackend.TargetRef.OPPONENT_CREATURE).entrySet()) {
            final Integer count = reference.value(recipient.getKey(), Integer.class);
            if (count != null) { state = IntrinsicDrawOutcomeBackend.initializeP1p1(state, recipient.getValue(), count,
                    recipient.getValue() == IntrinsicDrawOutcomeBackend.TargetRef.SOURCE ? includedSourceCounters : 0); }
        }
        return state;
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
