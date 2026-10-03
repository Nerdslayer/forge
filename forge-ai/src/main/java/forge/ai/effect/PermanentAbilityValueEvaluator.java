package forge.ai.effect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import forge.card.CardStateName;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/**
 * Combines live relationship analysis with the small, conservative intrinsic ability slice used
 * for removal decisions. The base permanent score remains owned by
 * {@link UnifiedPermanentValueEvaluator}.
 */
public final class PermanentAbilityValueEvaluator {
    private static final IntrinsicAbilityEvaluator INTRINSIC_EVALUATOR =
            new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(),
                    IntrinsicEvaluationSettings.defaults());

    private PermanentAbilityValueEvaluator() {
    }

    /** Explainable removal adjustment for one candidate. Positive values prefer removal. */
    public record Breakdown(int relationshipValue, int intrinsicValue,
            boolean hasUnevaluatedAbility, List<String> reasons) {
        public Breakdown {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    /** Includes the enabled intrinsic slice. */
    public static Map<Card, Breakdown> evaluateRemovalAbilities(final Player ai,
            final Iterable<Card> candidates, final EffectAnalysisTrace trace) {
        return evaluateRemovalAbilities(ai, candidates, trace, true);
    }

    /** Allows the caller to preserve relationship-only behavior while using the new API. */
    public static Map<Card, Breakdown> evaluateRemovalAbilities(final Player ai,
            final Iterable<Card> candidates, final EffectAnalysisTrace trace,
            final boolean includeIntrinsic) {
        return evaluateRemovalAbilities(ai, candidates, trace, includeIntrinsic, true);
    }

    /** Relationship entries suppress overlapping intrinsic credit only when the caller uses them. */
    public static Map<Card, Breakdown> evaluateRemovalAbilities(final Player ai,
            final Iterable<Card> candidates, final EffectAnalysisTrace trace,
            final boolean includeIntrinsic, final boolean applyRelationshipCredit) {
        return evaluateRemovalAbilities(ai, candidates, trace, null, includeIntrinsic,
                applyRelationshipCredit);
    }

    /** Evaluates removal abilities while modeling the proposed targeting action when supplied. */
    static Map<Card, Breakdown> evaluateRemovalAbilities(final Player ai,
            final Iterable<Card> candidates, final EffectAnalysisTrace trace,
            final SpellAbility removalAbility, final boolean includeIntrinsic,
            final boolean applyRelationshipCredit) {
        if (ai == null || candidates == null) {
            return Map.of();
        }

        final List<Card> candidateList = new ArrayList<>();
        candidates.forEach(candidate -> {
            if (candidate != null) {
                candidateList.add(candidate);
            }
        });
        final EffectAnalysisTrace effectiveTrace = trace == null
                ? EffectAnalysisTrace.disabled() : trace;
        final Map<Card, List<AbilityValueContribution>> relationships =
                EffectRelationshipEvaluator.evaluateRemovalContributions(ai, candidateList,
                        removalAbility, effectiveTrace);
        final Map<Card, Breakdown> result = new HashMap<>();
        final Map<Card, FutureAbilityEvaluation> intrinsicCache = new HashMap<>();
        for (final Card candidate : candidateList) {
            final List<String> reasons = new ArrayList<>();
            final List<AbilityValueContribution> relationshipEntries = relationships.getOrDefault(
                    candidate, List.of());
            int relationshipValue = 0;
            for (final AbilityValueContribution contribution : relationshipEntries) {
                if (contribution.counted()) {
                    relationshipValue = EffectMath.add(relationshipValue, contribution.value());
                }
                addReason(reasons, contribution);
            }

            int intrinsicValue = 0;
            boolean hasUnevaluatedAbility = false;
            if (includeIntrinsic && candidate.getController() != null
                    && isInspectableBattlefieldPermanent(candidate)) {
                final FutureAbilityEvaluation intrinsic = intrinsicCache.computeIfAbsent(candidate,
                        card -> evaluateIntrinsic(ai, card,
                                applyRelationshipCredit ? relationshipEntries : List.of(), effectiveTrace));
                hasUnevaluatedAbility = intrinsic.hasUnevaluatedAbility();
                reasons.addAll(intrinsic.reasons());
                for (final AbilityValueContribution contribution : intrinsic.contributions()) {
                    if (contribution.counted()) {
                        intrinsicValue = EffectMath.add(intrinsicValue, contribution.value());
                    }
                    addReason(reasons, contribution);
                }
            } else if (includeIntrinsic) {
                reasons.add(candidate.getName() + ": intrinsic value skipped (not an eligible live "
                        + "battlefield permanent)");
            }
            result.put(candidate, new Breakdown(relationshipValue, intrinsicValue,
                    hasUnevaluatedAbility, reasons));
        }
        return result;
    }

    record FutureAbilityEvaluation(List<AbilityValueContribution> contributions,
            boolean hasUnevaluatedAbility, List<String> reasons, Map<AbilityIdentity, Integer> referenceResolutionValues) {
        FutureAbilityEvaluation {
            contributions = List.copyOf(contributions);
            reasons = List.copyOf(reasons);
            referenceResolutionValues = Map.copyOf(referenceResolutionValues);
        }
        FutureAbilityEvaluation(final List<AbilityValueContribution> contributions,
                final boolean hasUnevaluatedAbility, final List<String> reasons) {
            this(contributions, hasUnevaluatedAbility, reasons, Map.of());
        }
    }

    /** Uses disabled tracing for callers that do not need diagnostics. */
    public static Map<Card, Breakdown> evaluateRemovalAbilities(final Player ai,
            final Iterable<Card> candidates, final boolean includeIntrinsic) {
        return evaluateRemovalAbilities(ai, candidates, EffectAnalysisTrace.disabled(),
                includeIntrinsic);
    }

    private static void collectStaticFutureAllowances(final Player ai, final Card candidate,
            final List<AbilityValueContribution> destination, final List<String> reasons) {
        for (final forge.game.staticability.StaticAbility ability : candidate.getStaticAbilities()) {
            collectStaticFutureAllowance(ai, candidate, ability, destination, reasons);
        }
        for (final forge.game.staticability.StaticAbility ability : candidate.getHiddenStaticAbilities()) {
            collectStaticFutureAllowance(ai, candidate, ability, destination, reasons);
        }
    }

    private static void collectStaticFutureAllowance(final Player ai, final Card candidate,
            final forge.game.staticability.StaticAbility ability,
            final List<AbilityValueContribution> destination, final List<String> reasons) {
        try {
            final java.util.Optional<AbilityValueContribution> allowance =
                    StaticAbilityFutureAllowanceEvaluator.evaluate(ai, candidate, ability);
            allowance.ifPresent(destination::add);
        } catch (final RuntimeException failure) {
            SituationalAnalysisSession.noteFailure(ai);
            reasons.add(candidate.getName() + ": static future allowance skipped (unsupported form)");
        }
    }

    private static FutureAbilityEvaluation evaluateIntrinsic(final Player ai, final Card candidate,
            final List<AbilityValueContribution> relationshipEntries,
            final EffectAnalysisTrace trace) {
        return evaluateFutureAbilities(ai, candidate, relationshipEntries, trace,
                FutureAbilityMode.LIVE_OUTCOMES);
    }

    /** Reference-only preparation cannot invoke live outcome/target/combat prediction callbacks. */
    enum FutureAbilityMode { LIVE_OUTCOMES, REFERENCE_ONLY }

    static FutureAbilityEvaluation evaluateFutureAbilities(final Player ai, final Card candidate,
            final List<AbilityValueContribution> relationshipEntries,
            final EffectAnalysisTrace trace, final FutureAbilityMode mode) {
        if (ai == null || candidate == null || candidate.getController() == null
                || !isInspectableBattlefieldPermanent(candidate)) {
            return new FutureAbilityEvaluation(List.of(), false,
                    List.of("Future ability value unavailable for this public permanent"));
        }
        final SituationalAnalysisSession session = SituationalAnalysisSession.current(ai);
        return session == null ? prepareFutureAbilities(ai, candidate, relationshipEntries, trace, mode)
                : session.futureAbilities(candidate, relationshipEntries, trace, mode,
                        () -> prepareFutureAbilities(ai, candidate, relationshipEntries, trace, mode));
    }

    private static FutureAbilityEvaluation prepareFutureAbilities(final Player ai, final Card candidate,
            final List<AbilityValueContribution> relationshipEntries,
            final EffectAnalysisTrace trace, final FutureAbilityMode mode) {
        final List<AbilityValueContribution> contributions = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();
        final Map<AbilityIdentity, Integer> referenceResolutionValues = new HashMap<>();
        collectStaticFutureAllowances(ai, candidate, contributions, reasons);
        final boolean hasUnevaluatedPrintedAbility = collectIntrinsicAbilities(ai, candidate,
                relationshipEntries, contributions, reasons, trace, mode, referenceResolutionValues);
        final boolean hasUnevaluatedGrantedAbility = hasUnevaluatedGrantedAbility(ai, candidate,
                relationshipEntries, reasons, trace, mode);
        final boolean hasUnevaluatedAbility = hasUnevaluatedPrintedAbility || hasUnevaluatedGrantedAbility;
        return new FutureAbilityEvaluation(contributions, hasUnevaluatedAbility, reasons, referenceResolutionValues);
    }

    private static boolean hasUnevaluatedGrantedAbility(final Player ai, final Card candidate,
            final List<AbilityValueContribution> relationships, final List<String> reasons,
            final EffectAnalysisTrace trace, final FutureAbilityMode mode) {
        // TODO: Value granted triggers/activations directly instead of using the coarse fallback.
        // Unsupported static/replacement abilities still need their own coverage policy. Do not
        // treat keyword expansion or mana production already scored by the body as unknown value.
        final List<CardAbilityTraversal.AbilityDescription> liveDescriptions;
        try {
            liveDescriptions = inspectLiveAbilities(ai, candidate, trace, mode);
        } catch (final RuntimeException unavailable) {
            SituationalAnalysisSession.noteFailure(ai);
            reasons.add(candidate.getName() + ": granted ability fallback skipped (live inventory unavailable)");
            return false;
        }
        for (final CardAbilityTraversal.AbilityDescription description : liveDescriptions) {
            if (description.provenance() != CardAbilityTraversal.Provenance.GRANTED
                    || !isActiveLiveAbility(candidate, description, description.path())
                    || overlapsKnownConsequence(candidate, description.path(), relationships)) {
                continue;
            }
            if (description.origin() == CardAbilityTraversal.Origin.ACTIVATION) {
                final SpellAbility ability = EffectAbilityUtils.abilityAtPath(candidate, description.path());
                if (ability == null || ability.getKeyword() != null || ability.isManaAbility()) {
                    continue;
                }
            } else if (description.origin() == CardAbilityTraversal.Origin.TRIGGER) {
                final Trigger trigger = EffectAbilityUtils.triggerAtPath(candidate, description.path());
                if (trigger == null || trigger.getKeyword() != null
                        || description.parameters().getOrDefault("TriggerDescription", "").startsWith("Landfall")) {
                    continue;
                }
            } else {
                continue;
            }
            reasons.add(description.path() + ": active granted ability has no intrinsic valuation");
            return true;
        }
        return false;
    }

    private static boolean collectIntrinsicAbilities(final Player ai, final Card candidate,
            final List<AbilityValueContribution> relationshipEntries,
            final List<AbilityValueContribution> destination, final List<String> reasons,
            final EffectAnalysisTrace trace, final FutureAbilityMode mode,
            final Map<AbilityIdentity, Integer> referenceResolutionValues) {
        if (candidate.getPaperCard() == null) {
            reasons.add(candidate.getName() + ": intrinsic value skipped (no public definition)");
            return false;
        }
        final List<CardAbilityTraversal.AbilityDescription> descriptions;
        final List<CardAbilityTraversal.AbilityDescription> liveDescriptions;
        final List<IntrinsicAbilityEvaluator.AbilityValue> values;
        try {
            final CardStateName face = candidate.getFaceupCardStateName();
            final IntrinsicAbilityEvaluator.DefinitionEvaluation definition =
                    INTRINSIC_EVALUATOR.evaluateDefinitionDetails(candidate.getPaperCard(), face);
            descriptions = definition.descriptions();
            liveDescriptions = inspectLiveAbilities(ai, candidate, trace, mode);
            values = definition.values();
        } catch (final RuntimeException failure) {
            SituationalAnalysisSession.noteFailure(ai);
            reasons.add(candidate.getName() + ": intrinsic value skipped (definition unavailable)");
            return false;
        }
        boolean hasUnevaluatedAbility = false;

        final Map<String, CardAbilityTraversal.AbilityDescription> byPath = new HashMap<>();
        for (final CardAbilityTraversal.AbilityDescription description : descriptions) {
            byPath.put(description.path(), description);
        }
        final Map<String, CardAbilityTraversal.AbilityDescription> liveByPath = new HashMap<>();
        for (final CardAbilityTraversal.AbilityDescription description : liveDescriptions) {
            liveByPath.put(description.path(), description);
        }
        for (final IntrinsicAbilityEvaluator.AbilityValue value : values) {
            final CardAbilityTraversal.AbilityDescription description = byPath.get(value.path());
            if (!isSafeIntrinsicDescription(description)) {
                addSkipped(destination, candidate, value.path(), "unsupported origin or keyword-derived ability");
                continue;
            }
            final CardAbilityTraversal.AbilityDescription liveDescription = liveByPath.get(value.path());
            if (!matchesLiveDefinition(description, liveDescription)
                    || !isActiveLiveAbility(candidate, description, value.path())) {
                addSkipped(destination, candidate, value.path(),
                        "printed ability does not match an active live ability");
                continue;
            }
            if (description.parameters().getOrDefault("TriggerDescription", "").startsWith("Landfall")) {
                addSkipped(destination, candidate, value.path(), "legacy CreatureEvaluator landfall credit");
                continue;
            }
            final IntrinsicReferenceAggregate aggregate = value.contribution();
            trace.intrinsicAbility(candidate, value.path(), description.origin().name(),
                    value.expectedOccurrences(), value.triggerStatus(), value.outcomeStatus(), aggregate);
            if (IntrinsicSelfEntryTriggerAdapter.isSelfEntry(description.parameters())) {
                // Entry value belongs to card/deployment evaluation, not removal of a permanent
                // already in play. TODO: Value future blink/re-entry through explicit actions.
                addSkipped(destination, candidate, value.path(), "self-ETB benefit already realized");
                continue;
            }
            if (!aggregate.complete() || aggregate.unresolvedRandomProbability() != 0) {
                hasUnevaluatedAbility = true;
                addSkipped(destination, candidate, value.path(), "intrinsic outcome is incomplete: "
                        + aggregate.unresolvedReasons());
                continue;
            }
            if (!isSafeIntrinsicOutcome(description.outcome())) {
                hasUnevaluatedAbility = true;
                addSkipped(destination, candidate, value.path(), "outcome is outside safe intrinsic slice");
                continue;
            }

            // Reference values describe the controller's benefit. Live outcome and static
            // evaluators already return signed removal value; only reference fallbacks need
            // conversion here. Losing a friendly benefit must not look like removing a threat.
            final double signedReferenceValue = aggregate.value()
                    * (candidate.getController().isOpponentOf(ai) ? 1 : -1);
            final int aggregateValue = toInt(signedReferenceValue);
            final AbilityIdentity identity = new AbilityIdentity(value.path(), true);
            if (description.origin() == CardAbilityTraversal.Origin.TRIGGER && value.expectedOccurrences() > 0) {
                // Metadata only: existing removal totals/occurrence estimates are unchanged.
                // Combat can retire one reference resolution after crediting its concrete outcome.
                // TODO: Attribute horizon-specific current shares when the occurrence report
                // exposes them, rather than consuming the bounded allowance by resolution count.
                referenceResolutionValues.put(identity, toInt(signedReferenceValue / value.expectedOccurrences()));
            }
            final String api = description.outcome().api();
            final boolean scheduled = ScheduledTriggerParser.parse(description.parameters()).isPresent();
            if (description.origin() == CardAbilityTraversal.Origin.ACTIVATION
                    && "Mana".equals(api)) {
                // ComputerUtilCard already owns the base value of mana abilities. Do not add the
                // same resource production again through the intrinsic activation allowance.
                addSkipped(destination, candidate, value.path(),
                        "mana activation value is already represented by base permanent evaluation");
                continue;
            }
            if (description.origin() == CardAbilityTraversal.Origin.ACTIVATION) {
                // The reference estimate describes a newly played card. Remove its current-turn
                // share before using it for later survival-weighted opportunities; an existing
                // permanent gets a separate immediate use only if that use is legal now.
                final double futureOccurrences = Math.max(0,
                        value.expectedOccurrences() - value.currentTurnUses());
                final SituationalFutureOutcomeEvaluator.Evaluation situational =
                        mode == FutureAbilityMode.LIVE_OUTCOMES
                                ? SituationalFutureOutcomeEvaluator.evaluateActivatedAbility(ai, candidate,
                                        value.path())
                                : SituationalFutureOutcomeEvaluator.Evaluation.unsupported("reference-only preparation");
                final SituationalFutureOutcomeEvaluator.Evaluation immediate =
                        mode == FutureAbilityMode.LIVE_OUTCOMES
                                ? SituationalFutureOutcomeEvaluator.evaluateReadyActivatedAbility(ai,
                                        candidate, value.path(), situational)
                                : SituationalFutureOutcomeEvaluator.Evaluation.unsupported("no immediate use in preparation");
                if (immediate.supported()) {
                    destination.add(AbilityValueContribution.counted(candidate, candidate, identity,
                            null, null, AbilityValueKind.INTRINSIC_IMMEDIATE, immediate.value(),
                            value.path() + ":activation-immediate", immediate.reason()));
                }
                final int allowance;
                final String contributionPath;
                final String contributionReason;
                if (situational.supported()) {
                    allowance = toInt(situational.value() * futureOccurrences);
                    contributionPath = value.path() + ":activation-future-situational";
                    contributionReason = situational.reason();
                } else {
                    allowance = value.expectedOccurrences() > 0
                            ? toInt(aggregateValue * futureOccurrences
                                    / value.expectedOccurrences()) : 0;
                    contributionPath = value.path() + ":activation-future-opportunity";
                    contributionReason = "Independent future-support allowance for activated " + api
                            + " ability (reference future uses; live refinement "
                            + situational.reason() + ")";
                }
                if (allowance != 0) {
                    destination.add(AbilityValueContribution.counted(candidate, candidate, identity,
                            null, null, AbilityValueKind.INTRINSIC_FUTURE_ALLOWANCE, allowance,
                            contributionPath, contributionReason));
                }
            } else if (scheduled) {
                // A production edge scores its consumer's reaction, not this producer's own
                // draw/counter outcome. Even a self-reaction is a different trigger/outcome.
                final SituationalFutureOutcomeEvaluator.Evaluation situational =
                        mode == FutureAbilityMode.LIVE_OUTCOMES
                                ? SituationalFutureOutcomeEvaluator.evaluateScheduledTrigger(ai, candidate,
                                        value.path())
                                : SituationalFutureOutcomeEvaluator.Evaluation.unsupported("reference-only preparation");
                if (situational.supported()) {
                    final int situationalValue = toInt(situational.value()
                            * value.expectedOccurrences());
                    if (situationalValue != 0) {
                        destination.add(AbilityValueContribution.counted(candidate, candidate, identity,
                                null, null, AbilityValueKind.INTRINSIC_SCHEDULED, situationalValue,
                                value.path() + ":scheduled-situational", situational.reason()));
                    }
                } else if (aggregateValue != 0) {
                    destination.add(AbilityValueContribution.counted(candidate, candidate, identity,
                            null, null, AbilityValueKind.INTRINSIC_SCHEDULED, aggregateValue,
                            value.path() + ":scheduled",
                            "Intrinsic scheduled " + api + " value (counted once; live refinement "
                                    + situational.reason() + ")"));
                }
            } else if ("Attacks".equals(description.parameters().get("Mode"))) {
                // This is the same self opportunity represented by an attack production and
                // this card's consequence. Use one estimate, never a second future allowance.
                if (overlapsKnownConsequence(candidate, value.path(), relationshipEntries)) {
                    destination.add(AbilityValueContribution.duplicate(candidate, identity,
                            "self-attack outcome already represented by a known relationship"));
                } else if (aggregateValue != 0) {
                    destination.add(AbilityValueContribution.counted(candidate, candidate, identity,
                            null, null, AbilityValueKind.INTRINSIC_SELF_OPPORTUNITY, aggregateValue,
                            value.path() + ":self-attack", "Reference self-attack estimate (no known outcome credit)"));
                }
            } else {
                // The event adapter is the single conservative policy gate shared with intrinsic
                // evaluation. Keep the allowance independent of the full intrinsic baseline and
                // do not increase it with the number of current producers.
                // TODO: Refine event likelihood with current board populations and contextual
                // self-opportunity without double counting known relationships.
                if (!IntrinsicEventTriggerAdapter.supportsIntrinsicParameters(description.parameters())
                        || value.expectedOccurrences() <= 0) {
                    hasUnevaluatedAbility = true;
                    addSkipped(destination, candidate, value.path(), "no future-support policy for this event");
                    continue;
                }
                if (overlapsKnownConsequence(candidate, value.path(), relationshipEntries)) {
                    destination.add(AbilityValueContribution.duplicate(candidate, identity,
                            "known relationship already represents this future tap opportunity"));
                    continue;
                }
                final int allowance = toInt(signedReferenceValue / value.expectedOccurrences());
                if (allowance != 0) {
                    destination.add(AbilityValueContribution.counted(candidate, candidate, identity,
                            null, null, AbilityValueKind.INTRINSIC_FUTURE_ALLOWANCE, allowance,
                            value.path() + ":future-opportunity",
                            "Independent future-support allowance: one reference " + api
                                    + " resolution (current producers counted separately)"));
                }
            }
        }
        return hasUnevaluatedAbility;
    }

    private static boolean isSafeIntrinsicDescription(
            final CardAbilityTraversal.AbilityDescription description) {
        return description != null
                && (description.origin() == CardAbilityTraversal.Origin.TRIGGER
                        || description.origin() == CardAbilityTraversal.Origin.ACTIVATION)
                && description.provenance() == CardAbilityTraversal.Provenance.PRINTED;
    }

    private static List<CardAbilityTraversal.AbilityDescription> inspectLiveAbilities(final Player ai,
            final Card candidate, final EffectAnalysisTrace trace, final FutureAbilityMode mode) {
        return mode == FutureAbilityMode.LIVE_OUTCOMES ? CardAbilityTraversal.inspectLive(ai, candidate, trace)
                : CardAbilityTraversal.inspect(candidate.getCurrentState());
    }

    private static boolean isInspectableBattlefieldPermanent(final Card candidate) {
        return candidate.isInZone(ZoneType.Battlefield)
                && candidate.getCurrentStateName() != CardStateName.FaceDown
                && !candidate.isCloned();
    }

    private static boolean matchesLiveDefinition(
            final CardAbilityTraversal.AbilityDescription definition,
            final CardAbilityTraversal.AbilityDescription live) {
        return live != null
                && definition.origin() == live.origin()
                && definition.provenance() == live.provenance()
                && definition.parameters().equals(live.parameters())
                && definition.outcome().equals(live.outcome());
    }

    private static boolean isActiveLiveTrigger(final Card candidate, final String path) {
        final Trigger trigger = EffectAbilityUtils.triggerAtPath(candidate, path);
        return trigger != null
                && EffectAbilityUtils.isActiveBattlefieldTriggerIgnoringRequirements(candidate, trigger);
    }

    private static boolean isActiveLiveAbility(final Card candidate,
            final CardAbilityTraversal.AbilityDescription description, final String path) {
        if (description.origin() == CardAbilityTraversal.Origin.TRIGGER) {
            return isActiveLiveTrigger(candidate, path);
        }
        return candidate.isInPlay() && !candidate.isPhasedOut();
    }

    private static boolean overlapsKnownConsequence(final Card candidate, final String path,
            final List<AbilityValueContribution> relationships) {
        return relationships.stream()
                .filter(entry -> entry.counted() && entry.kind() == AbilityValueKind.KNOWN_RELATIONSHIP
                        && entry.relatedSource() == candidate)
                .anyMatch(entry -> entry.relatedAbility() == null || !entry.relatedAbility().mapped()
                        || path.equals(entry.relatedAbility().path()));
    }

    private static boolean isSafeIntrinsicOutcome(final AbilityOutcomeDescription outcome) {
        // Complete choice, random, sequence, and simultaneous-batch trees are already evaluated
        // by IntrinsicAbilityEvaluator. Do not reimplement their semantics here: the aggregate's
        // complete/unresolved checks above are the admission gate. Unsupported branches remain
        // skipped, and dynamic/conditional forms still fail closed there.
        return outcome != null && outcome.issue().isEmpty();
    }

    private static int toInt(final double value) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE
                : value <= Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) Math.round(value);
    }

    private static void addSkipped(final List<AbilityValueContribution> destination,
            final Card candidate, final String path, final String reason) {
        destination.add(AbilityValueContribution.skipped(candidate, candidate,
                new AbilityIdentity(path, false), AbilityValueKind.SKIPPED, path, reason));
    }

    private static void addReason(final List<String> reasons,
            final AbilityValueContribution contribution) {
        if (contribution.reason() != null && !contribution.reason().isBlank()) {
            reasons.add(contribution.kind() + "[" + contribution.completeness() + "] "
                    + contribution.sourceAbility().path() + " = " + contribution.value()
                    + ": " + contribution.reason());
        }
    }
}
