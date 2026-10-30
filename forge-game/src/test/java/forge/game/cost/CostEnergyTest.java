package forge.game.cost;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotSame;

public class CostEnergyTest {
    @Test
    public void repeatedEnergyCostsAccumulateWithoutChangingInputs() {
        Cost base = new Cost("U R PayEnergy<2>", false);
        Cost kicker = new Cost("PayEnergy<3>", false);
        Cost total = base.copy();

        total.add(kicker);
        total.add(kicker);

        assertEquals(total.getCostEnergy().getAmount(), "8");
        assertEquals(total.getCostParts().stream().filter(CostPayEnergy.class::isInstance).count(), 1L);
        assertEquals(total.getCostMana().getMana().toString(), "{U}{R}");
        assertEquals(base.getCostEnergy().getAmount(), "2");
        assertEquals(kicker.getCostEnergy().getAmount(), "3");
        assertNotSame(total.getCostEnergy(), base.getCostEnergy());
        assertNotSame(total.getCostEnergy(), kicker.getCostEnergy());
    }

    @Test
    public void separateAdditionalCostsStaySeparateWhenMergingIsDisabled() {
        Cost total = new Cost("PayEnergy<2>", false);
        total.add(new Cost("PayEnergy<3>", false), false);

        assertEquals(total.getCostParts().stream().filter(CostPayEnergy.class::isInstance).count(), 2L);
    }

    @Test
    public void symbolicEnergyCostsStaySeparate() {
        Cost total = new Cost("PayEnergy<X>", false);
        total.add(new Cost("PayEnergy<3>", false));

        assertEquals(total.getCostParts().stream().filter(CostPayEnergy.class::isInstance).count(), 2L);
    }
}
