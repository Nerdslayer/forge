package forge.ai.effect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
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

    private ManaActionCombinationSelector() {
    }

    /** Result retained for focused callers and diagnostics. */
    public record Selection(SpellAbility firstAction, List<SpellAbility> actions,
            int availableMana, int usedMana, int unusedMana, int score,
            int handPressureBonus, int evaluatedCandidateCount, int fallbackCandidateCount,
            int fallbackActionCount, int incompleteActionCount, int uncertainActionCount) {
        public Selection {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }

        public boolean hasAction() {
            return firstAction != null;
        }
    }

    /** Results of comparing one proposed plan with the plan anchored to the legacy first action. */
    public record Comparison(Selection proposed, Selection legacy) {
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
        final int availableMana = snapshot == null ? 0 : snapshot.availableMana();
        if (snapshot != null && !snapshot.manaResourcesComplete()) {
            logSkipped(snapshot, "mana_source_model_incomplete");
            final Selection empty = emptySelection(availableMana, 0);
            return new Comparison(empty, empty);
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
                final Selection empty = emptySelection(availableMana, 0);
                return new Comparison(empty, empty);
            }
            if (!hasAlternativeLegacyAction(abilities, requiredFirstAction, cachedCandidateFilter,
                    budget)) {
                logSkipped(snapshot, "no_alternative_admitted_action");
                final Selection empty = emptySelection(availableMana, 0);
                return new Comparison(empty, empty);
            }
            prepared = prepareCandidates(ai, abilities, skipCounter, snapshot,
                    cachedCandidateFilter,
                    budget);
            final Selection proposed = selectPrepared(prepared, null, snapshot, budget);
            final Selection legacy = selectPrepared(prepared, requiredFirstAction, snapshot, budget);
            return new Comparison(proposed, legacy);
        } catch (final EffectEvaluationBudget.Exceeded exceeded) {
            final int timeoutMana = prepared == null ? availableMana : prepared.availableMana();
            final int evaluated = prepared == null ? 0 : prepared.candidates().size();
            final Selection timeout = timedOut(timeoutMana, evaluated);
            return new Comparison(timeout, timeout);
        } catch (final RuntimeException ignored) {
            return new Comparison(emptySelection(availableMana, 0),
                    emptySelection(availableMana, 0));
        }
    }

    private static boolean hasAlternativeLegacyAction(final List<SpellAbility> abilities,
            final SpellAbility requiredFirstAction,
            final Predicate<SpellAbility> candidateFilter,
            final EffectEvaluationBudget budget) {
        if (abilities == null || requiredFirstAction == null
                || requiredFirstAction.getHostCard() == null) {
            return false;
        }
        for (final SpellAbility ability : abilities) {
            budget.check();
            if (ability == null || ability == requiredFirstAction || ability.getHostCard() == null
                    || ability.getHostCard() == requiredFirstAction.getHostCard()) {
                continue;
            }
            if (candidateFilter == null) {
                return true;
            }
            try {
                if (candidateFilter.test(ability)) {
                    return true;
                }
            } catch (final RuntimeException ignored) {
                // A failed legacy probe must not suppress the existing chooser.
            }
        }
        return false;
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
                    snapshot, candidateFilter, budget);
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
            final EffectEvaluationBudget budget) {
        if (ai == null || abilities == null || abilities.isEmpty()) {
            return new PreparedCandidates(List.of(), new IdentityHashMap<>(), List.of(),
                    manaSourceModel(snapshot), 0, 0, 0, 0);
        }

        budget.check();
        final int availableMana = snapshot == null ? Math.max(0,
                ComputerUtilMana.getAvailableManaEstimate(ai, true)) : snapshot.availableMana();
        budget.check();
        final List<Candidate> candidates = new ArrayList<>();
        final IdentityHashMap<SpellAbility, Candidate> candidatesByAbility =
                new IdentityHashMap<>();
        for (final SpellAbility ability : abilities) {
            budget.check();
            if (Thread.currentThread().isInterrupted()) {
                return new PreparedCandidates(List.of(), new IdentityHashMap<>(), List.of(),
                        manaSourceModel(snapshot), availableMana, 0, 0, 0);
            }
            if (candidateFilter != null) {
                try {
                    if (!candidateFilter.test(ability)) {
                        continue;
                    }
                } catch (final RuntimeException ignored) {
                    // A failed legacy probe must not suppress the existing chooser.
                    continue;
                }
            }
            try {
                final Candidate candidate = createCandidate(ai, ability, availableMana, skipCounter,
                        budget);
                if (candidate != null) {
                    candidates.add(candidate);
                    candidatesByAbility.putIfAbsent(ability, candidate);
                }
            } catch (final EffectEvaluationBudget.Exceeded exceeded) {
                throw exceeded;
            } catch (final RuntimeException ignored) {
                // One unusual card or ability must not suppress the legacy action chooser.
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
                handOverflow, handPressureValue, fallbackCandidateCount);
    }

    private static Selection selectPrepared(final PreparedCandidates prepared,
            final SpellAbility requiredFirstAction, final ActionDecisionSnapshot snapshot,
            final EffectEvaluationBudget budget) {
        final List<Candidate> candidates = prepared.candidates();
        if (candidates.isEmpty()) {
            return emptySelection(prepared.availableMana(), 0);
        }

        budget.check();
        final State best;
        final Candidate required = prepared.candidatesByAbility().get(requiredFirstAction);
        if (requiredFirstAction != null && required == null) {
            return emptySelection(prepared.availableMana(), candidates.size());
        }
        best = solve(prepared.groups(), required,
                prepared.availableMana(), prepared.handOverflow(), snapshot,
                prepared.manaSourceModel(), budget);
        if (best == null || best.actionCount() == 0) {
            return new Selection(null, List.of(), prepared.availableMana(), 0,
                    prepared.availableMana(), -prepared.availableMana() * UNUSED_MANA_PENALTY,
                    0, candidates.size(), prepared.fallbackCandidateCount(), 0, 0, 0);
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
        for (final SpellAbility action : actions) {
            final Candidate candidate = prepared.candidatesByAbility().get(action);
            if (candidate == null) {
                continue;
            }
            if (candidate.fallback()) {
                fallbackActionCount++;
            }
            if (!candidate.estimate().canEstablishOverride()) {
                incompleteActionCount++;
            }
            if (candidate.estimate().resources().uncertain()) {
                uncertainActionCount++;
            }
        }
        return new Selection(firstAction, actions, prepared.availableMana(),
                best.usedMana(), unusedMana, score, pressure, candidates.size(),
                prepared.fallbackCandidateCount(), fallbackActionCount, incompleteActionCount,
                uncertainActionCount);
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

    private static Candidate createCandidate(final Player ai, final SpellAbility ability,
            final int availableMana, final boolean skipCounter,
            final EffectEvaluationBudget budget) {
        if (ability == null || ability.getHostCard() == null || ability.getPayCosts() == null
                || ability.getPayCosts().getTotalMana() == null
                || skipCounter && ability.getApi() == ApiType.Counter
                || ability.isLandAbility() || ability.isManaAbility()) {
            return null;
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
                return null;
            }
            manaCost = announcedManaCost(probe);
            if (manaCost > availableMana) {
                return null;
            }
            if (host.isLand() || !probe.canCastTiming(ai)
                    || !ComputerUtilCost.canPayCost(probe, ai, probe.isTrigger())) {
                return null;
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
                return null;
            }
            activationCost = SituationalAbilityOccurrenceContext.supportedActivationCost(
                    probe.getPayCosts(), probe).orElse(null);
            if (activationCost == null) {
                return null;
            }
            manaCost = activationCost.manaCost();
            if (manaCost > availableMana) {
                return null;
            }
            if (!ComputerUtilCost.canPayCost(probe, ai, false)) {
                return null;
            }
            action = new ActivateValuationAction(host, probe);
            context = ValuationContext.forActivation(ai, true);
            cast = false;
            costAbility = probe;
        } else {
            return null;
        }

        final ActionCostAnalysis costAnalysis = ActionCostSupport.analyze(costAbility, ai);
        budget.check();
        final CardValueBreakdown value = UnifiedActionValueEvaluator.evaluate(action, context,
                EffectAnalysisTrace.disabled(), budget);
        budget.check();
        final ActionValueFallbackEvaluator.Estimate fallback;
        if (value.isComplete()) {
            fallback = null;
        } else if (value.completeness() == ValuationCompleteness.UNAVAILABLE) {
            // A legal-looking action can still be unavailable because its current target or
            // state-dependent outcome cannot occur. Do not turn that into a generic action.
            return null;
        } else if (cast) {
            fallback = ActionValueFallbackEvaluator.cast(manaCost);
        } else {
            fallback = ActionValueFallbackEvaluator.activation(ai, manaCost,
                    activationCost == null ? 0 : activationCost.lifeCost());
        }
        final int maxUses = maximumUses(ability, activationCost, cast, manaCost, availableMana);
        final int explicitNonManaCost = fallback == null ? costAnalysis.explicitValue() : 0;
        final ActionResourceFootprint resources = costAnalysis.supported()
                ? costAnalysis.resources()
                : uncertainResources(host, cast);
        final ActionSelectionEstimate estimate = new ActionSelectionEstimate(
                fallback == null ? value.grossValue() : fallback.value(), explicitNonManaCost,
                resources, fallback == null ? value.completeness() : ValuationCompleteness.PARTIAL,
                fallback != null, fallback == null ? value.reasons()
                        : List.of(fallback.reason(), "Additional cost model: "
                                + String.join(", ", costAnalysis.reasons())));
        return new Candidate(ability, manaCost, ability.getPayCosts().getTotalMana(), xValue, cast,
                maxUses, estimate);
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
        if (cast || activationCost == null || activationCost.hasTapCost()
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
        final List<List<Candidate>> groups = new ArrayList<>();
        for (final Candidate candidate : candidates) {
            final ActionResourceFootprint resources = candidate.estimate().resources();
            final List<Card> exclusiveResources = new ArrayList<>(resources.consumedCards());
            exclusiveResources.addAll(resources.tappedSources());
            if (exclusiveResources.isEmpty()) {
                groups.add(List.of(candidate));
                continue;
            }

            // A cast consumes its card, while a tap activation consumes the source's untapped
            // state. Merge groups when one action has more than one fixed resource so alternative
            // modes cannot be split across separate optimizer groups.
            final List<List<Candidate>> linkedGroups = new ArrayList<>();
            for (final Card exclusiveResource : exclusiveResources) {
                final List<Candidate> group = exclusiveResourceGroups.get(exclusiveResource);
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
                    for (final Card resource : exclusiveResources) {
                        if (exclusiveResourceGroups.get(resource) == merged) {
                            exclusiveResourceGroups.put(resource, group);
                        }
                    }
                }
            }
            group.add(candidate);
            for (final Card exclusiveResource : exclusiveResources) {
                exclusiveResourceGroups.put(exclusiveResource, group);
            }
        }
        return groups;
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
        return new Selection(null, List.of(), availableMana, 0, availableMana,
                -availableMana * UNUSED_MANA_PENALTY, 0, evaluated, 0, 0, 0, 0);
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
                        + "overrideApplied={}, reason={}",
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
                advantage, minimumAdvantage, overrideApplied, reason);
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
        return ability == null || ability.getHostCard() == null ? "none"
                : ability.getHostCard().getName() + "(" + ability.getApi() + ")";
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
                        + "uncertainActions={}, first={}, actions={}",
                selection.availableMana(), selection.usedMana(), selection.unusedMana(),
                selection.score(), selection.handPressureBonus(), selection.evaluatedCandidateCount(),
                selection.fallbackCandidateCount(), selection.fallbackActionCount(),
                selection.incompleteActionCount(), selection.uncertainActionCount(),
                selection.firstAction().getHostCard().getName(), actions);
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
            int handPressureValue, int fallbackCandidateCount) {
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
