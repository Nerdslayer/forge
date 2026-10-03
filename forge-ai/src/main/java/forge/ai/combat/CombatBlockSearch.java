package forge.ai.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Game-free joint blocking search with bounded groups and explicit allocation/search coverage. */
public final class CombatBlockSearch {
    private CombatBlockSearch() { }

    public record Candidate(CombatAssignment assignment, CombatProjection projection,
            CombatTransitionValueEvaluator.Score score, Optional<CombatDamagePlan> damagePlan,
            int pressureValue, int exchangePreference, Optional<CombatSafetyEvaluator.FollowUp> followUp, int chumpLifeAdjustment) {
        public int adjustedTotal() {
            return CombatOutcomePredictor.add(CombatOutcomePredictor.add(
                    CombatOutcomePredictor.add(score.total(), pressureValue), exchangePreference), chumpLifeAdjustment);
        }
    }

    /** Exhaustiveness refers only to the admitted search domain, not outcome completeness.
     * unblocked is the no-additional-blocks baseline, which may contain fixed blocks.
     */
    public record Result(Optional<Candidate> best, Optional<Candidate> unblocked,
            boolean searchExhaustive, boolean outcomeDomainComplete, int nodes, List<String> reasons) {
        public Result { reasons = List.copyOf(reasons); }
    }

    public static Result search(final PublicCombatSnapshot snapshot, final PreparedCombatValuation values,
            final CombatSearchBudget budget) {
        return search(snapshot, values, budget, 16);
    }

    public static Result search(final PublicCombatSnapshot snapshot, final PreparedCombatValuation values,
            final CombatSearchBudget budget, final int beamWidth) {
        if (beamWidth <= 0) { throw new IllegalArgumentException("Positive beam width required"); }
        final Search search = new Search(snapshot, values, budget, beamWidth, null, null);
        return search.run();
    }

    /** Only the outer blocking decision admits lookahead; reply searches use search(). */
    public static Result searchWithPressure(final PublicCombatSnapshot snapshot, final PreparedCombatValuation values,
            final CombatSearchBudget budget, final PublicCombatReadiness readiness) {
        return searchWithPressure(snapshot, values, budget, readiness, Map.of());
    }

    /** Preserve fixed blocks, completing admitted minimum-size groups before ranking alternatives. */
    public static Result searchWithPressure(final PublicCombatSnapshot snapshot, final PreparedCombatValuation values,
            final CombatSearchBudget budget, final PublicCombatReadiness readiness,
            final Map<Integer, List<Integer>> fixedBlocks) {
        if (snapshot.observingPlayerId() != snapshot.defendingPlayerId() || readiness == null) {
            throw new IllegalArgumentException("Pressure requires an observing defender and frozen readiness");
        }
        return new Search(snapshot, values, budget, 16, readiness, null, fixedBlocks).run();
    }

    /** Adversarial blocks consider reply safety before nonterminal utility, with no recursive policy. */
    public static Result searchWithReplySafety(final PublicCombatSnapshot snapshot, final PreparedCombatValuation values,
            final CombatSearchBudget budget, final java.util.function.Function<CombatProjection, CombatSafetyEvaluator.FollowUp> replyEstimator) {
        return searchWithReplySafety(snapshot, values, budget, replyEstimator, null);
    }

    /** Optional explicit two-turn heuristic; nested follow-up searches never install it. */
    public static Result searchWithReplySafety(final PublicCombatSnapshot snapshot, final PreparedCombatValuation values,
            final CombatSearchBudget budget, final java.util.function.Function<CombatProjection, CombatSafetyEvaluator.FollowUp> replyEstimator,
            final java.util.function.Function<CombatProjection, CombatTwoTurnPressureEvaluator.Forecast> continuationEstimator) {
        if (snapshot.observingPlayerId() != snapshot.attackingPlayerId() || replyEstimator == null) {
            throw new IllegalArgumentException("Reply-aware responses require an observing attacker");
        }
        return new Search(snapshot, values, budget, 16, null, replyEstimator, Map.of(), continuationEstimator).run();
    }

    private static final class Search {
        private final PublicCombatSnapshot snapshot;
        private final PreparedCombatValuation values;
        private final CombatSearchBudget budget;
        private final CombatChumpValueEvaluator chumps;
        private final int startingNodes;
        private final int beamWidth;
        private final List<Integer> attackers;
        private final Map<Integer, List<List<Integer>>> groups = new HashMap<>();
        private final Map<Integer, List<Integer>> fixedBlocks;
        private final Set<Integer> reservedBlockers = new HashSet<>();
        private final Set<String> reasons = new LinkedHashSet<>();
        private final Comparator<Candidate> ordering;
        private final PublicCombatReadiness readiness;
        private final java.util.function.Function<CombatProjection, CombatSafetyEvaluator.FollowUp> replyEstimator;
        private final java.util.function.Function<CombatProjection, CombatTwoTurnPressureEvaluator.Forecast> continuationEstimator;
        private final Map<CombatProjection, CombatTwoTurnPressureEvaluator.Forecast> continuations = new HashMap<>();
        private final Map<CombatProjection, CombatSafetyEvaluator.FollowUp> forecasts = new HashMap<>();
        private CombatSafetyEvaluator.FollowUp baselineFollowUp;
        private boolean baselineEvaluated;
        private boolean pressureEnabled;
        private Candidate best;
        private boolean domainComplete = true;
        private boolean interrupted;
        private boolean groupsOmitted;

        private Search(final PublicCombatSnapshot snapshot0, final PreparedCombatValuation values0,
                final CombatSearchBudget budget0, final int width, final PublicCombatReadiness readiness0,
                final java.util.function.Function<CombatProjection, CombatSafetyEvaluator.FollowUp> replyEstimator0) {
            this(snapshot0, values0, budget0, width, readiness0, replyEstimator0, Map.of());
        }

        private Search(final PublicCombatSnapshot snapshot0, final PreparedCombatValuation values0,
                final CombatSearchBudget budget0, final int width, final PublicCombatReadiness readiness0,
                final java.util.function.Function<CombatProjection, CombatSafetyEvaluator.FollowUp> replyEstimator0,
                final Map<Integer, List<Integer>> fixedBlocks0) {
            this(snapshot0, values0, budget0, width, readiness0, replyEstimator0, fixedBlocks0, null);
        }

        private Search(final PublicCombatSnapshot snapshot0, final PreparedCombatValuation values0,
                final CombatSearchBudget budget0, final int width, final PublicCombatReadiness readiness0,
                final java.util.function.Function<CombatProjection, CombatSafetyEvaluator.FollowUp> replyEstimator0,
                final Map<Integer, List<Integer>> fixedBlocks0,
                final java.util.function.Function<CombatProjection, CombatTwoTurnPressureEvaluator.Forecast> continuationEstimator0) {
            snapshot = snapshot0;
            fixedBlocks = new CombatAssignment(snapshot.attackersToDefenders(), fixedBlocks0).blockersByAttacker();
            fixedBlocks.values().forEach(reservedBlockers::addAll);
            values = values0;
            budget = budget0;
            chumps = new CombatChumpValueEvaluator(snapshot0, budget0);
            startingNodes = budget.used();
            beamWidth = width;
            readiness = readiness0;
            replyEstimator = replyEstimator0;
            continuationEstimator = continuationEstimator0;
            // Dangerous attackers first for the beam; public ID is a stable final tie breaker.
            attackers = snapshot.attackersToDefenders().keySet().stream().sorted(Comparator
                    .<Integer>comparingInt(id -> snapshot.creatures().get(id).combatDamage()).reversed()
                    .thenComparingInt(Integer::intValue)).toList();
            final boolean defendingObserver = snapshot.observingPlayerId() == snapshot.defendingPlayerId();
            ordering = (left, right) -> {
                int comparison = CombatTransitionValueEvaluator.compare(left.score(), right.score());
                final int doomedDefense = hopelessDefensePriority(left.projection(), right.projection());
                if (left.score().terminal() == right.score().terminal() && doomedDefense != 0) {
                    comparison = doomedDefense;
                } else if (left.score().terminal() == right.score().terminal()) {
                    comparison = replyPriority(left.projection(), right.projection());
                    if (comparison == 0) { comparison = Integer.compare(left.adjustedTotal(), right.adjustedTotal()); }
                }
                if (defendingObserver) { comparison = -comparison; }
                return comparison != 0 ? comparison : compareAssignments(left.assignment(), right.assignment());
            };
        }

        private Result run() {
            if (fixedBlocks.values().stream().anyMatch(group -> group.size() > 8)) {
                // TODO: Audit larger fixed gangs without deep integer-allocation recursion.
                reasons.add("Fixed gang exceeds bounded allocation domain");
                return result(null, false);
            }
            final Set<Integer> fixedIds = new HashSet<>();
            for (final var entry : fixedBlocks.entrySet()) {
                final var creature = snapshot.creatures().get(entry.getKey());
                if (!snapshot.attackersToDefenders().containsKey(entry.getKey()) || creature == null
                        || entry.getValue().size() > creature.maximumBlockers()
                        || entry.getValue().stream().anyMatch(id -> !fixedIds.add(id)
                                || !snapshot.legalBlockers().getOrDefault(entry.getKey(), Set.of()).contains(id))) {
                    reasons.add("Fixed blocks contain an illegal pair, duplicate, destination or maximum count");
                    return result(null, false);
                }
            }
            final boolean needsCompletion = fixedBlocks.entrySet().stream().anyMatch(entry ->
                    !snapshot.creatures().get(entry.getKey()).permitsBlockerCount(entry.getValue().size()));
            Candidate unblocked = needsCompletion ? null : evaluate(fixedBlocks);
            if (!needsCompletion && unblocked == null) {
                if (budget.remaining() == 0) { reasons.add("Shared search budget exhausted"); }
                return result(null, false);
            }
            if (!needsCompletion) { baselineEvaluated = true; }
            for (final int attacker : attackers) {
                final List<Integer> required = fixedBlocks.getOrDefault(attacker, List.of());
                final List<Integer> eligible = snapshot.legalBlockers().getOrDefault(attacker, Set.of()).stream()
                        .filter(id -> !reservedBlockers.contains(id)).sorted().toList();
                final List<List<Integer>> choices = new ArrayList<>();
                final var creature = snapshot.creatures().get(attacker);
                if (creature.permitsBlockerCount(required.size())) { choices.add(required); }
                final int minimum = Math.max(required.size() + 1, Math.max(1, creature.minimumBlockers()));
                final int maximum = Math.min(eligible.size() + required.size(), creature.maximumBlockers());
                // At most six candidates have at most 64 total groups, including no block.
                // Enumerate the whole small domain; otherwise ordinary gangs remain bounded.
                final int ordinaryLimit = eligible.size() + required.size() <= 6 ? 6 : 3;
                for (int count = minimum; count <= Math.min(ordinaryLimit, maximum); count++) {
                    generateGroups(eligible, count, 0, new ArrayList<>(required), choices);
                }
                if (minimum > ordinaryLimit && minimum <= maximum) {
                    if (minimum <= 8) { generateGroups(eligible, minimum, 0, new ArrayList<>(required), choices); }
                    else {
                        // TODO: Bounded threshold groups without deep recursive enumeration
                        // for unusually large minimums. Preserve explicit incomplete coverage.
                        // Do not feed a huge group into the recursive integer-allocation
                        // enumerator. Its unsearched alternatives remain visible to callers.
                        groupsOmitted = true;
                    }
                }
                groups.put(attacker, List.copyOf(choices));
                if (maximum >= minimum && maximum > Math.max(ordinaryLimit, minimum)) { groupsOmitted = true; }
            }
            if (needsCompletion) {
                // Use a real legal completion as the pressure reference. An impossible
                // fixed-only/no-block declaration is never projected as a strategic option.
                unblocked = findCompletion(0, new HashMap<>(fixedBlocks), new HashSet<>(reservedBlockers));
                baselineEvaluated = true;
                if (unblocked == null) {
                    reasons.add("No supported legal fixed-group completion found within the shared budget");
                    return result(null, false);
                }
                reasons.add("Fixed groups completed before establishing the legal pressure baseline");
            }
            if (groupsOmitted) { reasons.add("Bounded group generation omits some larger groups/alternatives"); }
            final boolean exact = expansionUpperBound() <= budget.remaining();
            if (exact) {
                enumerate(0, new HashMap<>(fixedBlocks), new HashSet<>(reservedBlockers));
            } else {
                reasons.add("Approximate beam search within the admitted block-group domain");
                beam(unblocked);
                improve();
            }
            if (budget.remaining() == 0) { reasons.add("Shared search budget exhausted"); }
            // TODO: Admit whole-assignment requirements, audited legacy seeds,
            // broader follow-up mechanics and profile calibration before live rollout.
            return result(unblocked, exact && !interrupted && !groupsOmitted);
        }

        private void generateGroups(final List<Integer> eligible, final int count, final int start,
                final List<Integer> prefix, final List<List<Integer>> choices) {
            if (choices.size() >= 64) { groupsOmitted = true; return; }
            if (prefix.size() == count) { choices.add(prefix.stream().sorted().toList()); return; }
            // Do not count an impossible suffix as an omitted group when the 64th
            // (last legal small-domain) group has just filled the choice cap.
            for (int index = start; index <= eligible.size() - (count - prefix.size()); index++) {
                if (choices.size() >= 64) { groupsOmitted = true; return; }
                prefix.add(eligible.get(index));
                generateGroups(eligible, count, index + 1, prefix, choices);
                prefix.remove(prefix.size() - 1);
            }
        }

        private Result result(final Candidate unblocked, final boolean exhaustive) {
            return new Result(Optional.ofNullable(best), Optional.ofNullable(unblocked), exhaustive,
                    domainComplete, budget.used() - startingNodes, List.copyOf(reasons));
        }

        /** Counts every expanded prefix, conservatively ignoring used-blocker pruning. */
        private long expansionUpperBound() {
            long prefixes = 1;
            long total = 0;
            for (final int attacker : attackers) {
                prefixes *= groups.get(attacker).size();
                total += prefixes;
                if (total > budget.remaining()) { return Long.MAX_VALUE; }
            }
            return total;
        }

        private void enumerate(final int depth, final Map<Integer, List<Integer>> blocks, final Set<Integer> used) {
            if (depth == attackers.size()) { return; }
            final int attacker = attackers.get(depth);
            for (final List<Integer> group : options(attacker, used)) {
                if (budget.remaining() == 0) { interrupted = true; return; }
                if (!group.isEmpty()) { blocks.put(attacker, group); used.addAll(group); }
                evaluate(blocks);
                enumerate(depth + 1, blocks, used);
                if (!group.isEmpty()) {
                    blocks.remove(attacker);
                    used.removeAll(group);
                    final List<Integer> fixed = fixedBlocks.getOrDefault(attacker, List.of());
                    if (!fixed.isEmpty()) { blocks.put(attacker, fixed); used.addAll(fixed); }
                }
            }
        }

        /** First legal completion, not a greedy claim that competing forced groups are impossible. */
        private Candidate findCompletion(final int depth, final Map<Integer, List<Integer>> blocks, final Set<Integer> used) {
            if (depth == attackers.size()) { return evaluate(blocks); }
            final int attacker = attackers.get(depth);
            for (final List<Integer> group : options(attacker, used)) {
                if (!budget.tryConsume()) { interrupted = true; return null; }
                if (!group.isEmpty()) { blocks.put(attacker, group); used.addAll(group); }
                final Candidate candidate = findCompletion(depth + 1, blocks, used);
                blocks.remove(attacker);
                used.removeAll(group);
                final List<Integer> fixed = fixedBlocks.getOrDefault(attacker, List.of());
                if (!fixed.isEmpty()) { blocks.put(attacker, fixed); used.addAll(fixed); }
                if (candidate != null) { return candidate; }
            }
            return null;
        }

        private List<List<Integer>> options(final int attacker, final Set<Integer> used) {
            final List<Integer> fixed = fixedBlocks.getOrDefault(attacker, List.of());
            return groups.get(attacker).stream().filter(group -> group.stream()
                    .noneMatch(id -> used.contains(id) && !fixed.contains(id))).toList();
        }

        private void beam(final Candidate unblocked) {
            List<Candidate> frontier = List.of(unblocked);
            for (final int attacker : attackers) {
                final List<Candidate> expanded = new ArrayList<>();
                for (final Candidate prefix : frontier) {
                    final Map<Integer, List<Integer>> blocks = prefix.assignment().blockersByAttacker();
                    final Set<Integer> used = new HashSet<>();
                    blocks.forEach((id, group) -> { if (id != attacker) { used.addAll(group); } });
                    for (final List<Integer> group : options(attacker, used)) {
                        if (budget.remaining() == 0) { return; }
                        final Map<Integer, List<Integer>> next = new HashMap<>(blocks);
                        if (!group.isEmpty()) { next.put(attacker, group); }
                        final Candidate candidate = evaluate(next);
                        if (candidate != null) { expanded.add(candidate); }
                    }
                }
                frontier = expanded.stream().sorted(ordering).limit(beamWidth).toList();
            }
        }

        private void improve() {
            for (int pass = 0; pass < 2 && best != null; pass++) {
                final Candidate initial = best;
                final Map<Integer, List<Integer>> blocks = initial.assignment().blockersByAttacker();
                for (final int attacker : attackers) {
                    final Set<Integer> used = new HashSet<>();
                    blocks.forEach((id, group) -> { if (id != attacker) { used.addAll(group); } });
                    for (final List<Integer> group : options(attacker, used)) {
                        if (budget.remaining() == 0) { return; }
                        final Map<Integer, List<Integer>> next = new HashMap<>(blocks);
                        next.remove(attacker);
                        if (!group.isEmpty()) { next.put(attacker, group); }
                        evaluate(next);
                    }
                    for (final int other : attackers) {
                        if (other <= attacker || !blocks.containsKey(attacker) && !blocks.containsKey(other)) { continue; }
                        if (budget.remaining() == 0) { return; }
                        final Map<Integer, List<Integer>> next = new HashMap<>(blocks);
                        next.remove(attacker);
                        next.remove(other);
                        if (blocks.containsKey(other)) { next.put(attacker, blocks.get(other)); }
                        if (blocks.containsKey(attacker)) { next.put(other, blocks.get(attacker)); }
                        evaluate(next);
                    }
                }
                if (best == initial) { return; }
            }
        }

        private Candidate evaluate(final Map<Integer, List<Integer>> blocks) {
            if (fixedBlocks.entrySet().stream().anyMatch(entry ->
                    !blocks.getOrDefault(entry.getKey(), List.of()).containsAll(entry.getValue()))) { return null; }
            if (!budget.tryConsume()) { interrupted = true; return null; }
            if (blocks.entrySet().stream().anyMatch(entry -> !snapshot.creatures().get(entry.getKey())
                    .permitsBlockerCount(entry.getValue().size()))) { return null; }
            CombatAssignment assignment = new CombatAssignment(snapshot.attackersToDefenders(), blocks);
            CombatProjection projection;
            Optional<CombatDamagePlan> damagePlan = Optional.empty();
            final boolean allocationNeeded = blocks.entrySet().stream().anyMatch(entry -> entry.getValue().size() > 1
                    || snapshot.creatures().get(entry.getKey()).trample()
                    && entry.getValue().stream().anyMatch(id -> snapshot.creatures().get(id).indestructible()));
            if (allocationNeeded) {
                final CombatDamageOptimizer.Result allocations = CombatDamageOptimizer.optimizeBlockGroupsWithCandidateAdjustment(snapshot, assignment, values, budget,
                        candidate -> CombatOutcomePredictor.add(CombatOutcomePredictor.add(pressure(candidate.projection()), exchangePreference(candidate.projection())),
                                chumpAdjustment(candidate.assignment(), candidate.projection(), candidate.damagePlan())), this::replyPriority);
                interrupted |= !allocations.exhaustive();
                domainComplete &= allocations.outcomeSupported();
                reasons.addAll(allocations.reasons());
                if (allocations.best().isEmpty()) { return null; }
                projection = allocations.best().orElseThrow().projection();
                assignment = allocations.best().orElseThrow().assignment();
                damagePlan = Optional.of(allocations.best().orElseThrow().damagePlan());
            } else {
                projection = CombatOutcomePredictor.predict(snapshot, assignment);
            }
            if (!projection.supported()) {
                domainComplete = false;
                reasons.addAll(projection.reasons());
            }
            if (!projection.supported() || !projection.available()) {
                if (best == null) { reasons.addAll(projection.reasons()); }
                return null;
            }
            final int pressure = pressure(projection);
            estimateReply(projection);
            final Optional<CombatSafetyEvaluator.FollowUp> followUp = Optional.ofNullable(forecasts.get(projection));
            final Candidate candidate = new Candidate(assignment, projection,
                    CombatTransitionValueEvaluator.evaluate(snapshot, values, projection), damagePlan, pressure,
                    exchangePreference(projection), followUp, chumpAdjustment(assignment, projection, damagePlan.orElse(null)));
            if (best == null || ordering.compare(candidate, best) < 0) { best = candidate; }
            return candidate;
        }

        private int chumpAdjustment(final CombatAssignment assignment, final CombatProjection projection, final CombatDamagePlan plan) {
            final var result = chumps.evaluate(assignment, projection, fixedBlocks, plan);
            if (!result.complete()) {
                domainComplete = false;
                if (budget.remaining() == 0) { interrupted = true; }
                reasons.add("Nonlethal chump counterfactual unavailable or shared budget exhausted");
            }
            return result.adjustment();
        }

        /** Capped tie-band preference, never a per-casualty reward or a terminal override. */
        private int exchangePreference(final CombatProjection projection) {
            if (snapshot.observingPlayerId() != snapshot.defendingPlayerId()
                    || projection.terminal() != CombatProjection.Terminal.NONE) { return 0; }
            final int own = snapshot.observingPlayerId();
            final boolean friendlyLost = projection.lostCreatures().stream().anyMatch(id -> snapshot.creatures().get(id).controllerId() == own);
            final boolean opposingLost = projection.lostCreatures().stream().anyMatch(id -> snapshot.creatures().get(id).controllerId() != own);
            if (!friendlyLost || !opposingLost) { return 0; }
            int advantage = 0;
            for (final int id : projection.survivors().keySet()) {
                final var creature = snapshot.creatures().get(id);
                if (creature.combatDamage() > 0) { advantage += creature.controllerId() == own ? 1 : -1; }
            }
            return Math.max(0, Math.min(5, advantage));
        }

        private int pressure(final CombatProjection projection) {
            if (replyEstimator != null) {
                final var reply = estimateReply(projection);
                // For attack responses, compare immediate + half-weight ordinary reply utility. The
                // no-attack constant is subtracted once by the outer declaration search.
                return reply == null || !reply.usable() ? 0
                        : CombatOutcomePredictor.add(CombatSafetyEvaluator.discountedFollowUpValue(reply.nonterminalUtility()),
                                continuationPressure(projection));
            }
            if (readiness != null && projection.terminal() == CombatProjection.Terminal.NONE
                    && (!baselineEvaluated || pressureEnabled)) {
                final CombatSafetyEvaluator.FollowUp forecast = forecasts.computeIfAbsent(projection,
                        state -> CombatSafetyEvaluator.nextAttack(snapshot, state, readiness, values, budget));
                reasons.addAll(forecast.reasons());
                if (!forecast.usable()) {
                    // Do not compare a complete baseline with an optimistic partial reply.
                    domainComplete = false;
                    reasons.add("A complete public follow-up estimate is required for pressure ranking");
                }
                if (!baselineEvaluated) {
                    baselineFollowUp = forecast;
                    pressureEnabled = forecast.usable();
                } else if (forecast.usable()) {
                    return CombatSafetyEvaluator.incrementalPressure(baselineFollowUp, forecast);
                }
            }
            return 0;
        }

        private int continuationPressure(final CombatProjection projection) {
            if (continuationEstimator == null) { return 0; }
            final var forecast = continuations.computeIfAbsent(projection, continuationEstimator);
            reasons.addAll(forecast.reasons());
            if (!forecast.usable()) {
                domainComplete = false;
                interrupted |= !forecast.candidateSearchComplete();
                reasons.add("A complete stressed next-attack estimate is required for two-turn pressure ranking");
            }
            return forecast.value();
        }

        private CombatSafetyEvaluator.FollowUp estimateReply(final CombatProjection projection) {
            if (replyEstimator == null || projection.terminal() != CombatProjection.Terminal.NONE) { return null; }
            final var reply = forecasts.computeIfAbsent(projection, replyEstimator);
            reasons.addAll(reply.reasons());
            if (!reply.usable()) {
                domainComplete = false;
                reasons.add("A complete opposing reply is required for attack safety");
            }
            return reply;
        }

        private int replyPriority(final CombatProjection left, final CombatProjection right) {
            final var a = estimateReply(left);
            final var b = estimateReply(right);
            return a == null || b == null ? 0 : Boolean.compare(b.lethalOpportunity(), a.lethalOpportunity());
        }

        /** Below-zero life is still meaningful when every legal defense loses. Preserve terminal
         * priority, then minimize incoming player damage before choosing among equal-damage trades.
         * TODO: Other loss conditions need their own least-bad metric when projected here. */
        private int hopelessDefensePriority(final CombatProjection left, final CombatProjection right) {
            final boolean observingDefender = snapshot.observingPlayerId() == snapshot.defendingPlayerId();
            final var defeatedDefender = observingDefender ? CombatProjection.Terminal.LOSS : CombatProjection.Terminal.WIN;
            if (left.terminal() != defeatedDefender || right.terminal() != defeatedDefender) { return 0; }
            final long leftDamage = left.batches().stream().mapToLong(batch ->
                    batch.playerDamage().getOrDefault(snapshot.defendingPlayerId(), 0)).sum();
            final long rightDamage = right.batches().stream().mapToLong(batch ->
                    batch.playerDamage().getOrDefault(snapshot.defendingPlayerId(), 0)).sum();
            final int damagePreference = Long.compare(rightDamage, leftDamage);
            if (damagePreference != 0) { return observingDefender ? damagePreference : -damagePreference; }
            final int lessDamage = Integer.compare(left.playerLifeAfter().get(snapshot.defendingPlayerId()),
                    right.playerLifeAfter().get(snapshot.defendingPlayerId()));
            return observingDefender ? lessDamage : -lessDamage;
        }

        private int compareAssignments(final CombatAssignment left, final CombatAssignment right) {
            for (final int attacker : attackers.stream().sorted().toList()) {
                final List<Integer> a = left.blockersByAttacker().getOrDefault(attacker, List.of());
                final List<Integer> b = right.blockersByAttacker().getOrDefault(attacker, List.of());
                for (int index = 0; index < Math.min(a.size(), b.size()); index++) {
                    if (!a.get(index).equals(b.get(index))) { return Integer.compare(a.get(index), b.get(index)); }
                }
                if (a.size() != b.size()) { return Integer.compare(a.size(), b.size()); }
            }
            return 0;
        }
    }

}
