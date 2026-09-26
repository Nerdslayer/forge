package forge.ai.effect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.function.Predicate;

import org.tinylog.Logger;

import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.CardResourceValueEvaluator;
import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Chooses the first action from the best independent set of mana-paying actions.
 *
 * <p>The engine still executes one action at a time. This selector only reorders the supplied
 * list, so the normal AI legality, targeting, and safety checks remain authoritative after the
 * selected action is returned. A small dynamic-programming table compares combinations by their
 * independently evaluated gross action values and a modest unused-mana penalty; mana constrains
 * which combinations are legal instead of being subtracted from every action again.</p>
 *
 * <p>This deliberately does not simulate the combined actions. It therefore does not model board
 * changes or an action changing the value or legality of a later action. Simple colored mana
 * source assignment is checked when the owning chooser supplies a snapshot; ambiguous source
 * models fail closed. The next chooser invocation reevaluates the live state after each action.</p>
 */
public final class ManaActionCombinationSelector {
    private static final int UNUSED_MANA_PENALTY = Math.max(1,
            CardResourceValueEvaluator.MANA_VALUE / 5);
    private static final int MAX_REPEATABLE_ACTIVATION_USES = 4;
    private static final int MAX_DIAGNOSTIC_DETAILS = 4;

    private ManaActionCombinationSelector() {
    }

    /** Result retained for focused callers and diagnostics. */
    public record Selection(SpellAbility firstAction, List<SpellAbility> actions,
            int availableMana, int usedMana, int unusedMana, int score,
            int handPressureBonus, int evaluatedCandidateCount, int fallbackCandidateCount,
            int fallbackActionCount, int incompleteActionCount, int uncertainActionCount,
            List<String> actionDiagnostics) {
        public Selection {
            actions = actions == null ? List.of() : List.copyOf(actions);
            actionDiagnostics = actionDiagnostics == null
                    ? List.of() : List.copyOf(actionDiagnostics);
        }

        public boolean hasAction() {
            return firstAction != null;
        }
    }

    /** Results of comparing one proposed plan with the plan anchored to the legacy first action. */
    public record Comparison(Selection proposed, Selection legacy, String status,
            List<String> diagnostics) {
        public Comparison {
            status = status == null ? "unknown" : status;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        public String diagnosticSummary() {
            return summarizeDiagnostics(status, diagnostics);
        }
    }

    /**
     * Reorders {@code abilities} so that the first action in the best supported combination is
     * first. The original ordering is preserved if no complete action can be evaluated.
     */
    public static Selection apply(final Player ai, final List<SpellAbility> abilities,
            final boolean skipCounter) {
        return apply(ai, abilities, skipCounter, null);
    }

    /** Reorders only after applying an optional legacy-admission predicate to each candidate. */
    public static Selection apply(final Player ai, final List<SpellAbility> abilities,
            final boolean skipCounter, final Predicate<SpellAbility> candidateFilter) {
        final Selection selection = select(ai, abilities, skipCounter, null, candidateFilter);
        reorder(abilities, selection);
        logSelection(selection);
        return selection;
    }

    /** Moves the selected first action to the front without re-evaluating the candidates. */
    public static void reorder(final List<SpellAbility> abilities, final Selection selection) {
        if (selection == null || !selection.hasAction() || abilities == null) {
            return;
        }
        final int index = abilities.indexOf(selection.firstAction());
        if (index > 0) {
            abilities.remove(index);
            abilities.add(0, selection.firstAction());
        }
    }

    /** Evaluates all currently usable cast and activation candidates without mutating the list. */
    public static Selection select(final Player ai, final List<SpellAbility> abilities,
            final boolean skipCounter) {
        return select(ai, abilities, skipCounter, null, null);
    }

    /** Evaluates only candidates admitted by the supplied legacy-behavior filter. */
    public static Selection select(final Player ai, final List<SpellAbility> abilities,
            final boolean skipCounter, final Predicate<SpellAbility> candidateFilter) {
        return select(ai, abilities, skipCounter, null, ActionDecisionSnapshot.capture(ai),
                candidateFilter);
    }

    /**
     * Evaluates combinations that contain {@code requiredFirstAction}.
     *
     * <p>The required action is reported as the first action in the returned selection. This is
     * used to compare the best continuation of the legacy choice with an alternative without
     * mutating or executing any ability.</p>
     */
    public static Selection selectWithFirstAction(final Player ai,
            final List<SpellAbility> abilities, final boolean skipCounter,
            final SpellAbility requiredFirstAction) {
        return selectWithFirstAction(ai, abilities, skipCounter, requiredFirstAction, null);
    }

    /** Anchored comparison variant that applies the same admission filter as active selection. */
    public static Selection selectWithFirstAction(final Player ai,
            final List<SpellAbility> abilities, final boolean skipCounter,
            final SpellAbility requiredFirstAction,
            final Predicate<SpellAbility> candidateFilter) {
        return select(ai, abilities, skipCounter, requiredFirstAction,
                ActionDecisionSnapshot.capture(ai), candidateFilter);
    }

    /** Evaluates candidates against a snapshot captured by the owning chooser invocation. */
    public static Selection select(final Player ai, final List<SpellAbility> abilities,
            final boolean skipCounter, final ActionDecisionSnapshot snapshot,
            final Predicate<SpellAbility> candidateFilter) {
        return select(ai, abilities, skipCounter, null, snapshot, candidateFilter);
    }

    /** Anchored comparison variant using the same immutable snapshot as active selection. */
    public static Selection selectWithFirstAction(final Player ai,
            final List<SpellAbility> abilities, final boolean skipCounter,
            final SpellAbility requiredFirstAction, final ActionDecisionSnapshot snapshot,
            final Predicate<SpellAbility> candidateFilter) {
        return select(ai, abilities, skipCounter, requiredFirstAction, snapshot, candidateFilter);
    }

    /**
     * Compares the global proposal and legacy-anchored proposal using one candidate analysis and
     * one wall-clock budget. The caller can use both results for the same conservative gate.
     */
    public static Comparison compare(final Player ai, final List<SpellAbility> abilities,
            final boolean skipCounter, final SpellAbility requiredFirstAction,
            final ActionDecisionSnapshot snapshot,
            final Predicate<SpellAbility> candidateFilter) {
        return compare(ai, abilities, skipCounter, requiredFirstAction, snapshot,
                candidateFilter, ignored -> "legacy candidate filter rejected this action");
    }

    /** Comparison variant that explains why a caller's legacy admission filter rejects actions. */
    public static Comparison compare(final Player ai, final List<SpellAbility> abilities,
            final boolean skipCounter, final SpellAbility requiredFirstAction,
            final ActionDecisionSnapshot snapshot,
            final Predicate<SpellAbility> candidateFilter,
            final Function<SpellAbility, String> candidateFilterReason) {
        final int availableMana = snapshot == null ? 0 : snapshot.availableMana();
        if (snapshot != null && !snapshot.manaResourcesComplete()) {
            logSkipped(snapshot, "mana_source_model_incomplete");
            final Selection empty = emptySelection(availableMana, 0);
            return comparison(empty, empty, "mana_source_model_incomplete", List.of());
        }
        PreparedCandidates prepared = null;
        final EffectEvaluationBudget budget = EffectEvaluationBudget.fromTimeoutMillis(
                actionSelectionTimeoutMillis(ai));
        final IdentityHashMap<SpellAbility, Boolean> admissionResults = new IdentityHashMap<>();
        final Predicate<SpellAbility> cachedCandidateFilter = ability -> {
            if (admissionResults.containsKey(ability)) {
                return admissionResults.get(ability);
            }
            final boolean admitted;
            try {
                admitted = candidateFilter == null || candidateFilter.test(ability);
            } catch (final RuntimeException ignored) {
                admissionResults.put(ability, false);
                return false;
            }
            admissionResults.put(ability, admitted);
            return admitted;
        };
        try {
            if (requiredFirstAction == null) {
                prepared = prepareCandidates(ai, abilities, skipCounter, snapshot,
                        cachedCandidateFilter, candidateFilterReason, budget);
                final Selection proposed = selectPrepared(prepared, null, snapshot, budget);
                final List<String> diagnostics = new ArrayList<>(prepared.candidateDiagnostics());
                diagnostics.addAll(proposed.actionDiagnostics());
                return comparison(proposed, emptySelection(availableMana, 0),
                        proposed.hasAction() ? "proposal_against_legacy_pass"
                                : "no_valued_action", diagnostics);
            }
            final AlternativeAdmission alternatives = inspectAlternativeActions(abilities,
                    requiredFirstAction, skipCounter, cachedCandidateFilter,
                    candidateFilterReason, budget);
            if (alternatives.alternativeCount() == 0) {
                logSkipped(snapshot, "no_distinct_alternative_action");
                final Selection empty = emptySelection(availableMana, 0);
                return comparison(empty, empty, "no_distinct_alternative_action",
                        alternatives.diagnostics());
            }
            if (alternatives.admittedCount() == 0) {
                logSkipped(snapshot, "alternatives_rejected_by_legacy_filter");
                final Selection empty = emptySelection(availableMana, 0);
                return comparison(empty, empty, "alternatives_rejected_by_legacy_filter",
                        alternatives.diagnostics());
            }
            prepared = prepareCandidates(ai, abilities, skipCounter, snapshot,
                    cachedCandidateFilter, candidateFilterReason,
                    budget);
            final Selection proposed = selectPrepared(prepared, null, snapshot, budget);
            final Selection legacy = selectPrepared(prepared, requiredFirstAction, snapshot, budget);
            final List<String> diagnostics = new ArrayList<>(alternatives.diagnostics());
            diagnostics.addAll(prepared.candidateDiagnostics());
            diagnostics.addAll(proposed.actionDiagnostics());
            diagnostics.addAll(legacy.actionDiagnostics());
            final String status = proposed.hasAction() && legacy.hasAction()
                    ? "plans_compared" : proposed.hasAction()
                            ? "legacy_action_not_valued" : "alternative_actions_not_valued";
            return comparison(proposed, legacy, status, diagnostics);
        } catch (final EffectEvaluationBudget.Exceeded exceeded) {
            final int timeoutMana = prepared == null ? availableMana : prepared.availableMana();
            final int evaluated = prepared == null ? 0 : prepared.candidates().size();
            final Selection timeout = timedOut(timeoutMana, evaluated);
            return comparison(timeout, timeout, "evaluation_timeout",
                    List.of("analysis budget exceeded after " + evaluated + " candidate(s)"));
        } catch (final RuntimeException exception) {
            final Selection empty = emptySelection(availableMana, 0);
            return comparison(empty, empty, "evaluation_error",
                    List.of("comparison failed with " + exception.getClass().getSimpleName()));
        }
    }

    private static AlternativeAdmission inspectAlternativeActions(
            final List<SpellAbility> abilities,
            final SpellAbility requiredFirstAction,
            final boolean skipCounter,
            final Predicate<SpellAbility> candidateFilter,
            final Function<SpellAbility, String> candidateFilterReason,
            final EffectEvaluationBudget budget) {
        if (abilities == null || requiredFirstAction == null
                || requiredFirstAction.getHostCard() == null) {
            return new AlternativeAdmission(0, 0, List.of());
        }
        int alternativeCount = 0;
        int admittedCount = 0;
        final List<String> rejected = new ArrayList<>();
        for (final SpellAbility ability : abilities) {
            budget.check();
            if (ability == null || ability == requiredFirstAction || ability.getHostCard() == null
                    || skipCounter && ability.getApi() == ApiType.Counter
                    || ability.getHostCard() == requiredFirstAction.getHostCard()
                            && !(PlaneswalkerActivationSupport.isLoyaltyAction(ability)
                                    && PlaneswalkerActivationSupport.isLoyaltyAction(
                                            requiredFirstAction))) {
                continue;
            }
            alternativeCount++;
            if (candidateFilter == null) {
                return new AlternativeAdmission(alternativeCount, admittedCount + 1, rejected);
            }
            try {
                if (candidateFilter.test(ability)) {
                    return new AlternativeAdmission(alternativeCount, admittedCount + 1,
                            rejected);
                }
                addDiagnostic(rejected, actionName(ability) + ": "
                        + safeFilterReason(ability, candidateFilterReason));
            } catch (final RuntimeException exception) {
                addDiagnostic(rejected, actionName(ability)
                        + ": legacy admission check failed ("
                        + exception.getClass().getSimpleName() + ")");
            }
        }
        return new AlternativeAdmission(alternativeCount, admittedCount, rejected);
    }

    private static Selection select(final Player ai, final List<SpellAbility> abilities,
            final boolean skipCounter, final SpellAbility requiredFirstAction,
            final ActionDecisionSnapshot snapshot,
            final Predicate<SpellAbility> candidateFilter) {
        if (ai == null || abilities == null || abilities.isEmpty()) {
            return emptySelection(0, 0);
        }
        if (snapshot != null && !snapshot.manaResourcesComplete()) {
            logSkipped(snapshot, "mana_source_model_incomplete");
            return emptySelection(snapshot.availableMana(), 0);
        }

        final EffectEvaluationBudget budget = EffectEvaluationBudget.fromTimeoutMillis(
                actionSelectionTimeoutMillis(ai));
        try {
            final PreparedCandidates prepared = prepareCandidates(ai, abilities, skipCounter,
                    snapshot, candidateFilter, ignored -> "legacy candidate filter rejected action",
                    budget);
            return selectPrepared(prepared, requiredFirstAction, snapshot, budget);
        } catch (final EffectEvaluationBudget.Exceeded exceeded) {
            return timedOut(snapshot == null ? 0 : snapshot.availableMana(), 0);
        } catch (final RuntimeException ignored) {
            // The existing chooser remains the safe fallback if mana estimation is unavailable.
            return emptySelection(0, 0);
        }
    }

    private static PreparedCandidates prepareCandidates(final Player ai,
            final List<SpellAbility> abilities, final boolean skipCounter,
            final ActionDecisionSnapshot snapshot, final Predicate<SpellAbility> candidateFilter,
            final Function<SpellAbility, String> candidateFilterReason,
            final EffectEvaluationBudget budget) {
        if (ai == null || abilities == null || abilities.isEmpty()) {
            return new PreparedCandidates(List.of(), new IdentityHashMap<>(), List.of(),
                    manaSourceModel(snapshot), 0, 0, 0, 0, List.of());
        }

        budget.check();
        final int availableMana = snapshot == null ? Math.max(0,
                ComputerUtilMana.getAvailableManaEstimate(ai, true)) : snapshot.availableMana();
        budget.check();
        final List<Candidate> candidates = new ArrayList<>();
        final IdentityHashMap<SpellAbility, Candidate> candidatesByAbility =
                new IdentityHashMap<>();
        final List<String> candidateDiagnostics = new ArrayList<>();
        for (final SpellAbility ability : abilities) {
            budget.check();
            if (Thread.currentThread().isInterrupted()) {
                return new PreparedCandidates(List.of(), new IdentityHashMap<>(), List.of(),
                        manaSourceModel(snapshot), availableMana, 0, 0, 0,
                        List.of("candidate scan interrupted"));
            }
            if (candidateFilter != null) {
                try {
                    if (!candidateFilter.test(ability)) {
                        addDiagnostic(candidateDiagnostics, actionName(ability) + ": "
                                + safeFilterReason(ability, candidateFilterReason));
                        continue;
                    }
                } catch (final RuntimeException exception) {
                    addDiagnostic(candidateDiagnostics, actionName(ability)
                            + ": legacy admission check failed ("
                            + exception.getClass().getSimpleName() + ")");
                    continue;
                }
            }
            try {
                final CandidateBuildResult result = createCandidate(ai, ability, availableMana,
                        skipCounter, budget);
                final Candidate candidate = result.candidate();
                if (candidate != null) {
                    candidates.add(candidate);
                    candidatesByAbility.putIfAbsent(ability, candidate);
                } else if (result.rejectionReason() != null) {
                    addDiagnostic(candidateDiagnostics, actionName(ability) + ": "
                            + result.rejectionReason());
                }
            } catch (final EffectEvaluationBudget.Exceeded exceeded) {
                throw exceeded;
            } catch (final RuntimeException exception) {
                addDiagnostic(candidateDiagnostics, actionName(ability)
                        + ": valuation failed (" + exception.getClass().getSimpleName() + ")");
            }
        }

        final int handOverflow = snapshot == null
                ? ai.isUnlimitedHandSize() ? 0
                        : Math.max(0, ai.getCardsIn(ZoneType.Hand).size() - ai.getMaxHandSize())
                : snapshot.handOverflow();
        final int handPressureValue = handOverflow == 0 ? 0
                : CardResourceValueEvaluator.evaluateNextCard(ai.getMaxHandSize());
        budget.check();
        final int fallbackCandidateCount = (int) candidates.stream()
                .filter(Candidate::fallback).count();
        return new PreparedCandidates(candidates, candidatesByAbility,
                groupCandidates(candidates), manaSourceModel(snapshot), availableMana,
                handOverflow, handPressureValue, fallbackCandidateCount,
                candidateDiagnostics);
    }

    private static Selection selectPrepared(final PreparedCandidates prepared,
            final SpellAbility requiredFirstAction, final ActionDecisionSnapshot snapshot,
            final EffectEvaluationBudget budget) {
        final List<Candidate> candidates = prepared.candidates();
        if (candidates.isEmpty()) {
            return emptySelection(prepared.availableMana(), 0,
                    prepared.candidateDiagnostics());
        }

        budget.check();
        final State best;
        final Candidate required = prepared.candidatesByAbility().get(requiredFirstAction);
        if (requiredFirstAction != null && required == null) {
            final List<String> diagnostics = new ArrayList<>(prepared.candidateDiagnostics());
            addDiagnostic(diagnostics, "required legacy action " + actionName(requiredFirstAction)
                    + " has no valued candidate");
            return emptySelection(prepared.availableMana(), candidates.size(), diagnostics);
        }
        best = solve(prepared.groups(), required,
                prepared.availableMana(), prepared.handOverflow(), snapshot,
                prepared.manaSourceModel(), budget);
        if (best == null || best.actionCount() == 0) {
            return new Selection(null, List.of(), prepared.availableMana(), 0,
                    prepared.availableMana(), -prepared.availableMana() * UNUSED_MANA_PENALTY,
                    0, candidates.size(), prepared.fallbackCandidateCount(), 0, 0, 0,
                    prepared.candidateDiagnostics());
        }

        final int pressure = prepared.handPressureValue()
                * Math.min(best.castCount(), prepared.handOverflow());
        final int unusedMana = prepared.availableMana() - best.usedMana();
        final int score = saturatedAdd(best.value(), pressure,
                -unusedMana * UNUSED_MANA_PENALTY);
        final List<SpellAbility> actions = firstActionFirst(best.toActions(), requiredFirstAction);
        final SpellAbility firstAction = requiredFirstAction == null
                ? actions.get(0) : requiredFirstAction;
        int fallbackActionCount = 0;
        int incompleteActionCount = 0;
        int uncertainActionCount = 0;
        final List<String> actionDiagnostics = new ArrayList<>();
        for (final SpellAbility action : actions) {
            final Candidate candidate = prepared.candidatesByAbility().get(action);
            if (candidate == null) {
                continue;
            }
            final boolean fallbackAction = candidate.fallback();
            final boolean incompleteAction = !candidate.estimate().canEstablishOverride();
            final boolean uncertainAction = candidate.estimate().resources().uncertain();
            if (fallbackAction) {
                fallbackActionCount++;
            }
            if (incompleteAction) {
                incompleteActionCount++;
            }
            if (uncertainAction) {
                uncertainActionCount++;
            }
            if (fallbackAction || incompleteAction || uncertainAction) {
                addDiagnostic(actionDiagnostics, actionName(action) + " [fallback="
                        + fallbackAction + ", completeness="
                        + candidate.estimate().completeness() + ", uncertainResources="
                        + uncertainAction + "]: "
                        + String.join(", ", candidate.estimate().limitations()));
            }
        }
        return new Selection(firstAction, actions, prepared.availableMana(),
                best.usedMana(), unusedMana, score, pressure, candidates.size(),
                prepared.fallbackCandidateCount(), fallbackActionCount, incompleteActionCount,
                uncertainActionCount, actionDiagnostics);
    }

    private static List<SpellAbility> firstActionFirst(final List<SpellAbility> actions,
            final SpellAbility requiredFirstAction) {
        if (requiredFirstAction == null || actions.isEmpty()
                || actions.get(0) == requiredFirstAction) {
            return actions;
        }
        final List<SpellAbility> reordered = new ArrayList<>(actions.size());
        reordered.add(requiredFirstAction);
        boolean removed = false;
        for (final SpellAbility action : actions) {
            if (!removed && action == requiredFirstAction) {
                removed = true;
                continue;
            }
            reordered.add(action);
        }
        return reordered;
    }

    private static CandidateBuildResult createCandidate(final Player ai,
            final SpellAbility ability,
            final int availableMana, final boolean skipCounter,
            final EffectEvaluationBudget budget) {
        if (ability == null || ability.getHostCard() == null || ability.getPayCosts() == null
                || ability.getPayCosts().getTotalMana() == null
                || skipCounter && ability.getApi() == ApiType.Counter
                || ability.isLandAbility() || ability.isManaAbility()) {
            return CandidateBuildResult.rejected(
                    "not a supported mana-paying cast or activation candidate");
        }
        final Card host = ability.getHostCard();

        final ValuationAction action;
        final ValuationContext context;
        final boolean cast;
        final SituationalAbilityOccurrenceContext.SupportedActivationCost activationCost;
        final SpellAbility costAbility;
        final int xValue;
        int manaCost;
        if (ability.isSpell() && host.isInZone(ZoneType.Hand)
                && (host.getOwner() == ai || host.getController() == ai)) {
            final SpellAbility probe = ability.copy(host, ai, false);
            probe.setActivatingPlayer(ai);
            costAbility = probe;
            xValue = announcedXValue(probe, ai);
            if (ability.getPayCosts().getTotalMana().countX() > 0 && xValue < 0) {
                return CandidateBuildResult.rejected("announced X value is not supported");
            }
            manaCost = announcedManaCost(probe);
            if (manaCost > availableMana) {
                return CandidateBuildResult.rejected("requires " + manaCost
                        + " mana; only " + availableMana + " is available");
            }
            if (host.isLand()) {
                return CandidateBuildResult.rejected("land play is not a spell-value candidate");
            }
            if (!probe.canCastTiming(ai)) {
                return CandidateBuildResult.rejected("cast timing is not currently legal");
            }
            if (!ComputerUtilCost.canPayCost(probe, ai, probe.isTrigger())) {
                return CandidateBuildResult.rejected("spell cost cannot currently be paid");
            }
            action = new CastValuationAction(probe);
            context = ValuationContext.forCast(ai, true);
            cast = true;
            activationCost = null;
        } else if (ability.isActivatedAbility() && host.isInPlay()
                && host.getController() == ai && !host.isPhasedOut()) {
            // Reject unsupported costs before payment probing. In particular, CostTapType is
            // used by crew abilities and its payment search can be expensive even though this
            // selector cannot value that activation yet.
            final SpellAbility probe = ability.copy(host, false);
            probe.setActivatingPlayer(ai);
            xValue = announcedXValue(probe, ai);
            if (ability.getPayCosts().getTotalMana().countX() > 0 && xValue < 0) {
                return CandidateBuildResult.rejected("announced X value is not supported");
            }
            if (ability.isPwAbility() && host.isPlaneswalker()
                    && !probe.canPlay()) {
                traceLoyaltyCandidate(ability, ai, "unavailable", "engine legality rejected");
                return CandidateBuildResult.rejected("engine rejected current ability legality");
            }
            if (ability.isPwAbility() && host.isPlaneswalker()
                    && !probe.canCastTiming(ai)) {
                traceLoyaltyCandidate(ability, ai, "unavailable", "loyalty timing is not legal");
                return CandidateBuildResult.rejected("loyalty timing is not currently legal");
            }
            activationCost = SituationalAbilityOccurrenceContext.supportedActivationCost(
                    probe.getPayCosts(), probe).orElse(null);
            if (activationCost == null) {
                if (ability.isPwAbility() && host.isPlaneswalker()) {
                    traceLoyaltyCandidate(ability, ai, "unsupported",
                            "activation cost is not modeled");
                }
                return CandidateBuildResult.rejected("activation cost is not modeled");
            }
            manaCost = activationCost.manaCost();
            if (manaCost > availableMana) {
                if (ability.isPwAbility() && host.isPlaneswalker()) {
                    traceLoyaltyCandidate(ability, ai, "unavailable", "not enough available mana");
                }
                return CandidateBuildResult.rejected("requires " + manaCost
                        + " mana; only " + availableMana + " is available");
            }
            if (!ComputerUtilCost.canPayCost(probe, ai, false)) {
                if (ability.isPwAbility() && host.isPlaneswalker()) {
                    traceLoyaltyCandidate(ability, ai, "unavailable", "activation cost cannot be paid");
                }
                return CandidateBuildResult.rejected("activation cost cannot currently be paid");
            }
            action = new ActivateValuationAction(host, probe);
            context = ValuationContext.forActivation(ai, true);
            cast = false;
            costAbility = probe;
        } else {
            return CandidateBuildResult.rejected(
                    "not a cast from hand or an activation of an AI-controlled permanent");
        }

        final ActionCostAnalysis costAnalysis = ActionCostSupport.analyze(costAbility, ai);
        if (!cast && ability.isPwAbility() && host.isPlaneswalker()
                && !costAnalysis.supported()) {
            traceLoyaltyCandidate(ability, ai, "unsupported",
                    String.join(", ", costAnalysis.reasons()));
            return CandidateBuildResult.rejected("additional cost is unsupported: "
                    + String.join(", ", costAnalysis.reasons()));
        }
        budget.check();
        final CardValueBreakdown value = UnifiedActionValueEvaluator.evaluate(action, context,
                EffectAnalysisTrace.disabled(), budget);
        budget.check();
        if (!cast && ability.isPwAbility() && host.isPlaneswalker()
                && !value.isComplete()) {
            // Unsupported loyalty abilities remain with the legacy chooser; their generic
            // activation fallback must never displace a known planeswalker mode.
            traceLoyaltyCandidate(ability, ai, value.completeness().name(),
                    String.join(", ", value.reasons()));
            return CandidateBuildResult.rejected("planeswalker outcome is "
                    + value.completeness().name().toLowerCase() + ": "
                    + String.join(", ", value.reasons()));
        }
        final ActionValueFallbackEvaluator.Estimate fallback;
        if (value.isComplete()) {
            fallback = null;
        } else if (value.completeness() == ValuationCompleteness.UNAVAILABLE) {
            // A legal-looking action can still be unavailable because its current target or
            // state-dependent outcome cannot occur. Do not turn that into a generic action.
            return CandidateBuildResult.rejected("outcome unavailable: "
                    + String.join(", ", value.reasons()));
        } else if (cast) {
            fallback = ActionValueFallbackEvaluator.cast(manaCost);
        } else {
            fallback = ActionValueFallbackEvaluator.activation(ai, manaCost,
                    activationCost == null ? 0 : activationCost.lifeCost());
        }
        final int maxUses = maximumUses(ability, activationCost, cast, manaCost, availableMana);
        final int explicitNonManaCost = fallback == null ? costAnalysis.explicitValue() : 0;
        ActionResourceFootprint resources = costAnalysis.supported()
                ? costAnalysis.resources()
                : uncertainResources(host, cast);
        if (!cast && ability.isPwAbility() && host.isPlaneswalker()) {
            resources = resources.withLoyaltyActivationSource(host);
        }
        final ActionSelectionEstimate estimate = new ActionSelectionEstimate(
                fallback == null ? value.grossValue() : fallback.value(), explicitNonManaCost,
                resources, fallback == null ? value.completeness() : ValuationCompleteness.PARTIAL,
                fallback != null, fallback == null ? value.reasons()
                        : List.of(fallback.reason(), "Additional cost model: "
                                + String.join(", ", costAnalysis.reasons())));
        if (!cast && ability.isPwAbility() && host.isPlaneswalker()) {
            if (estimate.netBenefit() <= 0) {
                traceLoyaltyCandidate(ability, ai, "rejected",
                        "nonpositive net value=" + estimate.netBenefit());
                return CandidateBuildResult.rejected("evaluated net value is nonpositive ("
                        + estimate.netBenefit() + ")");
            }
            traceLoyaltyCandidate(ability, ai, "complete",
                    "outcome and costs are supported; selector benefit="
                            + estimate.netBenefit());
        }
        return CandidateBuildResult.accepted(new Candidate(ability, manaCost,
                ability.getPayCosts().getTotalMana(), xValue, cast, maxUses, estimate));
    }

    private static ActionResourceFootprint uncertainResources(final Card host, final boolean cast) {
        return cast
                ? ActionResourceFootprint.withRequirements(List.of(host), List.of(), List.of(), true)
                : ActionResourceFootprint.withRequirements(List.of(), List.of(), List.of(), true);
    }

    private static int announcedXValue(final SpellAbility ability, final Player ai) {
        if (ability == null || ability.getPayCosts() == null
                || ability.getPayCosts().getTotalMana() == null
                || ability.getPayCosts().getTotalMana().countX() == 0) {
            return 0;
        }
        if (!ability.hasSVar("X") || !"Count$xPaid".equals(ability.getSVar("X"))) {
            // Other X semantics can depend on targets, costs, or card-specific choices. The
            // live chooser remains authoritative until those announced values can be copied.
            return -1;
        }
        final int x = ComputerUtilCost.setMaxXValue(ability, ai, ability.isTrigger());
        if (x <= 0) {
            return -1;
        }
        ability.setXManaCostPaid(x);
        return x;
    }

    private static int announcedManaCost(final SpellAbility ability) {
        final ManaCost mana = ability.getPayCosts().getTotalMana();
        final Integer xPaid = ability.getXManaCostPaid();
        return Math.max(0, mana.getCMC() + (xPaid == null ? 0 : xPaid * mana.countX()));
    }

    /**
     * Bounds repeated activations to costs whose resource parts are explicitly reusable. A tap,
     * source-consuming cost, activation limit, condition, or zero-mana action remains one use;
     * those cases need live state changes or a dedicated use-limit model.
     */
    private static int maximumUses(final SpellAbility ability,
            final SituationalAbilityOccurrenceContext.SupportedActivationCost activationCost,
            final boolean cast, final int manaCost, final int availableMana) {
        if (cast || ability.isPwAbility() || activationCost == null || activationCost.hasTapCost()
                || activationCost.consumesSource() || manaCost <= 0
                || ability.getPayCosts() == null || !ability.getPayCosts().isReusuableResource()
                || ability.getRestrictions().getLimitToCheck() != null
                || ability.getRestrictions().getGameLimitToCheck() != null
                || hasActivationCondition(ability)) {
            return 1;
        }
        // The diminishing value applied by State.add prevents the independent optimizer from
        // treating repeated activations as identical copies. The cap keeps this bounded until
        // exact post-resolution state simulation is available.
        return Math.max(1, Math.min(MAX_REPEATABLE_ACTIVATION_USES,
                availableMana / manaCost));
    }

    private static boolean hasActivationCondition(final SpellAbility ability) {
        return ability.getMapParams().keySet().stream()
                .anyMatch(key -> key.startsWith("Condition"));
    }

    private static State solve(final List<List<Candidate>> groups, final Candidate required,
            final int availableMana, final int handOverflow,
            final ActionDecisionSnapshot snapshot, final ManaSourceModel manaSourceModel,
            final EffectEvaluationBudget budget) {
        State[][] states = new State[availableMana + 1][handOverflow + 1];
        states[0][0] = new State(0, 0, 0, null, null, 0);

        for (final List<Candidate> group : groups) {
            budget.check();
            final boolean containsRequired = required != null && group.contains(required);
            if (!containsRequired && group.size() == 1) {
                applyCandidateInPlace(states, group.get(0), availableMana, handOverflow, budget);
                continue;
            }
            final State[][] nextStates = containsRequired
                    ? new State[availableMana + 1][handOverflow + 1] : copyStates(states);
            for (final Candidate candidate : group) {
                if (containsRequired && candidate != required) {
                    continue;
                }
                budget.check();
                final int uses = Math.min(candidate.maxUses(),
                        candidate.manaCost() == 0 ? 1 : availableMana / candidate.manaCost());
                for (int mana = 0; mana <= availableMana; mana++) {
                    budget.check();
                    for (int casts = 0; casts <= handOverflow; casts++) {
                        final State current = states[mana][casts];
                        if (current == null) {
                            continue;
                        }
                        State repeated = current;
                        for (int use = 1; use <= uses; use++) {
                            final int newMana = mana + use * candidate.manaCost();
                            if (newMana > availableMana) {
                                break;
                            }
                            final int newCasts = Math.min(handOverflow,
                                    casts + (candidate.cast() ? 1 : 0));
                            repeated = repeated.add(candidate, newCasts, use);
                            if (isBetter(repeated, nextStates[newMana][newCasts])) {
                                nextStates[newMana][newCasts] = repeated;
                            }
                        }
                    }
                }
            }
            states = nextStates;
        }

        if (snapshot != null) {
            final List<State> finalStates = new ArrayList<>();
            for (int mana = 0; mana <= availableMana; mana++) {
                budget.check();
                for (int casts = 0; casts <= handOverflow; casts++) {
                    final State state = states[mana][casts];
                    if (state != null && state.actionCount() > 0) {
                        finalStates.add(state);
                    }
                }
            }
            finalStates.sort((first, second) -> {
                final int scoreOrder = Integer.compare(finalStateScore(second, availableMana),
                        finalStateScore(first, availableMana));
                if (scoreOrder != 0) {
                    return scoreOrder;
                }
                final int manaOrder = Integer.compare(second.usedMana(), first.usedMana());
                return manaOrder != 0 ? manaOrder
                        : Integer.compare(first.castCount(), second.castCount());
            });
            for (final State state : finalStates) {
                budget.check();
                if (isManaFeasible(state, snapshot, manaSourceModel)) {
                    return state;
                }
            }
            return null;
        }

        State best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int mana = 0; mana <= availableMana; mana++) {
            budget.check();
            for (int casts = 0; casts <= handOverflow; casts++) {
                final State state = states[mana][casts];
                if (state == null || state.actionCount() == 0) {
                    continue;
                }
                final int score = finalStateScore(state, availableMana);
                if (best == null || score > bestScore
                        || score == bestScore && mana > best.usedMana()) {
                    best = state;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private static int finalStateScore(final State state, final int availableMana) {
        return saturatedAdd(state.value(),
                -((availableMana - state.usedMana()) * UNUSED_MANA_PENALTY));
    }

    /**
     * Most candidates have no mutually exclusive resource footprint and form singleton groups.
     * Apply those to the existing table in reverse coordinate order so the new state for an
     * action cannot be consumed again during the same pass.
     */
    private static void applyCandidateInPlace(final State[][] states,
            final Candidate candidate, final int availableMana, final int handOverflow,
            final EffectEvaluationBudget budget) {
        final int uses = Math.min(candidate.maxUses(), candidate.manaCost() == 0
                ? 1 : availableMana / candidate.manaCost());
        for (int mana = availableMana; mana >= 0; mana--) {
            budget.check();
            for (int casts = handOverflow; casts >= 0; casts--) {
                final State current = states[mana][casts];
                if (current == null) {
                    continue;
                }
                State repeated = current;
                for (int use = 1; use <= uses; use++) {
                    final int newMana = mana + use * candidate.manaCost();
                    if (newMana > availableMana) {
                        break;
                    }
                    final int newCasts = Math.min(handOverflow,
                            casts + (candidate.cast() ? 1 : 0));
                    repeated = repeated.add(candidate, newCasts, use);
                    if (isBetterInPlace(repeated, states[newMana][newCasts], candidate,
                            current, use)) {
                        states[newMana][newCasts] = repeated;
                    }
                }
            }
        }
    }

    /** Preserve the former ascending-transition tie order while processing the table in place. */
    private static boolean isBetterInPlace(final State candidateState, final State existing,
            final Candidate candidate, final State source, final int useNumber) {
        if (existing == null || candidateState.value() > existing.value()) {
            return true;
        }
        if (candidateState.value() < existing.value()
                || existing.candidate() != candidate || existing.parent() == null) {
            // Before the refactor, untouched states were copied into the next table first, so an
            // equal-valued transition never displaced the best plan that was already there.
            return false;
        }
        State existingSource = existing;
        int existingUse = 0;
        while (existingSource != null && existingSource.candidate() == candidate) {
            existingUse++;
            existingSource = existingSource.parent();
        }
        if (existingSource == null) {
            return false;
        }
        if (source.usedMana() != existingSource.usedMana()) {
            return source.usedMana() < existingSource.usedMana();
        }
        if (source.castCount() != existingSource.castCount()) {
            return source.castCount() < existingSource.castCount();
        }
        return useNumber < existingUse;
    }

    /**
     * A card may expose several cast modes, but casting one mode consumes the card. Non-tap
     * activated abilities remain independent; tap activations on the same permanent are grouped
     * as alternatives because the first one consumes the source's untapped state.
     */
    private static List<List<Candidate>> groupCandidates(final List<Candidate> candidates) {
        final IdentityHashMap<Card, List<Candidate>> exclusiveResourceGroups =
                new IdentityHashMap<>();
        final IdentityHashMap<Card, List<Candidate>> loyaltyActivationGroups =
                new IdentityHashMap<>();
        final List<List<Candidate>> groups = new ArrayList<>();
        for (final Candidate candidate : candidates) {
            final ActionResourceFootprint resources = candidate.estimate().resources();
            final List<Card> exclusiveResources = new ArrayList<>(resources.consumedCards());
            exclusiveResources.addAll(resources.tappedSources());
            final List<Card> loyaltySources = resources.loyaltyActivationSources();
            if (exclusiveResources.isEmpty() && loyaltySources.isEmpty()) {
                groups.add(List.of(candidate));
                continue;
            }

            // A cast consumes its card, a tap activation consumes the source's untapped state,
            // and a planeswalker can use only one loyalty ability each turn. Track loyalty use
            // separately so a non-loyalty tap ability on that planeswalker can still coexist.
            final List<List<Candidate>> linkedGroups = new ArrayList<>();
            for (final Card exclusiveResource : exclusiveResources) {
                final List<Candidate> group = exclusiveResourceGroups.get(exclusiveResource);
                if (group != null && !linkedGroups.contains(group)) {
                    linkedGroups.add(group);
                }
            }
            for (final Card loyaltySource : loyaltySources) {
                final List<Candidate> group = loyaltyActivationGroups.get(loyaltySource);
                if (group != null && !linkedGroups.contains(group)) {
                    linkedGroups.add(group);
                }
            }
            final List<Candidate> group;
            if (linkedGroups.isEmpty()) {
                group = new ArrayList<>();
                groups.add(group);
            } else {
                group = linkedGroups.get(0);
                for (int i = linkedGroups.size() - 1; i > 0; i--) {
                    final List<Candidate> merged = linkedGroups.get(i);
                    group.addAll(merged);
                    groups.remove(merged);
                    replaceGroup(exclusiveResourceGroups, merged, group);
                    replaceGroup(loyaltyActivationGroups, merged, group);
                }
            }
            group.add(candidate);
            for (final Card exclusiveResource : exclusiveResources) {
                exclusiveResourceGroups.put(exclusiveResource, group);
            }
            for (final Card loyaltySource : loyaltySources) {
                loyaltyActivationGroups.put(loyaltySource, group);
            }
        }
        return groups;
    }

    private static void replaceGroup(final IdentityHashMap<Card, List<Candidate>> groups,
            final List<Candidate> oldGroup, final List<Candidate> newGroup) {
        for (final Card key : new ArrayList<>(groups.keySet())) {
            if (groups.get(key) == oldGroup) {
                groups.put(key, newGroup);
            }
        }
    }

    private static State[][] copyStates(final State[][] states) {
        final State[][] copy = new State[states.length][];
        for (int i = 0; i < states.length; i++) {
            copy[i] = states[i].clone();
        }
        return copy;
    }

    private static boolean isBetter(final State candidate, final State existing) {
        return existing == null || candidate.value() > existing.value();
    }

    private static int saturatedAdd(final int... values) {
        long result = 0;
        for (final int value : values) {
            result += value;
            if (result >= Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            if (result <= Integer.MIN_VALUE) {
                return Integer.MIN_VALUE;
            }
        }
        return (int) result;
    }

    private static Selection emptySelection(final int availableMana, final int evaluated) {
        return emptySelection(availableMana, evaluated, List.of());
    }

    private static Selection emptySelection(final int availableMana, final int evaluated,
            final List<String> diagnostics) {
        return new Selection(null, List.of(), availableMana, 0, availableMana,
                -availableMana * UNUSED_MANA_PENALTY, 0, evaluated, 0, 0, 0, 0,
                diagnostics);
    }

    private static Comparison comparison(final Selection proposed, final Selection legacy,
            final String status, final List<String> diagnostics) {
        final List<String> bounded = diagnostics == null ? List.of()
                : List.copyOf(diagnostics);
        final Comparison result = new Comparison(proposed, legacy, status, bounded);
        if (!"plans_compared".equals(status) && !"proposal_against_legacy_pass".equals(status)) {
            logComparisonDiagnostic(result);
        }
        return result;
    }

    private static void logComparisonDiagnostic(final Comparison comparison) {
        if (!Boolean.parseBoolean(System.getProperty(EffectAnalysisTrace.ENABLE_PROPERTY, "true"))) {
            return;
        }
        Logger.info("[AI Effect Analysis] Action comparison assessment: status={}, details={}",
                comparison.status(), comparison.diagnosticSummary());
    }

    private static String summarizeDiagnostics(final String status,
            final List<String> diagnostics) {
        final StringJoiner summary = new StringJoiner("; ");
        if (diagnostics != null) {
            for (int i = 0; i < Math.min(diagnostics.size(), MAX_DIAGNOSTIC_DETAILS); i++) {
                summary.add(diagnostics.get(i));
            }
            if (diagnostics.size() > MAX_DIAGNOSTIC_DETAILS) {
                summary.add("... and " + (diagnostics.size() - MAX_DIAGNOSTIC_DETAILS)
                        + " more detail(s)");
            }
        }
        return summary.length() == 0 ? status : status + ": " + summary;
    }

    private static void addDiagnostic(final List<String> diagnostics, final String detail) {
        if (detail != null && !detail.isBlank()) {
            diagnostics.add(detail);
        }
    }

    private static String safeFilterReason(final SpellAbility ability,
            final Function<SpellAbility, String> candidateFilterReason) {
        if (candidateFilterReason == null) {
            return "legacy admission filter rejected action";
        }
        try {
            final String reason = candidateFilterReason.apply(ability);
            return reason == null || reason.isBlank()
                    ? "legacy admission filter rejected action" : reason;
        } catch (final RuntimeException exception) {
            return "legacy admission reason unavailable ("
                    + exception.getClass().getSimpleName() + ")";
        }
    }

    private static Selection timedOut(final int availableMana, final int evaluated) {
        Logger.warn("[AI Effect Analysis] Mana action combination timed out after evaluating {} candidates; "
                + "preserving legacy action ordering.", evaluated);
        return emptySelection(availableMana, evaluated);
    }

    /** Logs a cheap skip when the conservative resource gate makes analysis impossible. */
    public static void logSkipped(final ActionDecisionSnapshot snapshot, final String reason) {
        if (!Boolean.parseBoolean(System.getProperty(EffectAnalysisTrace.ENABLE_PROPERTY, "true"))) {
            return;
        }
        Logger.info("[AI Effect Analysis] Mana action combination skipped: reason={}, "
                        + "availableMana={}, manaSources={}, manaResourcesComplete={}",
                reason, snapshot == null ? "UNKNOWN" : snapshot.availableMana(),
                snapshot == null ? "UNKNOWN" : snapshot.manaSources().size(),
                snapshot != null && snapshot.manaResourcesComplete());
    }

    /** Logs a non-mutating comparison between the legacy first action and the global proposal. */
    public static void logShadowComparison(final SpellAbility legacyFirstAction,
            final Selection legacyPlan, final Selection proposedPlan) {
        logShadowComparison(legacyFirstAction, legacyPlan, proposedPlan, false, "UNKNOWN", 0);
    }

    /** Logs the comparison and whether the conservative active gate actually applied it. */
    public static void logShadowComparison(final SpellAbility legacyFirstAction,
            final Selection legacyPlan, final Selection proposedPlan,
            final boolean overrideApplied, final String legacyTiming,
            final int minimumAdvantage) {
        logShadowComparison(legacyFirstAction, legacyPlan, proposedPlan, overrideApplied,
                legacyTiming, minimumAdvantage, null);
    }

    /** Logs the comparison with the immutable timing/resource snapshot used for both plans. */
    public static void logShadowComparison(final SpellAbility legacyFirstAction,
            final Selection legacyPlan, final Selection proposedPlan,
            final boolean overrideApplied, final String legacyTiming,
            final int minimumAdvantage, final ActionDecisionSnapshot snapshot) {
        logShadowComparison(legacyFirstAction, legacyPlan, proposedPlan, overrideApplied,
                legacyTiming, minimumAdvantage, snapshot, null);
    }

    /** Logs a comparison using the owning chooser's explicit gate reason. */
    public static void logShadowComparison(final SpellAbility legacyFirstAction,
            final Selection legacyPlan, final Selection proposedPlan,
            final boolean overrideApplied, final String legacyTiming,
            final int minimumAdvantage, final ActionDecisionSnapshot snapshot,
            final String explicitReason) {
        logShadowComparison(legacyFirstAction, legacyPlan, proposedPlan, overrideApplied,
                legacyTiming, minimumAdvantage, snapshot, explicitReason, "not available");
    }

    /** Logs the action-level explanation collected while preparing both comparison plans. */
    public static void logShadowComparison(final SpellAbility legacyFirstAction,
            final Selection legacyPlan, final Selection proposedPlan,
            final boolean overrideApplied, final String legacyTiming,
            final int minimumAdvantage, final ActionDecisionSnapshot snapshot,
            final String explicitReason, final String comparisonDetails) {
        if (!Boolean.parseBoolean(System.getProperty(EffectAnalysisTrace.ENABLE_PROPERTY, "true"))) {
            return;
        }
        final long advantage = proposedPlan == null || legacyPlan == null
                ? Long.MIN_VALUE : (long) proposedPlan.score() - legacyPlan.score();
        final String reason = explicitReason == null ? inferredReason(legacyFirstAction, legacyPlan,
                proposedPlan, overrideApplied, advantage, minimumAdvantage) : explicitReason;
        Logger.info("[AI Effect Analysis] Mana action shadow: legacyFirst={}, legacyPlanScore={}, "
                        + "legacyPlanActions={}, proposedFirst={}, proposedScore={}, "
                        + "legacyTiming={}, "
                        + "phase={}, stackEmpty={}, availableMana={}, reservedResources={}, "
                        + "manaSources={}, manaResourcesComplete={}, "
                        + "proposedActions={}, proposedCandidates={}, proposedFallbacks={}, "
                        + "proposedFallbackActions={}, proposedIncompleteActions={}, "
                        + "proposedUncertainActions={}, advantage={}, minimumAdvantage={}, "
                        + "overrideApplied={}, reason={}, comparisonDetails={}, "
                        + "legacyActionIssues={}, proposedActionIssues={}",
                actionName(legacyFirstAction), score(legacyPlan), actionNames(legacyPlan),
                actionName(proposedPlan == null ? null : proposedPlan.firstAction()),
                score(proposedPlan), legacyTiming,
                snapshot == null ? "UNKNOWN" : snapshot.phase(),
                snapshot == null ? "UNKNOWN" : snapshot.stackEmpty(),
                snapshot == null ? "UNKNOWN" : snapshot.availableMana(),
                snapshot == null ? "UNKNOWN" : snapshot.reservedResources().size(),
                snapshot == null ? "UNKNOWN" : snapshot.manaSources().size(),
                snapshot == null ? "UNKNOWN" : snapshot.manaResourcesComplete(),
                actionNames(proposedPlan),
                proposedPlan == null ? 0 : proposedPlan.evaluatedCandidateCount(),
                proposedPlan == null ? 0 : proposedPlan.fallbackCandidateCount(),
                proposedPlan == null ? 0 : proposedPlan.fallbackActionCount(),
                proposedPlan == null ? 0 : proposedPlan.incompleteActionCount(),
                proposedPlan == null ? 0 : proposedPlan.uncertainActionCount(),
                advantage, minimumAdvantage, overrideApplied, reason,
                comparisonDetails == null ? "not available" : comparisonDetails,
                planActionDiagnostics(legacyPlan), planActionDiagnostics(proposedPlan));
    }

    private static String planActionDiagnostics(final Selection selection) {
        return selection == null ? "not available"
                : summarizeDiagnostics("none", selection.actionDiagnostics());
    }

    private static String inferredReason(final SpellAbility legacyFirstAction,
            final Selection legacyPlan, final Selection proposedPlan,
            final boolean overrideApplied, final long advantage, final int minimumAdvantage) {
        final String reason;
        if (overrideApplied) {
            reason = "advantage_exceeds_threshold";
        } else if (legacyFirstAction == null) {
            reason = "legacy_pass_or_no_admitted_action";
        } else if (proposedPlan == null || !proposedPlan.hasAction()) {
            reason = "no_complete_proposed_plan";
        } else if (proposedPlan.fallbackActionCount() > 0) {
            reason = "fallback_action_in_proposed_plan";
        } else if (proposedPlan.incompleteActionCount() > 0
                || proposedPlan.uncertainActionCount() > 0) {
            reason = "incomplete_or_uncertain_action_in_proposed_plan";
        } else if (proposedPlan.firstAction() == legacyFirstAction
                || sameHost(proposedPlan.firstAction(), legacyFirstAction)) {
            reason = "legacy_first_card_preserved";
        } else if (advantage < minimumAdvantage) {
            reason = "insufficient_advantage";
        } else {
            reason = "active_gate_not_enabled";
        }
        return reason;
    }

    private static int score(final Selection selection) {
        return selection == null || !selection.hasAction() ? Integer.MIN_VALUE : selection.score();
    }

    private static String actionName(final SpellAbility ability) {
        if (ability == null || ability.getHostCard() == null) {
            return "none";
        }
        final String mode = PlaneswalkerActivationSupport.isLoyaltyAction(ability)
                ? ", mode=" + ability.getParamOrDefault("SpellDescription", ability.getApi().name())
                : "";
        return ability.getHostCard().getName() + "(" + ability.getApi() + mode + ")";
    }

    private static boolean sameHost(final SpellAbility first, final SpellAbility second) {
        return first != null && second != null && first.getHostCard() == second.getHostCard();
    }

    private static String actionNames(final Selection selection) {
        if (selection == null || selection.actions().isEmpty()) {
            return "none";
        }
        final StringJoiner actions = new StringJoiner(", ");
        for (final SpellAbility ability : selection.actions()) {
            actions.add(actionName(ability));
        }
        return actions.toString();
    }

    private static int actionSelectionTimeoutMillis(final Player ai) {
        try {
            return Math.max(1, AiProfileUtil.getIntProperty(ai,
                    AiProps.ACTION_COMBINATION_VALUE_SELECTION_TIMEOUT_MS));
        } catch (final RuntimeException invalidProfileValue) {
            return 100;
        }
    }

    public static void logSelection(final Selection selection) {
        if (!Boolean.parseBoolean(System.getProperty(EffectAnalysisTrace.ENABLE_PROPERTY, "true"))
                || !selection.hasAction()) {
            return;
        }
        final StringJoiner actions = new StringJoiner(", ");
        for (final SpellAbility ability : selection.actions()) {
            actions.add(ability.getHostCard().getName() + "(" + ability.getApi() + ")");
        }
        Logger.info("[AI Effect Analysis] Mana action combination: availableMana={}, usedMana={}, "
                        + "unusedMana={}, score={}, handPressure={}, candidates={}, "
                        + "fallbackCandidates={}, fallbackActions={}, incompleteActions={}, "
                        + "uncertainActions={}, first={}, actions={}, actionIssues={}",
                selection.availableMana(), selection.usedMana(), selection.unusedMana(),
                selection.score(), selection.handPressureBonus(), selection.evaluatedCandidateCount(),
                selection.fallbackCandidateCount(), selection.fallbackActionCount(),
                selection.incompleteActionCount(), selection.uncertainActionCount(),
                selection.firstAction().getHostCard().getName(), actions,
                planActionDiagnostics(selection));
    }

    private static void traceLoyaltyCandidate(final SpellAbility ability, final Player ai,
            final String status, final String reason) {
        if (!Boolean.parseBoolean(System.getProperty(EffectAnalysisTrace.ENABLE_PROPERTY, "true"))) {
            return;
        }
        final ActionCostAnalysis cost = ActionCostSupport.analyze(ability, ai);
        final int loyaltyValue = PlaneswalkerLoyaltyValue.abilityBenefit(ai, ability);
        final String abilityCost = ability.getPayCosts() == null
                ? "NONE" : ability.getPayCosts().toString();
        Logger.info("[AI Effect Analysis] Planeswalker loyalty candidate: source={}, mode={}, "
                        + "cost={}, status={}, loyaltyValue={}, signedExplicitCost={}, reason={}",
                ability.getHostCard().getName(),
                ability.getParamOrDefault("SpellDescription", ability.getApi().name()),
                abilityCost, status, loyaltyValue,
                cost.explicitValue(), reason);
    }

    private static boolean isManaFeasible(final State state,
            final ActionDecisionSnapshot snapshot, final ManaSourceModel manaSourceModel) {
        if (!snapshot.manaResourcesComplete()) {
            return false;
        }
        final List<Integer> requirements = new ArrayList<>();
        final List<ActionResourceRequirement> resourceRequirements = new ArrayList<>();
        final IdentityHashMap<Card, Boolean> fixedUnavailableResources = new IdentityHashMap<>();
        final IdentityHashMap<Card, Boolean> unavailableManaSources = new IdentityHashMap<>();
        final List<State> actionsInOrder = new ArrayList<>(state.actionCount());
        for (State action = state; action != null && action.candidate() != null;
                action = action.parent()) {
            actionsInOrder.add(action);
        }
        Collections.reverse(actionsInOrder);
        for (final State action : actionsInOrder) {
            final Candidate candidate = action.candidate();
            final List<Integer> actionRequirements = manaRequirements(candidate.manaCostDetails(),
                    candidate.xValue());
            if (actionRequirements == null) {
                return false;
            }
            requirements.addAll(actionRequirements);
            final ActionResourceFootprint resources = candidate.estimate().resources();
            for (final Card resource : resources.consumedCards()) {
                fixedUnavailableResources.put(resource, Boolean.TRUE);
                unavailableManaSources.put(resource, Boolean.TRUE);
            }
            for (final Card resource : resources.tappedSources()) {
                fixedUnavailableResources.put(resource, Boolean.TRUE);
                unavailableManaSources.put(resource, Boolean.TRUE);
            }
            resourceRequirements.addAll(resources.requirements());
            // If an additional cost could consume a mana source, do not optimistically assign
            // that source to mana. This boundary prefers a false negative to an impossible
            // projected payment; a later refinement can search both assignments.
            for (final ActionResourceRequirement resourceRequirement : resources.requirements()) {
                for (final ActionResourceRequirement.ResourceCandidate resource
                        : resourceRequirement.candidates()) {
                    unavailableManaSources.put(resource.card(), Boolean.TRUE);
                }
            }
        }
        if (!resourceRequirementsFeasible(resourceRequirements, fixedUnavailableResources)) {
            return false;
        }
        if (requirements.isEmpty()) {
            return true;
        }
        // Colored requirements are the constrained part of the search.  Assign them before
        // generic requirements so a source that produces only one color is not consumed too soon.
        Collections.sort(requirements, (first, second) -> Boolean.compare(
                isGenericRequirement(first), isGenericRequirement(second)));
        if (manaSourceModel.independentSingleOutputSources()) {
            return assignWithBipartiteMatching(requirements, manaSourceModel.sources(),
                    unavailableManaSources);
        }
        final boolean[][] usedOutputs = new boolean[manaSourceModel.sources().size()][];
        for (int i = 0; i < manaSourceModel.sources().size(); i++) {
            usedOutputs[i] = new boolean[manaSourceModel.sources().get(i).outputMasks().size()];
        }
        final int[][] lastTriedAtFrame = new int[requirements.size()]
                [manaSourceModel.symmetryGroups().count()];
        return assignManaRequirements(0, requirements, manaSourceModel.sources(), usedOutputs,
                new IdentityHashMap<>(), unavailableManaSources,
                manaSourceModel.symmetryGroups().bySource(),
                lastTriedAtFrame, new SearchBudget());
    }

    private static ManaSourceModel manaSourceModel(final ActionDecisionSnapshot snapshot) {
        final List<ActionManaSource> sources = snapshot == null
                ? List.of() : snapshot.manaSources();
        final boolean independentSingleOutputSources =
                hasIndependentSingleOutputSources(sources);
        final SourceSymmetryGroups symmetryGroups = independentSingleOutputSources
                ? new SourceSymmetryGroups(new int[sources.size()], 0)
                : sourceSymmetryGroups(sources);
        return new ManaSourceModel(sources, independentSingleOutputSources, symmetryGroups);
    }

    /**
     * Ordinary one-output sources form a bipartite assignment problem. Detect that common case so
     * the checker can use polynomial matching instead of exploring source permutations.
     */
    private static boolean hasIndependentSingleOutputSources(
            final List<ActionManaSource> sources) {
        final IdentityHashMap<Card, Integer> sourceCounts = new IdentityHashMap<>();
        for (final ActionManaSource source : sources) {
            if (!source.uncertain() && source.source() != null) {
                sourceCounts.put(source.source(), sourceCounts.getOrDefault(source.source(), 0) + 1);
            }
        }
        for (final ActionManaSource source : sources) {
            if (source.uncertain() || source.outputMasks().isEmpty()) {
                continue;
            }
            if (source.outputMasks().size() != 1
                    || source.source() != null && sourceCounts.get(source.source()) != 1) {
                return false;
            }
        }
        return true;
    }

    private static boolean assignWithBipartiteMatching(
            final List<Integer> requirements, final List<ActionManaSource> sources,
            final IdentityHashMap<Card, Boolean> unavailableManaSources) {
        final int[] matchedRequirementBySource = new int[sources.size()];
        java.util.Arrays.fill(matchedRequirementBySource, -1);
        final SearchBudget searchBudget = new SearchBudget();
        for (int requirementIndex = 0; requirementIndex < requirements.size(); requirementIndex++) {
            final boolean[] visitedSources = new boolean[sources.size()];
            if (!assignRequirement(requirementIndex, requirements, sources,
                    unavailableManaSources, matchedRequirementBySource, visitedSources,
                    searchBudget)) {
                return false;
            }
        }
        return true;
    }

    private static boolean assignRequirement(final int requirementIndex,
            final List<Integer> requirements, final List<ActionManaSource> sources,
            final IdentityHashMap<Card, Boolean> unavailableManaSources,
            final int[] matchedRequirementBySource, final boolean[] visitedSources,
            final SearchBudget searchBudget) {
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            if (!searchBudget.visit()) {
                return false;
            }
            final ActionManaSource source = sources.get(sourceIndex);
            if (visitedSources[sourceIndex] || source.uncertain()
                    || source.outputMasks().isEmpty()
                    || source.source() != null
                            && unavailableManaSources.containsKey(source.source())
                    || !canPayRequirement(source.outputMasks().get(0),
                            requirements.get(requirementIndex))) {
                continue;
            }
            visitedSources[sourceIndex] = true;
            final int previousRequirement = matchedRequirementBySource[sourceIndex];
            if (previousRequirement < 0 || assignRequirement(previousRequirement, requirements,
                    sources, unavailableManaSources, matchedRequirementBySource, visitedSources,
                    searchBudget)) {
                matchedRequirementBySource[sourceIndex] = requirementIndex;
                return true;
            }
        }
        return false;
    }

    /**
     * Identifies independent, single-output sources that are interchangeable for this check.
     * Sources with multiple abilities on one card are deliberately excluded because choosing one
     * activation prevents choosing another activation of that same permanent.
     */
    private static SourceSymmetryGroups sourceSymmetryGroups(
            final List<ActionManaSource> sources) {
        final IdentityHashMap<Card, Integer> sourceCounts = new IdentityHashMap<>();
        for (final ActionManaSource source : sources) {
            if (source.source() != null) {
                sourceCounts.put(source.source(), sourceCounts.getOrDefault(source.source(), 0) + 1);
            }
        }

        final int[] bySource = new int[sources.size()];
        java.util.Arrays.fill(bySource, -1);
        final Map<Integer, Integer> groupsByOutput = new HashMap<>();
        int nextGroup = 0;
        for (int i = 0; i < sources.size(); i++) {
            final ActionManaSource source = sources.get(i);
            if (source.uncertain() || source.outputMasks().size() != 1
                    || source.outputMasks().get(0) == 0
                    || source.source() != null && sourceCounts.get(source.source()) != 1) {
                continue;
            }
            final int output = source.outputMasks().get(0);
            Integer group = groupsByOutput.get(output);
            if (group == null) {
                group = nextGroup++;
                groupsByOutput.put(output, group);
            }
            bySource[i] = group;
        }
        return new SourceSymmetryGroups(bySource, nextGroup);
    }

    private static boolean resourceRequirementsFeasible(
            final List<ActionResourceRequirement> requirements,
            final IdentityHashMap<Card, Boolean> fixedUnavailable) {
        if (requirements.isEmpty()) {
            return true;
        }
        final List<ActionResourceRequirement> ordered = new ArrayList<>(requirements);
        ordered.sort((left, right) -> Integer.compare(left.candidates().size(),
                right.candidates().size()));
        final IdentityHashMap<Card, Integer> remaining = new IdentityHashMap<>();
        for (final ActionResourceRequirement requirement : ordered) {
            if (requirement.uncertain() || !requirement.isAvailable()) {
                return false;
            }
            for (final ActionResourceRequirement.ResourceCandidate candidate
                    : requirement.candidates()) {
                if (fixedUnavailable.containsKey(candidate.card())) {
                    continue;
                }
                final Integer previous = remaining.get(candidate.card());
                remaining.put(candidate.card(), previous == null
                        ? candidate.capacity() : Math.max(previous, candidate.capacity()));
            }
        }
        return assignResourceRequirements(0, ordered, remaining, fixedUnavailable);
    }

    private static boolean assignResourceRequirements(final int index,
            final List<ActionResourceRequirement> requirements,
            final IdentityHashMap<Card, Integer> remaining,
            final IdentityHashMap<Card, Boolean> fixedUnavailable) {
        if (index >= requirements.size()) {
            return true;
        }
        final ActionResourceRequirement requirement = requirements.get(index);
        if (requirement.sameObject()) {
            for (final ActionResourceRequirement.ResourceCandidate candidate
                    : requirement.candidates()) {
                if (fixedUnavailable.containsKey(candidate.card())) {
                    continue;
                }
                final int available = remaining.getOrDefault(candidate.card(), 0);
                if (available < requirement.amount()) {
                    continue;
                }
                remaining.put(candidate.card(), available - requirement.amount());
                if (assignResourceRequirements(index + 1, requirements, remaining,
                        fixedUnavailable)) {
                    return true;
                }
                remaining.put(candidate.card(), available);
            }
            return false;
        }
        return assignDistinctResourceUnits(0, index, requirements, remaining, fixedUnavailable);
    }

    private static boolean assignDistinctResourceUnits(final int unit,
            final int requirementIndex, final List<ActionResourceRequirement> requirements,
            final IdentityHashMap<Card, Integer> remaining,
            final IdentityHashMap<Card, Boolean> fixedUnavailable) {
        final ActionResourceRequirement requirement = requirements.get(requirementIndex);
        if (unit >= requirement.amount()) {
            return assignResourceRequirements(requirementIndex + 1, requirements, remaining,
                    fixedUnavailable);
        }
        for (final ActionResourceRequirement.ResourceCandidate candidate
                : requirement.candidates()) {
            if (fixedUnavailable.containsKey(candidate.card())) {
                continue;
            }
            final int available = remaining.getOrDefault(candidate.card(), 0);
            if (available <= 0) {
                continue;
            }
            remaining.put(candidate.card(), available - 1);
            if (assignDistinctResourceUnits(unit + 1, requirementIndex, requirements, remaining,
                    fixedUnavailable)) {
                return true;
            }
            remaining.put(candidate.card(), available);
        }
        return false;
    }

    private static List<Integer> manaRequirements(final ManaCost cost, final int xValue) {
        if (cost == null || cost.getGenericCost() < 0 || xValue < 0) {
            return null;
        }
        final List<Integer> requirements = new ArrayList<>();
        for (int i = 0; i < cost.getGenericCost(); i++) {
            requirements.add(GENERIC_REQUIREMENT);
        }
        for (int i = 0; i < cost.countX(); i++) {
            for (int j = 0; j < xValue; j++) {
                requirements.add(GENERIC_REQUIREMENT);
            }
        }
        for (final ManaCostShard shard : cost) {
            if (shard == ManaCostShard.X) {
                continue;
            }
            if (shard.isPhyrexian() || shard.isSnow() || shard.isOr2Generic()
                    || !shard.isMonoColor() && !shard.isColorless()) {
                // Hybrid, snow, phyrexian, and other choice-based shards need the same selected
                // payment mode as execution before they can participate in a combination.
                return null;
            }
            requirements.add((int) shard.getColorMask());
        }
        return requirements;
    }

    private static boolean assignManaRequirements(final int requirementIndex,
            final List<Integer> requirements, final List<ActionManaSource> sources,
            final boolean[][] usedOutputs, final IdentityHashMap<Card, Integer> chosenSources,
            final IdentityHashMap<Card, Boolean> unavailableManaSources,
            final int[] symmetryGroupBySource, final int[][] lastTriedAtFrame,
            final SearchBudget searchBudget) {
        if (requirementIndex >= requirements.size()) {
            return true;
        }
        if (!searchBudget.visit()) {
            return false;
        }
        final int frame = searchBudget.nextFrame();
        final int requirement = requirements.get(requirementIndex);
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            final ActionManaSource source = sources.get(sourceIndex);
            if (source.uncertain() || source.outputMasks().isEmpty()) {
                continue;
            }
            final Card sourceCard = source.source();
            if (sourceCard != null && unavailableManaSources.containsKey(sourceCard)) {
                continue;
            }
            final Integer chosenSource = sourceCard == null ? null : chosenSources.get(sourceCard);
            if (chosenSource != null && chosenSource != sourceIndex) {
                continue;
            }
            final int symmetryGroup = symmetryGroupBySource[sourceIndex];
            if (symmetryGroup >= 0
                    && lastTriedAtFrame[requirementIndex][symmetryGroup] == frame) {
                continue;
            }
            boolean canUseOutput = false;
            for (int outputIndex = 0; outputIndex < source.outputMasks().size(); outputIndex++) {
                if (!usedOutputs[sourceIndex][outputIndex]
                        && canPayRequirement(source.outputMasks().get(outputIndex), requirement)) {
                    canUseOutput = true;
                    break;
                }
            }
            if (!canUseOutput) {
                continue;
            }
            if (symmetryGroup >= 0) {
                lastTriedAtFrame[requirementIndex][symmetryGroup] = frame;
            }
            final boolean newlyChosen = sourceCard != null && chosenSource == null;
            if (newlyChosen) {
                chosenSources.put(sourceCard, sourceIndex);
            }
            for (int outputIndex = 0; outputIndex < source.outputMasks().size(); outputIndex++) {
                if (usedOutputs[sourceIndex][outputIndex]
                        || !canPayRequirement(source.outputMasks().get(outputIndex), requirement)) {
                    continue;
                }
                usedOutputs[sourceIndex][outputIndex] = true;
                if (assignManaRequirements(requirementIndex + 1, requirements, sources,
                        usedOutputs, chosenSources, unavailableManaSources, symmetryGroupBySource,
                        lastTriedAtFrame, searchBudget)) {
                    return true;
                }
                usedOutputs[sourceIndex][outputIndex] = false;
            }
            if (newlyChosen) {
                chosenSources.remove(sourceCard);
            }
        }
        return false;
    }

    private static boolean canPayRequirement(final int outputMask, final int requirement) {
        return isGenericRequirement(requirement)
                ? outputMask != 0
                : (outputMask & requirement) != 0;
    }

    private static boolean isGenericRequirement(final int requirement) {
        return requirement == GENERIC_REQUIREMENT;
    }

    private static final int GENERIC_REQUIREMENT = -1;
    private static final int MAX_MANA_SEARCH_NODES = 10_000;

    private static final class SearchBudget {
        private int nodes;
        private int frames;

        private boolean visit() {
            return ++nodes <= MAX_MANA_SEARCH_NODES;
        }

        private int nextFrame() {
            return ++frames;
        }
    }

    private record SourceSymmetryGroups(int[] bySource, int count) {
    }

    private record PreparedCandidates(List<Candidate> candidates,
            IdentityHashMap<SpellAbility, Candidate> candidatesByAbility,
            List<List<Candidate>> groups, ManaSourceModel manaSourceModel,
            int availableMana, int handOverflow,
            int handPressureValue, int fallbackCandidateCount,
            List<String> candidateDiagnostics) {
    }

    private record CandidateBuildResult(Candidate candidate, String rejectionReason) {
        private static CandidateBuildResult accepted(final Candidate candidate) {
            return new CandidateBuildResult(candidate, null);
        }

        private static CandidateBuildResult rejected(final String reason) {
            return new CandidateBuildResult(null, reason);
        }
    }

    private record AlternativeAdmission(int alternativeCount, int admittedCount,
            List<String> diagnostics) {
    }

    private record ManaSourceModel(List<ActionManaSource> sources,
            boolean independentSingleOutputSources, SourceSymmetryGroups symmetryGroups) {
    }

    private record Candidate(SpellAbility ability, int manaCost, ManaCost manaCostDetails,
            int xValue, boolean cast, int maxUses, ActionSelectionEstimate estimate) {
        private int value() {
            return estimate.netBenefit();
        }

        private boolean fallback() {
            return estimate.usedFallback();
        }

        private int valueForUse(final int useNumber) {
            final int value = value();
            if (useNumber <= 1 || cast || maxUses <= 1) {
                return value;
            }
            // A later use is still useful, but an independent estimate cannot know how much the
            // board, hand, or target availability changed after the prior resolution.
            final int divisor = Math.max(1, maxUses);
            return value >= 0 ? (int) ((long) value * (divisor - useNumber + 1) / divisor)
                    : value;
        }
    }

    private record State(int value, int usedMana, int castCount, State parent,
            Candidate candidate, int actionCount) {
        private State add(final Candidate candidate, final int newCastCount,
            final int useNumber) {
            return new State(saturatedAdd(value, candidate.valueForUse(useNumber)),
                    usedMana + candidate.manaCost(), newCastCount, this, candidate,
                    actionCount + 1);
        }

        private List<SpellAbility> toActions() {
            final List<SpellAbility> actions = new ArrayList<>(actionCount);
            State current = this;
            while (current != null && current.candidate() != null) {
                actions.add(current.candidate().ability());
                current = current.parent();
            }
            Collections.reverse(actions);
            return actions;
        }
    }
}
