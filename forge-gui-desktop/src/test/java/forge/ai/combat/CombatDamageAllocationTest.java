package forge.ai.combat;

import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

public class CombatDamageAllocationTest {
    @Test
    public void legacyOrderRequiresLethalBeforeMovingToTheNextBlocker() {
        final List<CombatDamageAllocation.Target> targets = List.of(
                new CombatDamageAllocation.Target(10, 2), new CombatDamageAllocation.Target(11, 2));
        final CombatDamageAllocation.Result modern = CombatDamageAllocation.enumerate(3, targets, false, false,
                new CombatSearchBudget(100));
        final CombatDamageAllocation.Result legacy = CombatDamageAllocation.enumerate(3, targets, false, true,
                new CombatSearchBudget(100));
        Assert.assertTrue(modern.exhaustive() && legacy.exhaustive());
        Assert.assertEquals(modern.allocations().size(), 4);
        Assert.assertEquals(legacy.allocations().size(), 2);
        Assert.assertTrue(modern.allocations().contains(new CombatDamageAllocation.Allocation(Map.of(11, 3), 0)));
        Assert.assertFalse(legacy.allocations().contains(new CombatDamageAllocation.Allocation(Map.of(11, 3), 0)));
        legacy.allocations().forEach(allocation -> Assert.assertTrue(allocation.creatureDamage().get(10) >= 2));
    }

    @Test
    public void trampleOnlySpillsAfterEveryBlockerHasAssignmentLethal() {
        final List<CombatDamageAllocation.Target> targets = List.of(
                new CombatDamageAllocation.Target(10, 2), new CombatDamageAllocation.Target(11, 2));
        final CombatDamageAllocation.Result result = CombatDamageAllocation.enumerate(5, targets, true, false,
                new CombatSearchBudget(100));
        Assert.assertTrue(result.exhaustive());
        Assert.assertEquals(result.allocations().size(), 7);
        for (final var allocation : result.allocations()) {
            Assert.assertTrue(CombatDamageAllocation.isLegal(5, targets, true, false, allocation));
            Assert.assertEquals(allocation.creatureDamage().values().stream().mapToInt(Integer::intValue).sum()
                    + allocation.defenderDamage(), 5);
            if (allocation.defenderDamage() > 0) {
                Assert.assertEquals(allocation, new CombatDamageAllocation.Allocation(Map.of(10, 2, 11, 2), 1));
            }
        }
        Assert.assertFalse(CombatDamageAllocation.isLegal(5, targets, true, false,
                new CombatDamageAllocation.Allocation(Map.of(10, 1, 11, 2), 2)));
        Assert.assertFalse(CombatDamageAllocation.isLegal(5, targets, true, false,
                new CombatDamageAllocation.Allocation(Map.of(10, -1, 11, 6), 0)));
    }

    @Test
    public void deathtouchAndIndestructibleUseAssignmentRatherThanDestroyability() {
        // The caller supplies assignment-lethal, including one for deathtouch even if
        // the blocker cannot actually be destroyed. Killing is a separate projection rule.
        final CombatDamageAllocation.Result result = CombatDamageAllocation.enumerate(4,
                List.of(new CombatDamageAllocation.Target(10, 1)), true, false, new CombatSearchBudget(100));
        Assert.assertEquals(result.allocations().size(), 4);
        Assert.assertTrue(result.allocations().contains(new CombatDamageAllocation.Allocation(Map.of(10, 1), 3)));
        Assert.assertTrue(result.allocations().contains(new CombatDamageAllocation.Allocation(Map.of(10, 4), 0)));
        Assert.assertFalse(result.allocations().contains(new CombatDamageAllocation.Allocation(Map.of(), 4)));
    }

    @Test
    public void blockedStatusIsPreservedWhenNoBlockersRemain() {
        final CombatDamageAllocation.Result ordinary = CombatDamageAllocation.enumerate(4, List.of(), false, false,
                new CombatSearchBudget(10));
        final CombatDamageAllocation.Result trample = CombatDamageAllocation.enumerate(4, List.of(), true, false,
                new CombatSearchBudget(10));
        Assert.assertEquals(ordinary.allocations(), List.of(new CombatDamageAllocation.Allocation(Map.of(), 0)));
        Assert.assertEquals(trample.allocations(), List.of(new CombatDamageAllocation.Allocation(Map.of(), 4)));
    }

    @Test
    public void enormousDamageAndSharedAllowanceStayBounded() {
        final CombatSearchBudget budget = new CombatSearchBudget(20);
        Assert.assertTrue(budget.tryConsume());
        final CombatDamageAllocation.Result result = CombatDamageAllocation.enumerate(Integer.MAX_VALUE,
                List.of(new CombatDamageAllocation.Target(10, Integer.MAX_VALUE),
                        new CombatDamageAllocation.Target(11, 1)), false, true, budget);
        Assert.assertFalse(result.exhaustive());
        Assert.assertEquals(result.nodes(), 19);
        Assert.assertEquals(budget.used(), 20);
        Assert.assertTrue(result.allocations().isEmpty(), "Unsearched allocations are not impossible allocations");
    }

    @Test
    public void allocationsAreFrozenAndInputsAreValidated() {
        final CombatDamageAllocation.Result result = CombatDamageAllocation.enumerate(2,
                List.of(new CombatDamageAllocation.Target(10, 1)), false, false, new CombatSearchBudget(10));
        Assert.expectThrows(UnsupportedOperationException.class, () -> result.allocations().clear());
        Assert.expectThrows(UnsupportedOperationException.class,
                () -> result.allocations().get(0).creatureDamage().put(10, 9));
        Assert.expectThrows(IllegalArgumentException.class, () -> CombatDamageAllocation.enumerate(2,
                List.of(new CombatDamageAllocation.Target(10, 1), new CombatDamageAllocation.Target(10, 1)),
                false, false, new CombatSearchBudget(10)));
        Assert.expectThrows(IllegalArgumentException.class, () -> CombatDamageAllocation.enumerate(-1,
                List.of(), false, false, new CombatSearchBudget(10)));
    }
}
