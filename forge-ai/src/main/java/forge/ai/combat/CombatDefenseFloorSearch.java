package forge.ai.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Bounded immediate defense, established before optional combat forecasts. No new scoring policy. */
final class CombatDefenseFloorSearch {
    private static final int MAX_TRIALS = 256;
    private static final int MAX_GROUPS = 16;
    private final PublicCombatSnapshot snapshot;
    private final Map<Integer, List<Integer>> fixed;
    private final CombatSearchBudget budget;
    private final PreparedCombatValuation values;
    private final CombatBlockSearch.ImmediateEvaluator evaluator;
    private final List<CombatBlockSearch.Candidate> verified = new ArrayList<>();
    private final Set<CombatAssignment> attempted = new HashSet<>();
    private final List<Integer> attackers;
    private CombatBlockSearch.Candidate baseline;
    private CombatBlockSearch.Candidate best;
    private int trials;
    private int groups;
    private int pairs;
    private boolean singletonComplete;
    private int illegal;
    private int duplicateProposals;
    private String phase = "baseline";
    private String bestSource = "unavailable";

    private CombatDefenseFloorSearch(final PublicCombatSnapshot snapshot0, final PreparedCombatValuation values0,
            final Map<Integer, List<Integer>> fixed0, final CombatSearchBudget budget0) {
        snapshot = snapshot0;
        values = values0;
        fixed = fixed0;
        budget = budget0;
        evaluator = new CombatBlockSearch.ImmediateEvaluator(snapshot, values, fixed, budget);
        attackers = snapshot.attackersToDefenders().keySet().stream().sorted().toList();
    }

    static CombatBlockSearch.Seeds prepare(final PublicCombatSnapshot snapshot, final PreparedCombatValuation values,
            final Map<Integer, List<Integer>> fixed, final CombatSearchBudget budget) {
        return new CombatDefenseFloorSearch(snapshot, values, fixed, budget).run();
    }

    private CombatBlockSearch.Seeds run() {
        final int started = budget.used();
        final long startMillis = budget.elapsedMillis();
        if (snapshot.unsupportedReasons().isEmpty() && snapshot.unavailableReasons().isEmpty()) {
            final var reference = CombatDefenseSeedBuilder.baseline(snapshot, fixed, budget);
            if (reference != null) { baseline = trial(reference.blockersByAttacker(), budget); }
            if (baseline != null) {
                // Preserve broad survival/scarcity proposals, without starving direct safe-block discovery.
                final var coverage = budget.nodeAllowance(budget.remaining() / 4);
                phase = "whole-board coverage";
                final var proposals = CombatDefenseSeedBuilder.build(snapshot, values, fixed, coverage);
                for (final var proposal : proposals) { trial(proposal.blockersByAttacker(), coverage); }
                phase = "baseline singleton scan";
                singletonComplete = singles(baseline.assignment().blockersByAttacker());
                // Recompute marginals after accepting a complete assignment. Alternatives retain scarce-block coverage.
                for (int sweep = 0; sweep < 2 && capacity(); sweep++) {
                    final var before = best;
                    final var incumbents = retained();
                    for (final var incumbent : incumbents) {
                        if (!capacity()) { break; }
                        final var blocks = incumbent.assignment().blockersByAttacker();
                        phase = "single replacement/removal";
                        singles(blocks);
                        phase = "scarcity swap";
                        swaps(blocks);
                        phase = "small gang/reinforcement";
                        gangs(blocks);
                    }
                    if (best == before) { break; }
                }
            }
        }
        final List<String> reasons = new ArrayList<>();
        reasons.addAll(snapshot.unsupportedReasons());
        reasons.addAll(snapshot.unavailableReasons());
        reasons.add("Immediate defensive floor: mode=immediate, nodes=" + (budget.used() - started)
                + ", millis=" + (budget.elapsedMillis() - startMillis) + ", trials=" + trials
                + ", verified=" + verified.size() + ", interrupted=" + evaluator.interrupted()
                + ", unsupported=" + evaluator.unsupported() + ", unavailable=" + evaluator.unavailable() + ", illegal=" + illegal
                + ", cacheHits=" + evaluator.cacheHits() + ", singlePairs=" + pairs
                + ", duplicateProposalsSkipped=" + duplicateProposals
                + ", singletonScanComplete=" + singletonComplete + ", groups=" + groups
                + ", refinementBounded=" + (!capacity() || groups == MAX_GROUPS)
                + ", bestSource=" + bestSource + ", baseline=" + describe(baseline) + ", best=" + describe(best));
        reasons.addAll(evaluator.failures());
        final var assignments = retained().stream().map(CombatBlockSearch.Candidate::assignment).toList();
        return new CombatBlockSearch.Seeds(new CombatBlockSearch.Result(Optional.ofNullable(best), Optional.ofNullable(baseline),
                false, best != null, budget.used() - started, reasons, evaluator.evaluations(), evaluator.cacheHits(), best != null), assignments);
    }

    private static String describe(final CombatBlockSearch.Candidate candidate) {
        if (candidate == null) { return "unavailable"; }
        final String description = candidate.assignment().blockersByAttacker()
                + "/terminal=" + candidate.projection().terminal() + "/life=" + candidate.projection().playerLifeAfter()
                + "/lost=" + candidate.projection().lostPermanents() + "/loss=" + candidate.score().permanentLoss().total()
                + "/lifeUtility=" + candidate.score().lifeUtility() + "/outcomes=" + candidate.score().outcomeUtility()
                + "/adjusted=" + candidate.adjustedTotal();
        return description.length() <= 1200 ? description : description.substring(0, 1200) + "...[summary truncated]";
    }

    private List<CombatBlockSearch.Candidate> retained() {
        final List<CombatBlockSearch.Candidate> result = new ArrayList<>();
        if (baseline == null) { return result; }
        result.add(baseline); // Never redefine the advanced mode's pressure reference as the best floor.
        final Set<CombatAssignment> seen = new HashSet<>();
        seen.add(baseline.assignment());
        verified.stream().sorted(evaluator::compare).filter(candidate -> seen.add(candidate.assignment()))
                .limit(3).forEach(result::add);
        return result;
    }

    private boolean capacity() { return trials < MAX_TRIALS && budget.remaining() > 0; }

    private CombatBlockSearch.Candidate trial(final Map<Integer, List<Integer>> blocks, final CombatSearchBudget allowance) {
        if (!capacity() || allowance.remaining() == 0) { return null; }
        final var assignment = new CombatAssignment(snapshot.attackersToDefenders(), blocks);
        if (attempted.contains(assignment)) { duplicateProposals++; return null; }
        trials++;
        // Charge proposal inspection too. Illegal atomic groups are understood exclusions, not incomplete projections.
        if (!allowance.tryConsume()) { return null; }
        if (!legal(blocks)) { illegal++; attempted.add(assignment); return null; }
        final var candidate = evaluator.evaluate(assignment, allowance);
        if (candidate != null) {
            attempted.add(assignment);
            verified.add(candidate);
            if (best == null || evaluator.compare(candidate, best) < 0) { best = candidate; bestSource = phase; }
        }
        return candidate;
    }

    private boolean legal(final Map<Integer, List<Integer>> blocks) {
        final Set<Integer> used = new HashSet<>();
        for (final var entry : blocks.entrySet()) {
            final var card = snapshot.creatures().get(entry.getKey());
            if (card == null || !snapshot.attackersToDefenders().containsKey(entry.getKey())
                    || !card.permitsBlockerCount(entry.getValue().size())
                    || !entry.getValue().containsAll(fixed.getOrDefault(entry.getKey(), List.of()))) { return false; }
            for (final int blocker : entry.getValue()) {
                if (!used.add(blocker) || !snapshot.legalBlockers().getOrDefault(entry.getKey(), Set.of()).contains(blocker)) { return false; }
            }
        }
        return fixed.entrySet().stream().allMatch(entry -> blocks.getOrDefault(entry.getKey(), List.of()).containsAll(entry.getValue()));
    }

    /** Round-robin across attackers; replacements and optional removals are full-declaration trials. */
    private boolean singles(final Map<Integer, List<Integer>> blocks) {
        final Map<Integer, List<Integer>> options = new LinkedHashMap<>();
        for (final int attacker : attackers) {
            if (!budget.tryConsume()) { return false; }
            options.put(attacker, snapshot.legalBlockers().getOrDefault(attacker, Set.of()).stream().sorted().toList());
            final var required = fixed.getOrDefault(attacker, List.of());
            if (!blocks.getOrDefault(attacker, List.of()).equals(required)) { replace(blocks, attacker, required, budget.nodeAllowance(64)); }
        }
        for (int index = 0; ; index++) {
            boolean any = false;
            for (final int attacker : attackers) {
                if (!capacity()) { return false; }
                if (index >= options.get(attacker).size()) { continue; }
                any = true;
                if (!budget.tryConsume()) { return false; }
                final int blocker = options.get(attacker).get(index);
                if (usedElsewhere(blocks, attacker).contains(blocker)) { continue; }
                final var group = new ArrayList<>(fixed.getOrDefault(attacker, List.of()));
                if (!group.contains(blocker)) { group.add(blocker); }
                pairs++;
                replace(blocks, attacker, group, budget.nodeAllowance(64));
                // Also reinforce existing gangs/tramplers, rather than replacing away their useful members.
                final var reinforced = new ArrayList<>(blocks.getOrDefault(attacker, List.of()));
                if (!reinforced.isEmpty() && reinforced.size() < 4 && !reinforced.contains(blocker)) {
                    reinforced.add(blocker);
                    replace(blocks, attacker, reinforced, budget.nodeAllowance(128));
                }
            }
            if (!any) { return true; }
        }
    }

    private Set<Integer> usedElsewhere(final Map<Integer, List<Integer>> blocks, final int attacker) {
        final Set<Integer> used = new HashSet<>();
        blocks.forEach((id, group) -> { if (id != attacker) { used.addAll(group); } });
        return used;
    }

    private void replace(final Map<Integer, List<Integer>> blocks, final int attacker, final List<Integer> group,
            final CombatSearchBudget allowance) {
        final var proposal = new LinkedHashMap<>(blocks);
        if (group.isEmpty()) { proposal.remove(attacker); } else { proposal.put(attacker, List.copyOf(group)); }
        trial(proposal, allowance);
    }

    /** One-hop displacement and swaps, never moving fixed members. Larger matching problems remain bounded. */
    private void swaps(final Map<Integer, List<Integer>> blocks) {
        for (final int donor : attackers) {
            final var group = blocks.getOrDefault(donor, List.of());
            if (group.size() != 1 || !fixed.getOrDefault(donor, List.of()).isEmpty()) { continue; }
            final int moving = group.get(0);
            for (final int target : attackers) {
                if (!capacity() || !budget.tryConsume()) { return; }
                if (target == donor || !snapshot.legalBlockers().getOrDefault(target, Set.of()).contains(moving)
                        || !fixed.getOrDefault(target, List.of()).isEmpty()) { continue; }
                final var old = blocks.getOrDefault(target, List.of());
                if (old.size() > 1) { continue; }
                for (final int replacement : snapshot.legalBlockers().getOrDefault(donor, Set.of()).stream().sorted().toList()) {
                    if (!capacity() || !budget.tryConsume()) { return; }
                    if (replacement == moving || usedElsewhere(blocks, donor).contains(replacement) && !old.contains(replacement)) { continue; }
                    final var proposal = new LinkedHashMap<>(blocks);
                    proposal.put(donor, List.of(replacement));
                    proposal.put(target, List.of(moving));
                    trial(proposal, budget.nodeAllowance(64));
                }
            }
        }
    }

    private void gangs(final Map<Integer, List<Integer>> blocks) {
        for (final int attacker : attackers) {
            if (!capacity() || groups >= MAX_GROUPS || !budget.tryConsume()) { return; }
            final var body = snapshot.creatures().get(attacker);
            if (body.maximumBlockers() < 2 || body.minimumBlockers() > 4) { continue; }
            final var used = usedElsewhere(blocks, attacker);
            final var pool = snapshot.legalBlockers().getOrDefault(attacker, Set.of()).stream().filter(id -> !used.contains(id)).sorted().toList();
            final List<Comparator<Integer>> orders = List.of(
                    Comparator.<Integer>comparingInt(id -> snapshot.creatures().get(id).combatDamage()).reversed(),
                    Comparator.<Integer>comparingInt(id -> snapshot.creatures().get(id).toughness() - snapshot.creatures().get(id).markedDamage()).reversed(),
                    Comparator.<Integer>comparingLong(id -> Math.abs((long) values.evaluateLosses(Set.of(id)).total())),
                    Comparator.<Integer>comparingLong(id -> snapshot.legalBlockers().values().stream().filter(set -> set.contains(id)).count()));
            final Set<List<Integer>> proposals = new LinkedHashSet<>();
            for (final var order : orders) {
                final var sorted = pool.stream().sorted(order.thenComparingInt(Integer::intValue)).toList();
                for (int size = Math.max(2, body.minimumBlockers()); size <= Math.min(Math.min(4, body.maximumBlockers()), sorted.size()); size++) {
                    final var group = new LinkedHashSet<>(fixed.getOrDefault(attacker, List.of()));
                    for (final int id : sorted) { if (group.size() < size) { group.add(id); } }
                    if (group.size() <= 4) { proposals.add(group.stream().sorted().toList()); }
                }
            }
            for (final var group : proposals) {
                if (!capacity() || groups >= MAX_GROUPS) { return; }
                final var proposed = new LinkedHashMap<>(blocks);
                proposed.put(attacker, group);
                if (attempted.contains(new CombatAssignment(snapshot.attackersToDefenders(), proposed))) { continue; }
                groups++;
                // TODO: Legacy four-member order coverage remains explicitly incomplete in the optimizer.
                // Larger gangs, multi-displacement matching and unmodeled global requirements are deferred.
                replace(blocks, attacker, group, budget.nodeAllowance(512));
            }
        }
    }
}
