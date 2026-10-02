package forge.ai.combat;

import java.util.concurrent.atomic.AtomicLong;

import org.testng.Assert;
import org.testng.annotations.Test;

/** Shared node/time caps and validation headroom, independent of machine speed. */
public class CombatSearchBudgetTest {
    @Test
    public void searchExhaustionLeavesReservedNodesWithoutResettingAccounting() {
        final AtomicLong clock = new AtomicLong();
        final var total = new CombatSearchBudget(10, 100, clock::get);
        final var planning = total.planningAllowance();
        for (int index = 0; index < 8; index++) { Assert.assertTrue(planning.tryConsume()); }
        Assert.assertFalse(planning.tryConsume());
        Assert.assertEquals(total.used(), 8);
        Assert.assertEquals(total.remaining(), 2);
        Assert.assertTrue(total.tryConsume());
        Assert.assertTrue(total.tryConsume());
        Assert.assertFalse(total.tryConsume());
        Assert.assertEquals(planning.used(), 10);
        Assert.assertEquals(planning.remaining(), 0);
    }

    @Test
    public void planningDeadlineLeavesValidationTimeButNeverExtendsTheTotalDeadline() {
        final AtomicLong clock = new AtomicLong();
        final var total = new CombatSearchBudget(5000, 100, clock::get);
        clock.set(30_000_000);
        final var planning = total.planningAllowance();
        clock.set(79_000_000);
        Assert.assertTrue(planning.tryConsume());
        clock.set(80_000_000);
        Assert.assertFalse(planning.tryConsume());
        Assert.assertTrue(total.tryConsume());
        Assert.assertEquals(total.elapsedMillis(), 80L);
        clock.set(100_000_000);
        Assert.assertFalse(total.tryConsume());
        Assert.assertEquals(total.used(), 2);
    }

    @Test
    public void tinyOrAlreadySpentBudgetsCannotAcquireNewPlanningCapacity() {
        final AtomicLong clock = new AtomicLong();
        Assert.assertFalse(new CombatSearchBudget(0, 100, clock::get).planningAllowance().tryConsume());
        final var tiny = new CombatSearchBudget(1, 100, clock::get);
        Assert.assertFalse(tiny.planningAllowance().tryConsume());
        Assert.assertTrue(tiny.tryConsume());
        final var spent = new CombatSearchBudget(10, 100, clock::get);
        for (int index = 0; index < 9; index++) { Assert.assertTrue(spent.tryConsume()); }
        Assert.assertFalse(spent.planningAllowance().tryConsume());
        Assert.assertEquals(spent.remaining(), 1);
    }
}
