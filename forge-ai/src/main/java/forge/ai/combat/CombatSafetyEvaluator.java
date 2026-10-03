package forge.ai.combat;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

/** One public survivor-board follow-up. Inner block calls explicitly have no further lookahead. */
public final class CombatSafetyEvaluator {
    private CombatSafetyEvaluator() { }

    public record FollowUp(boolean supported, boolean searchExhaustive, int nonterminalUtility,
            boolean lethalOpportunity, List<Integer> attackers, List<String> reasons, boolean candidateSearchComplete) {
        public FollowUp { attackers = List.copyOf(attackers); reasons = List.copyOf(reasons); }
        public FollowUp(final boolean supported, final boolean searchExhaustive, final int nonterminalUtility,
                final boolean lethalOpportunity, final List<Integer> attackers, final List<String> reasons) {
            this(supported, searchExhaustive, nonterminalUtility, lethalOpportunity, attackers, reasons, searchExhaustive);
        }
        /** All chosen heuristic candidates and their opposing blocks were evaluated, not all subsets. */
        public boolean usable() { return supported && candidateSearchComplete; }
    }

    /** Optional scalar continuation for the explicit two-turn heuristic, never a recursive policy. */
    public record Continuation(PublicCombatSnapshot snapshot, CombatProjection projection, PreparedCombatValuation values) { }

    public record Forecast(FollowUp evaluation, Optional<Continuation> continuation) { }

    /** Ordinary speculative combat value gets half weight; lethal protection is ranked separately. */
    public static int discountedFollowUpValue(final long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(value * 0.5)));
    }

    /** A heuristic opportunity is not an actual win; current terminal ranking remains separate. */
    public static int incrementalPressure(final FollowUp baseline, final FollowUp candidate) {
        if (!baseline.usable() || !candidate.usable()) {
            throw new IllegalArgumentException("Complete comparable follow-up estimates required");
        }
        final long delta = (long) candidate.nonterminalUtility() - baseline.nonterminalUtility();
        final int ordinary = discountedFollowUpValue(delta);
        final int lethal = candidate.lethalOpportunity() == baseline.lethalOpportunity() ? 0
                : candidate.lethalOpportunity() ? 500 : -500;
        return CombatOutcomePredictor.add(ordinary, lethal);
    }

    public static FollowUp nextAttack(final PublicCombatSnapshot before, final CombatProjection current,
            final PublicCombatReadiness readiness, final PreparedCombatValuation values, final CombatSearchBudget budget) {
        return forecastNextAttack(before, current, readiness, values, budget).evaluation();
    }

    /** Same bounded forecast, retaining the selected public continuation without redoing search. */
    public static Forecast forecastNextAttack(final PublicCombatSnapshot before, final CombatProjection current,
            final PublicCombatReadiness readiness, final PreparedCombatValuation values, final CombatSearchBudget budget) {
        if (!current.supported() || !current.available() || current.terminal() != CombatProjection.Terminal.NONE) {
            return new Forecast(rejected("No continuing supported combat state"), Optional.empty());
        }
        if (!readiness.unsupportedReasons().isEmpty()) {
            return new Forecast(new FollowUp(false, false, 0, false, List.of(), readiness.unsupportedReasons()), Optional.empty());
        }
        if (readiness.nextActivePlayerId() != before.defendingPlayerId()) {
            return new Forecast(rejected("Immediate reply timing differs from ordinary alternating turns"), Optional.empty());
        }
        final int active = readiness.nextActivePlayerId();
        final int defending = before.attackingPlayerId();
        final boolean observingAttacker = before.observingPlayerId() == active;
        final Map<Integer, PublicCombatSnapshot.Creature> creatures = new LinkedHashMap<>();
        current.survivors().forEach((id, survivor) -> {
            final PublicCombatSnapshot.Creature card = before.creatureAfterLosses(id, current.lostCreatures());
            // The next active player untaps; the other side does not. Combat damage clears.
            final boolean attemptsUntap = card.controllerId() == active && survivor.tapped();
            final boolean tapped = survivor.tapped() && (!attemptsUntap || card.stunCounters() > 0);
            final int stun = card.stunCounters() - (attemptsUntap && card.stunCounters() > 0 ? 1 : 0);
            creatures.put(id, new PublicCombatSnapshot.Creature(id, card.controllerId(), card.combatDamage(), card.toughness(),
                    0, false, card.firstStrike(), card.doubleStrike(), card.deathtouch(), card.indestructible(), card.trample(),
                    card.lifelink(), card.vigilance(), tapped, card.minimumBlockers(), card.maximumBlockers(), stun, survivor.shieldCounters()));
        });
        final Map<Integer, PublicCombatSnapshot.LifeState> players = new LinkedHashMap<>();
        before.players().forEach((id, state) -> players.put(id, new PublicCombatSnapshot.LifeState(current.playerLifeAfter().get(id),
                state.canLoseLife(), state.canGainLife(), state.cannotLoseAtZero(), state.cannotWin())));
        // Neither lost edges nor completed current opportunities can be charged again later.
        final PreparedCombatValuation survivingValues = values.afterCombat(before, current);
        final Map<Integer, CombatPlayerResources> resources = new LinkedHashMap<>(current.outcomes().resourcesAfter().isEmpty()
                ? before.resources() : current.outcomes().resourcesAfter());
        if (!before.triggers().isEmpty() && !resources.isEmpty()) {
            final CombatPlayerResources nextPlayer = resources.get(active);
            if (nextPlayer == null || nextPlayer.librarySize() < 1) {
                return new Forecast(rejected("Normal draw before reply could exhaust the projected library"), Optional.empty());
            }
            // Public count-only normal draw, not a hidden-card guess or additional utility bonus.
            resources.put(active, new CombatPlayerResources(CombatOutcomePredictor.add(nextPlayer.handSize(), 1), nextPlayer.librarySize() - 1));
        }
        final List<Integer> eligible = creatures.keySet().stream()
                .filter(id -> creatures.get(id).controllerId() == active && !creatures.get(id).tapped()
                        && readiness.canAttackNextTurn().contains(id)).sorted().toList();
        final Set<String> reasons = new LinkedHashSet<>(readiness.ignoredEffects());
        final Map<Integer, Integer> attacks = new LinkedHashMap<>();
        final Map<Integer, Set<Integer>> blockers = new LinkedHashMap<>();
        for (final int id : eligible) {
            attacks.put(id, defending);
            blockers.put(id, readiness.blockPairsNextTurn().getOrDefault(id, Set.of()).stream()
                    .filter(blocker -> creatures.containsKey(blocker) && creatures.get(blocker).controllerId() == defending
                            && !creatures.get(blocker).tapped()).collect(java.util.stream.Collectors.toSet()));
        }
        final PublicCombatSnapshot alternatives = new PublicCombatSnapshot(before.observingPlayerId(), active, defending,
                creatures, players, attacks, blockers, List.of(), List.of(), before.legacyDamageOrder(), resources,
                before.triggers().stream().filter(trigger -> creatures.containsKey(trigger.ability().sourceId())).toList(), Set.of(), Set.of(),
                before.preventionRules().stream().filter(rule -> !current.lostCreatures().contains(rule.providerId())).toList(),
                before.staticWorlds().surviving(current.lostCreatures()), readiness.ignoredEffects());
        final var selection = GreedyAttackCandidates.select(List.of(), eligible, group -> {
            if (!budget.tryConsume()) { reasons.add("Follow-up shared budget exhausted"); return null; }
            final PublicCombatSnapshot next = CombatAttackCandidates.select(alternatives, group);
            final CombatBlockSearch.Result response = CombatBlockSearch.search(next, survivingValues, budget);
            reasons.addAll(response.reasons());
            // TODO: The block expansion bound ignores used-blocker pruning and can choose a
            // beam for a manageable domain. Audit a tighter bound before relaxing this gate.
            if (!response.outcomeDomainComplete() || !response.searchExhaustive() || response.best().isEmpty()) {
                reasons.add("Incomplete opposing block responses in greedy future attack search");
                return null;
            }
            final var candidate = response.best().orElseThrow();
            return new FutureCandidate(next, candidate);
        }, (left, right) -> {
            final int comparison = CombatTransitionValueEvaluator.compare(left.combat().score(), right.combat().score());
            return observingAttacker ? comparison : -comparison;
        }, id -> id);
        // TODO: Reuse permutation-equivalent future singleton scores once future-role keys are audited.
        // Greedy search may miss non-prefix combinations; no-lethal is an estimate, not a safety certificate.
        reasons.add("Greedy future singleton/addition search with independent all-out candidate; not a global optimum certificate");
        reasons.add("Greedy future declarations evaluated: " + selection.evaluations());
        if (!selection.complete()) { reasons.add("Greedy future attack candidates were not completely evaluated"); }
        final FutureCandidate selected = selection.best().orElse(null);
        if (selected == null) {
            return new Forecast(new FollowUp(false, false, 0, false, List.of(), List.copyOf(reasons), false), Optional.empty());
        }
        final var candidate = selected.combat();
        final int utility = CombatOutcomePredictor.add(CombatOutcomePredictor.add(candidate.score().permanentLoss().total(),
                candidate.score().lifeUtility()), candidate.score().outcomeUtility());
        final boolean lethal = candidate.projection().terminal()
                == (observingAttacker ? CombatProjection.Terminal.WIN : CombatProjection.Terminal.LOSS);
        // TODO: Scheduled state-changing opportunities, static layers beyond fixed additive P/T,
        // untap/turn-skipping rules and alternative equally ranked public reply continuations.
        return new Forecast(new FollowUp(true, selection.complete() && eligible.size() <= 2, utility, lethal,
                candidate.assignment().attackersToDefenders().keySet().stream().sorted().toList(), List.copyOf(reasons), selection.complete()),
                Optional.of(new Continuation(selected.snapshot(), candidate.projection(), survivingValues)));
    }

    private record FutureCandidate(PublicCombatSnapshot snapshot, CombatBlockSearch.Candidate combat) { }

    private static FollowUp rejected(final String reason) {
        return new FollowUp(false, false, 0, false, List.of(), List.of(reason));
    }
}
