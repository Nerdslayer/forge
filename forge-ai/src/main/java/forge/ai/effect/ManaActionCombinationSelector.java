package forge.ai.effect;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.StringJoiner;

import org.tinylog.Logger;

import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.CardResourceValueEvaluator;
import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerResourceValueEvaluator;
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
 * changes, colored-mana-source conflicts, or an action changing the value or legality of a later
 * action. The next chooser invocation will reevaluate the live state after each action.</p>
 */
public final class ManaActionCombinationSelector {
    private static final int UNUSED_MANA_PENALTY = Math.max(1,
            CardResourceValueEvaluator.MANA_VALUE / 5);

    private ManaActionCombinationSelector() {
    }

    /** Result retained for focused callers and diagnostics. */
    public record Selection(SpellAbility firstAction, List<SpellAbility> actions,
            int availableMana, int usedMana, int unusedMana, int score,
            int handPressureBonus, int evaluatedCandidateCount, int fallbackCandidateCount) {
        public Selection {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }

        public boolean hasAction() {
            return firstAction != null;
        }
    }

    /**
     * Reorders {@code abilities} so that the first action in the best supported combination is
     * first. The original ordering is preserved if no complete action can be evaluated.
     */
    public static Selection apply(final Player ai, final List<SpellAbility> abilities,
            final boolean skipCounter) {
        final Selection selection = select(ai, abilities, skipCounter);
        if (!selection.hasAction() || abilities == null) {
            return selection;
        }

        final int index = abilities.indexOf(selection.firstAction());
        if (index > 0) {
            abilities.remove(index);
            abilities.add(0, selection.firstAction());
        }
        logSelection(selection);
        return selection;
    }

    /** Evaluates all currently usable cast and activation candidates without mutating the list. */
    public static Selection select(final Player ai, final List<SpellAbility> abilities,
            final boolean skipCounter) {
        if (ai == null || abilities == null || abilities.isEmpty()) {
            return emptySelection(0, 0);
        }

        final EffectEvaluationBudget budget = EffectEvaluationBudget.fromTimeoutMillis(
                actionSelectionTimeoutMillis(ai));
        final int availableMana;
        try {
            budget.check();
            availableMana = Math.max(0,
                    ComputerUtilMana.getAvailableManaEstimate(ai, true));
            budget.check();
        } catch (final EffectEvaluationBudget.Exceeded exceeded) {
            return timedOut(0, 0);
        } catch (final RuntimeException ignored) {
            // The existing chooser remains the safe fallback if mana estimation is unavailable.
            return emptySelection(0, 0);
        }
        final List<Candidate> candidates = new ArrayList<>();
        for (final SpellAbility ability : abilities) {
            try {
                budget.check();
            } catch (final EffectEvaluationBudget.Exceeded exceeded) {
                return timedOut(availableMana, candidates.size());
            }
            if (Thread.currentThread().isInterrupted()) {
                return emptySelection(availableMana, candidates.size());
            }
            try {
                final Candidate candidate = createCandidate(ai, ability, availableMana, skipCounter,
                        budget);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            } catch (final EffectEvaluationBudget.Exceeded exceeded) {
                return timedOut(availableMana, candidates.size());
            } catch (final RuntimeException ignored) {
                // One unusual card or ability must not suppress the legacy action chooser.
            }
        }
        if (candidates.isEmpty()) {
            return emptySelection(availableMana, 0);
        }

        final int handOverflow = ai.isUnlimitedHandSize() ? 0
                : Math.max(0, ai.getCardsIn(ZoneType.Hand).size() - ai.getMaxHandSize());
        final int handPressureValue = handOverflow == 0 ? 0
                : CardResourceValueEvaluator.evaluateNextCard(ai.getMaxHandSize());
        try {
            budget.check();
        } catch (final EffectEvaluationBudget.Exceeded exceeded) {
            return timedOut(availableMana, candidates.size());
        }
        final State best;
        try {
            best = solve(candidates, availableMana, handOverflow, budget);
        } catch (final EffectEvaluationBudget.Exceeded exceeded) {
            return timedOut(availableMana, candidates.size());
        }
        final int fallbackCandidateCount = (int) candidates.stream()
                .filter(Candidate::fallback).count();
        if (best == null || best.actions().isEmpty()) {
            return new Selection(null, List.of(), availableMana, 0, availableMana,
                    -availableMana * UNUSED_MANA_PENALTY, 0, candidates.size(),
                    fallbackCandidateCount);
        }

        final int pressure = handPressureValue * Math.min(best.castCount(), handOverflow);
        final int unusedMana = availableMana - best.usedMana();
        final int score = saturatedAdd(best.value(), pressure,
                -unusedMana * UNUSED_MANA_PENALTY);
        return new Selection(best.actions().get(0), best.actions(), availableMana,
                best.usedMana(), unusedMana, score, pressure, candidates.size(),
                fallbackCandidateCount);
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
        if (ability.getPayCosts().getTotalMana().countX() > 0) {
            // TODO: Evaluate X costs with the same chosen X value that the final AI action uses.
            return null;
        }

        final Card host = ability.getHostCard();
        final int manaCost = Math.max(0, ability.getPayCosts().getTotalMana().getCMC());
        if (manaCost > availableMana) {
            return null;
        }

        final ValuationAction action;
        final ValuationContext context;
        final boolean cast;
        final SituationalAbilityOccurrenceContext.SupportedActivationCost activationCost;
        if (ability.isSpell() && host.isInZone(ZoneType.Hand)
                && (host.getOwner() == ai || host.getController() == ai)) {
            final SpellAbility probe = ability.copy(host, ai, false);
            probe.setActivatingPlayer(ai);
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
            activationCost = SituationalAbilityOccurrenceContext.supportedActivationCost(
                    ability.getPayCosts()).orElse(null);
            if (activationCost == null) {
                return null;
            }
            final SpellAbility probe = ability.copy(host, false);
            probe.setActivatingPlayer(ai);
            if (!ComputerUtilCost.canPayCost(probe, ai, false)) {
                return null;
            }
            action = new ActivateValuationAction(host, probe);
            context = ValuationContext.forActivation(ai, true);
            cast = false;
        } else {
            return null;
        }

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
                    activationCost.lifeCost());
        }
        final boolean tapCost = ability.getPayCosts().hasTapCost();
        final int maxUses = cast || tapCost || manaCost == 0
                ? 1 : Math.max(1, availableMana / manaCost);
        int candidateValue = fallback == null ? value.grossValue() : fallback.value();
        if (fallback == null && !cast && activationCost.lifeCost() > 0) {
            // Gross action value does not include access costs. Life is not part of the mana
            // constraint, so preserve it as an explicit penalty for known activations.
            final int lifeCostValue = EffectMath.negate(PlayerResourceValueEvaluator.evaluateLifeChange(
                    ai.getLife(), ai.getLife() - activationCost.lifeCost()));
            candidateValue = EffectMath.subtract(candidateValue, lifeCostValue);
        }
        return new Candidate(ability, manaCost, candidateValue, cast, maxUses, fallback != null);
    }

    private static State solve(final List<Candidate> candidates, final int availableMana,
            final int handOverflow, final EffectEvaluationBudget budget) {
        State[][] states = new State[availableMana + 1][handOverflow + 1];
        states[0][0] = new State(0, 0, 0, List.of());

        for (final List<Candidate> group : groupCandidates(candidates)) {
            budget.check();
            final State[][] nextStates = copyStates(states);
            for (final Candidate candidate : group) {
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
                            repeated = repeated.add(candidate, newCasts);
                            if (isBetter(repeated, nextStates[newMana][newCasts])) {
                                nextStates[newMana][newCasts] = repeated;
                            }
                        }
                    }
                }
            }
            states = nextStates;
        }

        State best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int mana = 0; mana <= availableMana; mana++) {
            budget.check();
            for (int casts = 0; casts <= handOverflow; casts++) {
                final State state = states[mana][casts];
                if (state == null || state.actions().isEmpty()) {
                    continue;
                }
                final int score = saturatedAdd(state.value(),
                        -((availableMana - mana) * UNUSED_MANA_PENALTY));
                if (best == null || score > bestScore
                        || score == bestScore && mana > best.usedMana()) {
                    best = state;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    /**
     * A card may expose several cast modes, but casting one mode consumes the card. Activated
     * abilities remain separate groups because different abilities on one permanent can each be
     * used, subject to the existing per-candidate tap/use bounds.
     */
    private static List<List<Candidate>> groupCandidates(final List<Candidate> candidates) {
        final IdentityHashMap<Card, List<Candidate>> castGroups = new IdentityHashMap<>();
        final List<List<Candidate>> groups = new ArrayList<>();
        for (final Candidate candidate : candidates) {
            if (!candidate.cast()) {
                groups.add(List.of(candidate));
                continue;
            }
            List<Candidate> group = castGroups.get(candidate.ability().getHostCard());
            if (group == null) {
                group = new ArrayList<>();
                castGroups.put(candidate.ability().getHostCard(), group);
                groups.add(group);
            }
            group.add(candidate);
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
                -availableMana * UNUSED_MANA_PENALTY, 0, evaluated, 0);
    }

    private static Selection timedOut(final int availableMana, final int evaluated) {
        Logger.warn("[AI Effect Analysis] Mana action combination timed out after evaluating {} candidates; "
                + "preserving legacy action ordering.", evaluated);
        return emptySelection(availableMana, evaluated);
    }

    private static int actionSelectionTimeoutMillis(final Player ai) {
        try {
            return Math.max(1, AiProfileUtil.getIntProperty(ai,
                    AiProps.ACTION_COMBINATION_VALUE_SELECTION_TIMEOUT_MS));
        } catch (final RuntimeException invalidProfileValue) {
            return 100;
        }
    }

    private static void logSelection(final Selection selection) {
        if (!Boolean.parseBoolean(System.getProperty(EffectAnalysisTrace.ENABLE_PROPERTY, "true"))
                || !selection.hasAction()) {
            return;
        }
        final StringJoiner actions = new StringJoiner(", ");
        for (final SpellAbility ability : selection.actions()) {
            actions.add(ability.getHostCard().getName() + "(" + ability.getApi() + ")");
        }
        Logger.info("[AI Effect Analysis] Mana action combination: availableMana={}, usedMana={}, "
                        + "unusedMana={}, score={}, handPressure={}, candidates={}, fallbacks={}, first={}, actions={}",
                selection.availableMana(), selection.usedMana(), selection.unusedMana(),
                selection.score(), selection.handPressureBonus(), selection.evaluatedCandidateCount(),
                selection.fallbackCandidateCount(),
                selection.firstAction().getHostCard().getName(), actions);
    }

    private record Candidate(SpellAbility ability, int manaCost, int value, boolean cast,
            int maxUses, boolean fallback) {
    }

    private record State(int value, int usedMana, int castCount, List<SpellAbility> actions) {
        private State add(final Candidate candidate, final int newCastCount) {
            final List<SpellAbility> nextActions = new ArrayList<>(actions);
            nextActions.add(candidate.ability());
            return new State(saturatedAdd(value, candidate.value()),
                    usedMana + candidate.manaCost(), newCastCount, nextActions);
        }
    }
}
