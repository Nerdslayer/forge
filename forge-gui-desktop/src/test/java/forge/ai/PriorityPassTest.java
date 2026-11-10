package forge.ai;

import java.util.List;

import forge.ai.effect.EarlyActionAffordability;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Test;

public class PriorityPassTest extends AITest {
    private AiController controllerWithPriorityChecks(final Game game, final Player ai) {
        final AiController controller = Mockito.spy(new AiController(ai, game));
        Mockito.doReturn(true).when(controller).getBoolProperty(AiProps.ENABLE_EARLY_ACTION_AFFORDABILITY);
        Mockito.doReturn(true).when(controller).getBoolProperty(AiProps.ENABLE_CAST_VALUE_TIEBREAK);
        Mockito.doReturn(true).when(controller).getBoolProperty(AiProps.ENABLE_ACTIVATION_VALUE_TIEBREAK);
        return controller;
    }

    private void putOpponentSpellOnStack(final Game game) {
        final Player opponent = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opponent);
        addCards("Mountain", 4, opponent);
        final Card card = addCardToZone("Hill Giant", opponent, ZoneType.Hand);
        final SpellAbility spell = card.getFirstSpellAbility();
        spell.setActivatingPlayer(opponent);
        ((PlayerControllerAi) opponent.getController()).playChosenSpellAbility(spell);
        Assert.assertFalse(game.getStack().isEmpty());
    }

    @Test
    public void unavailablePriorityActionsSkipBothValueTieBreakers() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        putOpponentSpellOnStack(game);
        addCard("Icy Manipulator", ai).setTapped(true);
        addCard("Pacification Array", ai).setTapped(true);
        Assert.assertTrue(ComputerUtilAbility.getSpellAbilities(ai.getCardsIn(ZoneType.Battlefield), ai).size() >= 2);

        final AiController controller = controllerWithPriorityChecks(game, ai);
        final var plays = controller.chooseSpellAbilityToPlay();
        Assert.assertTrue(plays == null || plays.isEmpty());
        Mockito.verify(controller, Mockito.never()).getBoolProperty(AiProps.ENABLE_CAST_VALUE_TIEBREAK);
        Mockito.verify(controller, Mockito.never()).getBoolProperty(AiProps.ENABLE_ACTIVATION_VALUE_TIEBREAK);
    }

    @Test
    public void legalFlashCounterPermanentStillReachesChooser() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        putOpponentSpellOnStack(game);
        addCards("Forest", 2, ai);
        addCards("Island", 2, ai);
        final Card counter = addCardToZone("Mystic Snake", ai, ZoneType.Hand);

        final var plays = controllerWithPriorityChecks(game, ai).chooseSpellAbilityToPlay();
        Assert.assertNotNull(plays);
        Assert.assertFalse(plays.isEmpty());
        Assert.assertSame(plays.get(0).getHostCard(), counter);
    }

    @Test
    public void affordableOfferingStillReachesChooser() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        putOpponentSpellOnStack(game);
        // The AI's normal Offering policy declines fodder with mana value <= 1.
        final Card artifact = addCard("Darksteel Forge", ai);
        for (int i = 0; i < 9; i++) {
            ai.getManaPool().addMana(new Mana((byte) ManaAtom.RED, artifact, null, ai));
        }
        final Card dragon = addCardToZone("Blast-Furnace Hellkite", ai, ZoneType.Hand);
        final SpellAbility spell = dragon.getFirstSpellAbility();
        spell.setActivatingPlayer(ai);
        Assert.assertFalse(spell.canCastTiming(ai));
        Assert.assertFalse(EarlyActionAffordability.noPriorityActionBeforeOrdering(ai, List.of(spell), true));

        final SpellAbility offering = GameActionUtil.addOptionalCosts(spell,
                GameActionUtil.getOptionalCostValues(spell));
        Assert.assertTrue(offering.isOffering() && offering.canPlay());
        Assert.assertTrue(ComputerUtilCost.canPayCost(offering, ai, false));

        final AiController controller = controllerWithPriorityChecks(game, ai);
        controller.chooseSpellAbilityToPlay();
        Mockito.verify(controller).getBoolProperty(AiProps.ENABLE_CAST_VALUE_TIEBREAK);
    }

    @Test
    public void timingPreflightLeavesPipReductionsIntact() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        putOpponentSpellOnStack(game);

        for (final String name : List.of("Grizzly Bears", "Mystical Tether")) {
            final SpellAbility spell = addCardToZone(name, ai, ZoneType.Hand).getFirstSpellAbility();
            spell.setActivatingPlayer(ai);
            spell.addOptionalCost(OptionalCost.ReduceG);
            Assert.assertFalse(spell.canCastTiming(ai));
            Assert.assertEquals(EarlyActionAffordability.noPriorityActionBeforeOrdering(ai, List.of(spell), true),
                    name.equals("Grizzly Bears"));
            Assert.assertEquals(spell.getPipsToReduce(), List.of("G"));
        }
    }
}
