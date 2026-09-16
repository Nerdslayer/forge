package forge.ai.effect;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.StringJoiner;

import org.tinylog.Logger;

import forge.ai.CardResourceValueEvaluator;
import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
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
 * independently evaluated net values and a modest unused-mana penalty.</p>
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

        final int availableMana;
        try {
            availableMana = Math.max(0,
                    ComputerUtilMana.getAvailableManaEstimate(ai, true));
        } catch (final RuntimeException ignored) {
            // The existing chooser remains the safe fallback if mana estimation is unavailable.
            return emptySelection(0, 0);
        }
        final List<Candidate> candidates = new ArrayList<>();
        for (final SpellAbility ability : abilities) {
            if (Thread.currentThread().isInterrupted()) {
                return emptySelection(availableMana, candidates.size());
            }
            try {
                final Candidate candidate = createCandidate(ai, ability, availableMana, skipCounter);
                if (candidate != null) {
                    candidates.add(candidate);
                }
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
        final State best = solve(candidates, availableMana, handOverflow);
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
            final int availableMana, final boolean skipCounter) {
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

        ability.setActivatingPlayer(ai);
        final ValuationAction action;
        final ValuationContext context;
        final boolean cast;
        if (ability.isSpell() && host.isInZone(ZoneType.Hand)
                && (host.getOwner() == ai || host.getController() == ai)) {
            if (host.isLand() || !ability.canCastTiming(ai)
                    || !ComputerUtilCost.canPayCost(ability, ai, ability.isTrigger())) {
                return null;
            }
            action = new CastValuationAction(ability);
            context = ValuationContext.forCast(ai, true);
            cast = true;
        } else if (ability.isActivatedAbility() && host.isInPlay()
                && host.getController() == ai && !host.isPhasedOut()) {
            if (!ComputerUtilCost.canPayCost(ability, ai, false)) {
                return null;
            }
            action = new ActivateValuationAction(host, ability);
            context = ValuationContext.forActivation(ai, true);
            cast = false;
        } else {
            return null;
        }

        final CardValueBreakdown value = UnifiedActionValueEvaluator.evaluate(action, context);
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
            final ActivationOccurrenceRequest request = new SituationalAbilityOccurrenceContext(ai)
                    .activationRequest(host, ability);
            if (!request.supported()) {
                // Unsupported additional costs need their own value model. Treating them as a
                // fair mana sink would risk selecting a harmful activation.
                return null;
            }
            fallback = ActionValueFallbackEvaluator.activation(ai, manaCost, request.lifeCost());
        }
        final boolean tapCost = ability.getPayCosts().hasTapCost();
        final int maxUses = cast || tapCost || manaCost == 0
                ? 1 : Math.max(1, availableMana / manaCost);
        final int candidateValue = fallback == null ? value.netValue() : fallback.value();
        return new Candidate(ability, manaCost, candidateValue, cast, maxUses, fallback != null);
    }

    private static State solve(final List<Candidate> candidates, final int availableMana,
            final int handOverflow) {
        State[][] states = new State[availableMana + 1][handOverflow + 1];
        states[0][0] = new State(0, 0, 0, List.of());

        for (final List<Candidate> group : groupCandidates(candidates)) {
            final State[][] nextStates = copyStates(states);
            for (final Candidate candidate : group) {
                final int uses = Math.min(candidate.maxUses(),
                        candidate.manaCost() == 0 ? 1 : availableMana / candidate.manaCost());
                for (int mana = 0; mana <= availableMana; mana++) {
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
