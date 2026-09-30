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
        final Map<Card, IntrinsicEvaluation> intrinsicCache = new HashMap<>();
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
                    && candidate.getController().isOpponentOf(ai)
                    && isInspectableBattlefieldPermanent(candidate)) {
                final IntrinsicEvaluation intrinsic = intrinsicCache.computeIfAbsent(candidate,
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
                        + "battlefield permanent or is controlled by the AI)");
            }
            result.put(candidate, new Breakdown(relationshipValue, intrinsicValue,
                    hasUnevaluatedAbility, reasons));
        }
        return result;
    }

    private record IntrinsicEvaluation(List<AbilityValueContribution> contributions,
            boolean hasUnevaluatedAbility, List<String> reasons) {
        private IntrinsicEvaluation {
            contributions = List.copyOf(contributions);
            reasons = List.copyOf(reasons);
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
            reasons.add(candidate.getName() + ": static future allowance skipped (unsupported form)");
        }
    }

    private static IntrinsicEvaluation evaluateIntrinsic(final Player ai, final Card candidate,
            final List<AbilityValueContribution> relationshipEntries,
            final EffectAnalysisTrace trace) {
        final List<AbilityValueContribution> contributions = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();
        collectStaticFutureAllowances(ai, candidate, contributions, reasons);
        final boolean hasUnevaluatedAbility = collectIntrinsicAbilities(ai, candidate,
                relationshipEntries, contributions, reasons, trace);
        return new IntrinsicEvaluation(contributions, hasUnevaluatedAbility, reasons);
    }

    private static boolean collectIntrinsicAbilities(final Player ai, final Card candidate,
            final List<AbilityValueContribution> relationshipEntries,
            final List<AbilityValueContribution> destination, final List<String> reasons,
            final EffectAnalysisTrace trace) {
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
            liveDescriptions = CardAbilityTraversal.inspect(candidate.getCurrentState());
            values = definition.values();
        } catch (final RuntimeException failure) {
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

            final int aggregateValue = toInt(aggregate.value());
            final AbilityIdentity identity = new AbilityIdentity(value.path(), true);
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
                        SituationalFutureOutcomeEvaluator.evaluateActivatedAbility(ai, candidate,
                                value.path());
                final SituationalFutureOutcomeEvaluator.Evaluation immediate =
                        SituationalFutureOutcomeEvaluator.evaluateReadyActivatedAbility(ai,
                                candidate, value.path(), situational);
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
                        SituationalFutureOutcomeEvaluator.evaluateScheduledTrigger(ai, candidate,
                                value.path());
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
                final int allowance = toInt(aggregate.value() / value.expectedOccurrences());
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
