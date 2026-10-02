package forge.ai.combat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

public class GreedyAttackCandidatesTest {
    private record Option(List<Integer> attackers, int score) { }

    @Test
    public void harmfulAdditionIsSkippedAndLaterProfitableAdditionIsStillTried() {
        final Map<List<Integer>, Integer> scores = Map.of(List.of(), 0, List.of(1), 50, List.of(2), 20, List.of(3), 15,
                List.of(4), -10, List.of(1, 2), 70, List.of(1, 2, 3), 60, List.of(1, 2, 4), 90, List.of(1, 2, 3, 4), 80);
        final Map<List<Integer>, Integer> calls = new HashMap<>();
        final var result = GreedyAttackCandidates.select(List.of(), List.of(1, 2, 3, 4), group -> {
            calls.merge(group, 1, Integer::sum);
            return new Option(group, scores.get(group));
        }, java.util.Comparator.comparingInt(Option::score), id -> id);
        Assert.assertTrue(result.complete());
        Assert.assertEquals(result.best().orElseThrow().attackers(), List.of(1, 2, 4));
        Assert.assertEquals(calls.get(List.of(1)).intValue(), 1, "The first ranked singleton must reuse its full evaluation");
        Assert.assertTrue(calls.values().stream().allMatch(count -> count == 1));
    }

    @Test
    public void allOutCanOvercomeBadSingletonsAndAnEqualScoreFrontierContinues() {
        final Map<List<Integer>, Integer> scores = Map.of(List.of(), 0, List.of(1), -10, List.of(2), -20, List.of(3), -30,
                List.of(1, 2), -10, List.of(1, 2, 3), 100);
        final var result = GreedyAttackCandidates.select(List.of(), List.of(1, 2, 3),
                group -> new Option(group, scores.get(group)), java.util.Comparator.comparingInt(Option::score), id -> id);
        Assert.assertTrue(result.complete());
        Assert.assertEquals(result.best().orElseThrow().attackers(), List.of(1, 2, 3));
    }

    @Test
    public void mandatoryAttackersRemainAndTiesNeverForceAnUnprofitableOptionalAttack() {
        final var result = GreedyAttackCandidates.select(List.of(9), List.of(1, 2),
                group -> new Option(group, 0), java.util.Comparator.comparingInt(Option::score), id -> id);
        Assert.assertTrue(result.complete());
        Assert.assertEquals(result.best().orElseThrow().attackers(), List.of(9));
        final var partial = GreedyAttackCandidates.select(List.of(), List.of(1, 2),
                group -> group.contains(2) ? null : new Option(group, 0), java.util.Comparator.comparingInt(Option::score), id -> id);
        Assert.assertFalse(partial.complete(), "Missing evaluations are not optimistic safe results");
    }

    @Test
    public void largeInterchangeableArmyUsesLinearJointEvaluationsAndReusesSingletons() {
        final List<Integer> attackers = java.util.stream.IntStream.rangeClosed(1, 16).boxed().toList();
        final Map<List<Integer>, Integer> calls = new HashMap<>();
        final var result = GreedyAttackCandidates.select(List.of(), attackers, group -> {
            calls.merge(group, 1, Integer::sum);
            return new Option(group, group.size());
        }, java.util.Comparator.comparingInt(Option::score), id -> 1);
        Assert.assertTrue(result.complete());
        Assert.assertEquals(result.best().orElseThrow().attackers(), attackers);
        Assert.assertEquals(result.evaluations(), 17);
        Assert.assertTrue(calls.values().stream().allMatch(count -> count == 1));
    }
}
