package forge.ai.controller;

import forge.ai.AiCostDecision;
import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.cost.Cost;
import forge.game.cost.CostPayment;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class EnergyCostPaymentTest extends SimulationTest {
    @Test
    public void combinedEnergyCostRequiresAndConsumesTheFullAmount() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card source = addCardToZone("Lightning Bolt", player, ZoneType.Hand);
        SpellAbility spell = source.getFirstSpellAbility();
        spell.setActivatingPlayer(player);
        Cost total = new Cost("PayEnergy<3>", false);
        total.add(new Cost("PayEnergy<3>", false));
        spell.setPayCosts(total);

        player.setCounters(CounterEnumType.ENERGY, 5, player, false);
        assertFalse(CostPayment.canPayAdditionalCosts(total, spell, false));

        player.setCounters(CounterEnumType.ENERGY, 6, player, false);
        assertTrue(CostPayment.canPayAdditionalCosts(total, spell, false));
        assertTrue(new CostPayment(total, spell).payCost(new AiCostDecision(player, spell, false)));
        assertEquals(player.getCounters(CounterEnumType.ENERGY), 0);
    }

    @Test
    public void aiLimitsEnergyMultikickerToAvailableEnergy() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        addCard("Mountain", player);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player);
        Card source = addCardToZone("Lightning Bolt", player, ZoneType.Hand);
        source.addIntrinsicKeyword("Multikicker:PayEnergy<3>");
        SpellAbility spell = source.getFirstSpellAbility();
        spell.setActivatingPlayer(player);
        var keyword = source.getKeywords(Keyword.MULTIKICKER).iterator().next();

        for (int energy : new int[] {0, 2, 3, 5, 6, 8, 9}) {
            player.setCounters(CounterEnumType.ENERGY, energy, player, false);
            // A finite ceiling makes the original over-selection fail without hanging the test.
            int kicks = player.getController().chooseNumberForKeywordCost(spell,
                    new Cost("PayEnergy<3>", false), keyword, "Choose energy multikicker", 4);
            assertEquals(kicks, energy / 3, "Energy available: " + energy);
        }
    }
}
