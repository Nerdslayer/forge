package forge.ai.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Priority-ordered remaining-attacker allocation with shared full-combat responses. */
final class CombatAttackDestinationSearch {
    private CombatAttackDestinationSearch() { }
    private static final int MAX_ROUTES = 4;

    static CombatAttackSearch.Result search(final PublicCombatSnapshot snapshot, final PreparedCombatValuation values,
            final PublicCombatReadiness readiness, final CombatSearchBudget budget,
            final Map<Integer, Integer> fixed, final Map<Integer, Set<Integer>> legal) {
        return new Search(snapshot, values, readiness, budget, fixed, legal).run();
    }

    static boolean allWalkerAbilitiesComplete(final PublicCombatSnapshot snapshot, final PreparedCombatValuation values) {
        return snapshot.planeswalkers().values().stream().filter(w -> w.controllerId() == snapshot.defendingPlayerId())
                .allMatch(w -> values.permanents().containsKey(w.id()) && values.permanents().get(w.id()).abilitiesComplete());
    }

    static List<PublicCombatSnapshot.Planeswalker> priorityWalkers(final PublicCombatSnapshot snapshot, final PreparedCombatValuation values) {
        final boolean allComplete = allWalkerAbilitiesComplete(snapshot, values);
        return snapshot.planeswalkers().values().stream().filter(w -> w.controllerId() == snapshot.defendingPlayerId())
                .sorted(Comparator.<PublicCombatSnapshot.Planeswalker>comparingInt(w -> w.ultimateThreat().potential() ? 0 : 1)
                        .thenComparing(Comparator.comparingInt((PublicCombatSnapshot.Planeswalker w) -> allComplete
                                ? values.evaluateLosses(Set.of(w.id())).total() : w.manaValue()).reversed())
                        .thenComparingInt(w -> {
                            final int denial = PlaneswalkerUltimateThreatEvaluator.damageRequired(w.loyalty(), w.ultimateThreshold());
                            return w.ultimateThreat().potential() && denial > 0 ? denial : w.loyalty();
                        }).thenComparingInt(PublicCombatSnapshot.Planeswalker::id)).toList();
    }

    private static final class Search {
        private final PublicCombatSnapshot snapshot;
        private final PreparedCombatValuation values;
        private final CombatSearchBudget budget;
        private final Map<Integer, Integer> fixed;
        private final Map<Integer, Set<Integer>> legal;
        private final PublicCombatReadiness readiness;
        private final Set<String> reasons;
        private final CombatAttackSearch.Evaluation evaluation;
        private final Map<Map<Integer, Integer>, CombatAttackSearch.Candidate> cache = new LinkedHashMap<>();
        private final int start;
        private boolean complete = true;
        private int safeguardEvaluations;

        Search(final PublicCombatSnapshot snapshot0, final PreparedCombatValuation values0,
                final PublicCombatReadiness readiness, final CombatSearchBudget budget0,
                final Map<Integer, Integer> fixed0, final Map<Integer, Set<Integer>> legal0) {
            snapshot = snapshot0; values = values0; budget = budget0; fixed = fixed0; legal = legal0;
            this.readiness = readiness;
            start = budget.used();
            reasons = new LinkedHashSet<>(snapshot.unsupportedReasons());
            reasons.addAll(snapshot.unavailableReasons());
            evaluation = new CombatAttackSearch.Evaluation(snapshot, values, readiness, budget, reasons);
        }

        CombatAttackSearch.Result run() {
            if (!reasons.isEmpty()) { complete = false; return result(null, null); }
            final Map<Integer, Integer> playerAll = new LinkedHashMap<>(fixed);
            legal.keySet().stream().sorted().filter(id -> !fixed.containsKey(id) && legal.get(id).contains(snapshot.defendingPlayerId()))
                    .forEach(id -> playerAll.put(id, snapshot.defendingPlayerId()));
            // Current resolution first, within the existing planning budget and validation reserve.
            final var immediateAllowance = budget.childAllowance(Math.min(6000, Math.max(1, budget.remaining() / 4)));
            safeguardEvaluations++;
            final var win = evaluation.immediateWin(CombatAttackCandidates.assign(snapshot, playerAll), immediateAllowance);
            if (win != null) {
                reasons.add("Independent immediate player-win safeguard; certified=" + win.certifiedWin());
                return result(win, null);
            }
            final var baseline = evaluate(fixed);
            if (baseline == null) {
                // All-out is a safeguard, not proof that no smaller winning declaration exists.
                for (final int id : playerAll.keySet().stream().sorted().toList()) {
                    if (fixed.containsKey(id) || immediateAllowance.remaining() == 0) { continue; }
                    final Map<Integer, Integer> singleton = new LinkedHashMap<>(fixed);
                    singleton.put(id, snapshot.defendingPlayerId());
                    safeguardEvaluations++;
                    final var alternative = evaluation.immediateWin(CombatAttackCandidates.assign(snapshot, singleton), immediateAllowance);
                    if (alternative != null) {
                        complete = true;
                        reasons.add("Independent singleton player-win safeguard; certified=" + alternative.certifiedWin());
                        return result(alternative, null);
                    }
                }
                reasons.add("No comparable continuation and no independently verified current win");
                return result(null, null);
            }
            final List<CombatAttackSearch.Candidate> routes = new ArrayList<>();
            routes.add(baseline);
            final List<PublicCombatSnapshot.Planeswalker> walkers = priorityWalkers(snapshot, values);
            reasons.add("Walker priority basis=" + (allWalkerAbilitiesComplete(snapshot, values) ? "ability_value" : "mana_value")
                    + "; ordered sources=" + walkers.stream().map(PublicCombatSnapshot.Planeswalker::id).toList());
            for (final var walker : walkers) {
                if (!complete) { break; }
                final List<CombatAttackSearch.Candidate> extensions = new ArrayList<>(routes);
                final int target = new CombatAttackDestination(CombatAttackDestination.Kind.PLANESWALKER, walker.id()).key();
                for (final var route : List.copyOf(routes)) {
                    if (!complete) { break; }
                    extensions.addAll(allocate(route.combat().assignment().attackersToDefenders(), target, walker));
                }
                routes.clear();
                routes.addAll(frontier(extensions, baseline));
            }
            if (complete) {
                for (final var route : List.copyOf(routes)) {
                    if (!complete) { break; }
                    routes.addAll(allocate(route.combat().assignment().attackersToDefenders(), snapshot.defendingPlayerId(), null));
                }
            }
            CombatAttackSearch.Candidate best = cache.values().stream().filter(java.util.Objects::nonNull).max(CombatAttackDestinationSearch::compareSelection).orElse(baseline);
            if (complete) { best = trim(best); }
            reasons.add("Priority greedy remaining-attacker allocation; bounded denial/kill/skip routes=" + MAX_ROUTES);
            reasons.add("Walker goals use best predicted blocks, not a forced-kill certificate");
            if (!complete) { reasons.add("Incomplete destination search: use whole-decision legacy fallback"); }
            // TODO: Pair swaps, alternate target orders and broader future destinations. No subset cross product.
            return result(best, baseline);
        }

        private List<CombatAttackSearch.Candidate> allocate(final Map<Integer, Integer> committed,
                final int target, final PublicCombatSnapshot.Planeswalker walker) {
            final List<Integer> optional = legal.keySet().stream().filter(id -> !committed.containsKey(id) && legal.get(id).contains(target)).sorted().toList();
            final Function<List<Integer>, CombatAttackSearch.Candidate> extension = ids -> {
                final Map<Integer, Integer> assignments = new LinkedHashMap<>(committed);
                ids.stream().filter(id -> !committed.containsKey(id)).forEach(id -> assignments.put(id, target));
                return evaluate(assignments);
            };
            final var representatives = CombatAttackEquivalence.representatives(snapshot, values, readiness, optional,
                    committed, legal, target, true);
            reasons.add("Equivalent destination singleton rankings reused: "
                    + (optional.size() - representatives.values().stream().distinct().count()));
            final var selection = GreedyAttackCandidates.select(committed.keySet().stream().sorted().toList(), optional,
                    extension, CombatAttackDestinationSearch::compare, CombatAttackDestinationSearch::compareSelection, id -> representatives.get(id));
            complete &= selection.complete();
            final List<CombatAttackSearch.Candidate> result = new ArrayList<>();
            selection.best().ifPresent(result::add);
            if (walker != null) {
                for (final boolean kill : List.of(false, true)) {
                    cache.values().stream().filter(java.util.Objects::nonNull).filter(candidate -> {
                        final var assignments = candidate.combat().assignment().attackersToDefenders();
                        if (!committed.entrySet().stream().allMatch(e -> e.getValue().equals(assignments.get(e.getKey())))) { return false; }
                        if (assignments.entrySet().stream().anyMatch(e -> !committed.containsKey(e.getKey()) && e.getValue() != target)) { return false; }
                        final int after = candidate.combat().projection().planeswalkerLoyaltyAfter().getOrDefault(walker.id(), walker.loyalty());
                        return kill ? after == 0 : walker.ultimateThreat().potential() && walker.ultimateThreshold() > 0 && after < walker.ultimateThreshold();
                    }).min(Comparator.<CombatAttackSearch.Candidate>comparingInt(c -> c.attackers().size())
                            .thenComparing((a, b) -> compare(b, a))).ifPresent(result::add);
                }
            }
            return result;
        }

        private List<CombatAttackSearch.Candidate> frontier(final List<CombatAttackSearch.Candidate> candidates,
                final CombatAttackSearch.Candidate baseline) {
            final Map<Map<Integer, Integer>, CombatAttackSearch.Candidate> unique = new LinkedHashMap<>();
            candidates.forEach(c -> unique.putIfAbsent(c.combat().assignment().attackersToDefenders(), c));
            final List<CombatAttackSearch.Candidate> result = new ArrayList<>(unique.values().stream()
                    .sorted((a, b) -> compareSelection(b, a)).limit(MAX_ROUTES - 1).toList());
            if (!result.contains(baseline)) { result.add(baseline); }
            return result;
        }

        private CombatAttackSearch.Candidate trim(CombatAttackSearch.Candidate best) {
            if (best.combat().projection().terminal() != CombatProjection.Terminal.NONE) { return best; }
            final var allowance = budget.childAllowance(Math.min(4000, budget.remaining() / 4));
            if (allowance.remaining() == 0) { return best; }
            final var optional = evaluation.withBudget(allowance);
            final Function<Map<Integer, Integer>, CombatAttackSearch.Candidate> trial = assignments -> {
                if (assignments.entrySet().stream().anyMatch(e -> !legal.getOrDefault(e.getKey(), Set.of()).contains(e.getValue()))) { return null; }
                return optional.evaluate(CombatAttackCandidates.assign(snapshot, assignments));
            };
            best = CombatAttackRefiner.refine(best, fixed, trial, CombatAttackDestinationSearch::compare, allowance, reasons);
            for (final int id : best.attackers()) {
                if (allowance.remaining() == 0) { break; }
                if (fixed.containsKey(id)) { continue; }
                for (final int target : legal.get(id).stream().sorted().toList()) {
                    if (allowance.remaining() == 0) { break; }
                    final Map<Integer, Integer> redirected = new LinkedHashMap<>(best.combat().assignment().attackersToDefenders());
                    redirected.put(id, target);
                    final var alternative = trial.apply(redirected);
                    if (alternative != null && compare(alternative, best) > 0) { best = alternative; }
                }
            }
            reasons.add("Optional destination refinement cache hits: " + optional.cacheHits() + ", allowanceRemaining=" + allowance.remaining());
            return best;
        }

        private CombatAttackSearch.Candidate evaluate(final Map<Integer, Integer> assignments) {
            final Map<Integer, Integer> key = Map.copyOf(assignments);
            if (cache.containsKey(key)) { return cache.get(key); }
            if (key.entrySet().stream().anyMatch(e -> !legal.getOrDefault(e.getKey(), Set.of()).contains(e.getValue()))) {
                throw new IllegalArgumentException("Destination search requested an illegal alternative");
            }
            final var result = evaluation.evaluate(CombatAttackCandidates.assign(snapshot, key));
            cache.put(key, result);
            if (result == null) { complete = false; }
            return result;
        }

        private CombatAttackSearch.Result result(final CombatAttackSearch.Candidate best, final CombatAttackSearch.Candidate baseline) {
            return CombatAttackDecisionPolicy.select(new CombatAttackSearch.Result(Optional.ofNullable(best), Optional.ofNullable(baseline), false, complete,
                    budget.used() - start, List.copyOf(reasons), complete, cache.size() + safeguardEvaluations));
        }
    }

    private static int compare(final CombatAttackSearch.Candidate left, final CombatAttackSearch.Candidate right) {
        if (left.certifiedWin() != right.certifiedWin()
                || left.combat().projection().terminal() != CombatProjection.Terminal.NONE
                || right.combat().projection().terminal() != CombatProjection.Terminal.NONE
                || left.reply().map(CombatSafetyEvaluator.FollowUp::lethalOpportunity).orElse(false)
                        != right.reply().map(CombatSafetyEvaluator.FollowUp::lethalOpportunity).orElse(false)) {
            return CombatAttackSearch.compare(left, right);
        }
        final boolean leftRace = left.twoTurnPressure().map(v -> v.usable() && v.lethalOpportunity()).orElse(false);
        final boolean rightRace = right.twoTurnPressure().map(v -> v.usable() && v.lethalOpportunity()).orElse(false);
        if (leftRace != rightRace) { return leftRace ? 1 : -1; }
        return CombatAttackSearch.compare(left, right);
    }

    private static int compareSelection(final CombatAttackSearch.Candidate left, final CombatAttackSearch.Candidate right) {
        return CombatAttackRefiner.preferSmaller(left, right, compare(left, right));
    }
}
