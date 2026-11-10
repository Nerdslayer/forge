package forge.ai;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import forge.ai.effect.EarlyActionAffordability;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.AITimeoutTracker;

import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Test;

public class PriorityPassTest extends AITest {
    @Test
    public void liveChooserFinishesExpiredProbeCleanupBeforeReturningEarlierApproval() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        final var controller = Mockito.spy(new AiController(ai, game));
        final Card first = addCard("Icy Manipulator", ai);
        final Card second = addCard("Pacification Array", ai);
        final Card third = addCard("Sol Ring", ai);
        final var candidates = new ArrayList<>(ComputerUtilAbility.getSpellAbilities(
                new CardCollection(List.of(first, second, third)), ai));
        Assert.assertTrue(candidates.size() >= 3);
        final Thread caller = Thread.currentThread();
        final var probes = new AtomicInteger();
        final List<SpellAbility> approved = new ArrayList<>();
        Mockito.doAnswer(invocation -> {
            Assert.assertSame(Thread.currentThread(), caller);
            final SpellAbility ability = invocation.getArgument(0);
            if (probes.incrementAndGet() == 1) {
                approved.add(ability);
                return AiPlayDecision.WillPlay;
            }
            try {
                first.setTapped(true);
                // The chooser's scope restores the previous deadline on exit.
                AITimeoutTracker.arm(System.nanoTime() - 1, false);
                return AiPlayDecision.WillPlay;
            } finally {
                first.setTapped(false);
            }
        }).when(controller).canPlayAndPayFor(Mockito.any(), Mockito.anyBoolean());
        Assert.assertEquals(controller.chooseSpellAbilitiesToPlayFromList(candidates, false, 3), approved);
        Assert.assertEquals(probes.get(), 2);
        Assert.assertFalse(first.isTapped());
        Assert.assertFalse(AITimeoutTracker.isActive(AITimeoutTracker.key(game.getId(), ai.getId())));
        Assert.assertEquals(AITimeoutTracker.remainingNanos(), Long.MAX_VALUE);
        final Object expired = AITimeoutTracker.arm(System.nanoTime() - 1, false);
        try {
            Assert.assertTrue(controller.chooseSpellAbilitiesToPlayFromList(candidates, false, 3).isEmpty());
            Assert.assertEquals(probes.get(), 2);
        } finally {
            AITimeoutTracker.disarm(expired);
        }
    }

    @Test
    public void legacyAdmissionStopsAfterAnExpiredProbe() {
        for (final boolean throwInProbe : List.of(false, true)) {
            final Game game = initAndCreateGame();
            final Player ai = game.getPlayers().get(1);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
            addCards("Island", 3, ai);
            final Card first = addCard("Icy Manipulator", ai);
            final Card second = addCard("Pacification Array", ai);
            final var candidates = new ArrayList<>(ComputerUtilAbility.getSpellAbilities(
                    new CardCollection(List.of(first, second)), ai));
            Assert.assertTrue(candidates.size() >= 2);
            final var controller = Mockito.spy(new AiController(ai, game));
            Mockito.doReturn(true).when(controller).getBoolProperty(AiProps.ENABLE_ACTION_COMBINATION_VALUE_SHADOW);
            final var probes = new AtomicInteger();
            final Thread caller = Thread.currentThread();
            Mockito.doAnswer(invocation -> {
                Assert.assertSame(Thread.currentThread(), caller);
                final SpellAbility probe = invocation.getArgument(0);
                Assert.assertTrue(candidates.stream().noneMatch(candidate -> candidate == probe),
                        "The expired probe must be in legacy admission, not the final scan");
                probes.incrementAndGet();
                try {
                    first.setTapped(true);
                    AITimeoutTracker.arm(System.nanoTime() - 1, false);
                    if (throwInProbe) { AITimeoutTracker.check(); }
                    return AiPlayDecision.CantPlaySa;
                } finally {
                    first.setTapped(false);
                }
            }).when(controller).canPlayAndPayFor(Mockito.any(), Mockito.anyBoolean());
            Assert.assertTrue(controller.chooseSpellAbilitiesToPlayFromList(candidates, false, 1).isEmpty());
            Assert.assertEquals(probes.get(), 1);
            Assert.assertFalse(first.isTapped());
            Assert.assertEquals(AITimeoutTracker.remainingNanos(), Long.MAX_VALUE);
        }
    }

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
