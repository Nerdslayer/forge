package forge.ai.effect;

import forge.ai.MastermindEffort;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.Assert;
import org.testng.annotations.Test;

public class EffectEvaluationBudgetTest {
    @Test
    public void effortScalesTheSharedDeadlineAndPaymentNodeCap() {
        final var clock = new AtomicLong();
        final var low = EffectEvaluationBudget.fromTimeoutMillis(100, MastermindEffort.LOW, clock::get);
        final var max = EffectEvaluationBudget.fromTimeoutMillis(100, MastermindEffort.MAX, clock::get);
        Assert.assertEquals(low.scale(10_000), 10_000);
        Assert.assertEquals(max.scale(10_000), 80_000);
        clock.set(100_000_000);
        Assert.expectThrows(EffectEvaluationBudget.Exceeded.class, low::check);
        max.check();
        clock.set(800_000_000);
        Assert.expectThrows(EffectEvaluationBudget.Exceeded.class, max::check);
    }
}
