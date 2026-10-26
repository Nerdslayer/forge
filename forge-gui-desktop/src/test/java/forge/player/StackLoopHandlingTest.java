package forge.player;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.phase.StackLoopWarning;
import forge.game.phase.StackResolutionTracker;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.YieldController;
import forge.gui.interfaces.IGuiGame;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.AfterMethod;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class StackLoopHandlingTest extends AITest {
    private String originalDecisionMode;

    @BeforeMethod
    public void useInMemoryDecisions() {
        originalDecisionMode = FModel.getPreferences().getPref(FPref.UI_AUTO_DECISION_MODE);
        FModel.getPreferences().setPref(FPref.UI_AUTO_DECISION_MODE, ForgeConstants.AUTO_DECISION_PER_CARD);
    }

    @AfterMethod
    public void restoreDecisionMode() {
        FModel.getPreferences().setPref(FPref.UI_AUTO_DECISION_MODE, originalDecisionMode);
    }

    @Test
    public void manualPlayOverridesAutomationWithoutChangingSavedDecisions() {
        Game game = initAndCreateGame();
        AtomicInteger prompts = new AtomicInteger();
        PlayerControllerHuman human = human(game, false, prompts);
        YieldController yields = human.getYieldController();
        String key = "loop safety regression";
        AutoYieldStore.TriggerDecision original = yields.getTriggerDecision(key);
        yields.setShouldAutoYield(key, true, false);
        yields.setTriggerDecision(key, AutoYieldStore.TriggerDecision.ACCEPT, false);
        yields.setPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, "true");
        yields.setAutoPassUntilStackEmpty(true, false);
        yields.setSkipPhase(human.getLocalPlayerView(), PhaseType.MAIN1, true);
        try {
            Assert.assertTrue(human.handleStackLoop(new StackLoopWarning(3, 200)));
            Assert.assertTrue(yields.isStackLoopPaused());
            Assert.assertFalse(yields.shouldAutoYield());
            Assert.assertFalse(yields.shouldAutoYield(key));
            Assert.assertFalse(yields.isAutoPassingNoActions(human.getLocalPlayerView()));
            Assert.assertFalse(human.isUiSetToSkipPhase(human.getLocalPlayerView(), PhaseType.MAIN1));
            Assert.assertEquals(yields.getTriggerDecision(key), AutoYieldStore.TriggerDecision.ASK);
            human.handleStackLoop(new StackLoopWarning(3, 400));
            Assert.assertEquals(prompts.get(), 1, "manual play must not keep showing the same warning");

            human.resetStackLoopHandling();
            Assert.assertTrue(yields.shouldAutoYield(key));
            Assert.assertEquals(yields.getTriggerDecision(key), AutoYieldStore.TriggerDecision.ACCEPT);
            Assert.assertTrue(yields.getBoolPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS));
            Assert.assertFalse(game.isGameOver());
        } finally {
            yields.setShouldAutoYield(key, false, false);
            yields.setTriggerDecision(key, original, false);
        }
    }

    @Test
    public void continuingLeavesTheCurrentYieldActiveAndDoesNotEndTheGame() {
        Game game = initAndCreateGame();
        AtomicInteger prompts = new AtomicInteger();
        PlayerControllerHuman human = human(game, true, prompts);
        human.getYieldController().setAutoPassUntilStackEmpty(true, false);
        Assert.assertTrue(human.handleStackLoop(new StackLoopWarning(3, 200)));
        Assert.assertFalse(human.getYieldController().isStackLoopPaused());
        Assert.assertTrue(human.getYieldController().isYieldActive());
        Assert.assertEquals(prompts.get(), 1);
        Assert.assertFalse(game.isGameOver());
        Assert.assertNull(human.getPlayer().getOutcome());
    }

    @Test
    public void snapshotRestorationClearsTheTemporaryManualOverride() {
        Game game = initAndCreateGame();
        PlayerControllerHuman human = human(game, false, new AtomicInteger());
        human.getPlayer().dangerouslySetController(human);
        GameSnapshot snapshot = new GameSnapshot(game);
        snapshot.makeCopy();
        human.handleStackLoop(new StackLoopWarning(3, 200));
        Assert.assertTrue(human.getYieldController().isStackLoopPaused());
        snapshot.restoreGameState(game);
        Assert.assertFalse(human.getYieldController().isStackLoopPaused());
    }

    @Test
    public void explicitYieldCanResumeAfterInspectingTheStack() {
        Game game = initAndCreateGame();
        PlayerControllerHuman human = human(game, false, new AtomicInteger());
        human.handleStackLoop(new StackLoopWarning(3, 200));
        Assert.assertTrue(human.getYieldController().isStackLoopPaused());
        human.getYieldController().setAutoPassUntilStackEmpty(true, false);
        Assert.assertFalse(human.getYieldController().isStackLoopPaused());
    }

    @Test
    public void reachingAnEmptyStackClearsTheManualOverride() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(0);
        PlayerControllerHuman human = human(game, false, new AtomicInteger());
        player.dangerouslySetController(human);
        Card source = addCard("Runeclaw Bear", player);
        SpellAbility ability = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ 0 | Defined$ You | LifeAmount$ 0", source);
        ability.setActivatingPlayer(player);
        game.getStack().add(ability);
        human.handleStackLoop(new StackLoopWarning(1, 200));
        Assert.assertTrue(human.getYieldController().isStackLoopPaused());
        for (int i = 0; i < 3; i++) game.getPhaseHandler().mainLoopStep();
        Assert.assertTrue(game.getStack().isEmpty());
        Assert.assertFalse(human.getYieldController().isStackLoopPaused());
    }

    @Test(timeOut = 30000)
    public void landfallBatchFinishesWithHumanAutomationAndNoLoopWarning() {
        Game game = initAndCreateGame();
        AtomicInteger prompts = new AtomicInteger();
        PlayerControllerHuman human = human(game, false, prompts);
        human.getPlayer().dangerouslySetController(human);
        human.getYieldController().setAutoPassUntilStackEmpty(true, false);
        Player player = game.getPlayers().get(1);
        addCards("Forest", 6, player);
        addCards("Scute Swarm", 256, player);
        game.getAction().checkStateEffects(true);
        game.getAction().moveToPlay(addCardToZone("Forest", player, ZoneType.Hand), null, AbilityKey.newMap());
        PhaseType phase = game.getPhaseHandler().getPhase();
        for (int i = 0; i < 1500 && !game.isGameOver() && game.getPhaseHandler().is(phase); i++) {
            game.getPhaseHandler().mainLoopStep();
        }
        Assert.assertEquals(prompts.get(), 0);
        Assert.assertEquals(countCardsWithName(game, "Scute Swarm"), 512);
        Assert.assertTrue(game.getStack().isEmpty());
        Assert.assertFalse(game.getPhaseHandler().is(phase));
        Assert.assertFalse(game.isGameOver());
        Assert.assertFalse(human.getYieldController().isStackLoopPaused());
        for (Player p : game.getPlayers()) Assert.assertNull(p.getOutcome());
    }

    @Test(timeOut = 30000)
    public void resourceWarningOffersManualRecoveryWithAnAccurateExplanation() {
        Game game = initAndCreateGame();
        AtomicInteger prompts = new AtomicInteger();
        AtomicReference<String> question = new AtomicReference<>();
        PlayerControllerHuman human = human(game, false, prompts, question);
        human.getPlayer().dangerouslySetController(human);
        human.getYieldController().setAutoPassUntilStackEmpty(true, false);
        Player player = game.getPlayers().get(1);
        Card source = addCard("Runeclaw Bear", player);
        Trigger trigger = TriggerHandler.parseTrigger(
                "Mode$ Phase | Phase$ Upkeep | TriggerZones$ Battlefield", source, true);
        SpellAbility effect = AbilityFactory.getAbility("DB$ GainLife | Defined$ You | LifeAmount$ 0", source);
        trigger.setOverridingAbility(effect);
        for (int i = 0; i < StackResolutionTracker.STACK_SIZE_WARNING; i++) {
            // Queue actual triggers: activated abilities copy an ever-growing activation
            // history into LKI, making a large synthetic batch quadratic in memory.
            SpellAbility ability = new WrappedAbility(trigger, effect.copy(source, true), null);
            ability.setActivatingPlayer(player);
            game.getStack().add(ability);
        }
        game.getPhaseHandler().mainLoopStep();
        Assert.assertEquals(prompts.get(), 1);
        Assert.assertTrue(question.get().startsWith("Large stack:"));
        Assert.assertFalse(question.get().contains("Possible repeating loop"));
        Assert.assertTrue(human.getYieldController().isStackLoopPaused());
        Assert.assertFalse(game.isGameOver());
        game.getPhaseHandler().mainLoopStep();
        Assert.assertEquals(prompts.get(), 1);
        Assert.assertEquals(game.getStack().size(), StackResolutionTracker.STACK_SIZE_WARNING - 1);
        for (Player p : game.getPlayers()) Assert.assertNull(p.getOutcome());
    }

    @Test(timeOut = 30000)
    public void growingTokenChainPausesWithoutMistakingGrowthForProofOfADraw() {
        Game game = initAndCreateGame();
        AtomicInteger prompts = new AtomicInteger();
        PlayerControllerHuman human = human(game, false, prompts);
        human.getPlayer().dangerouslySetController(human);
        human.getYieldController().setAutoPassUntilStackEmpty(true, false);
        Player player = game.getPlayers().get(1);
        // Each token produces two new triggers, then dies. Keep the battlefield
        // bounded so the test measures stack growth rather than static-rule scaling.
        addCards("Gremlin Tamer", 2, player);
        addCard("Secret Arcade", player);
        addCard("Night of Souls' Betrayal", player);
        game.getAction().checkStateEffects(true);
        game.getAction().moveToPlay(addCardToZone("Sterling Grove", player, ZoneType.Hand), null, AbilityKey.newMap());
        for (int i = 0; i < 500 && !game.isGameOver() && prompts.get() == 0; i++) {
            game.getPhaseHandler().mainLoopStep();
        }
        Assert.assertEquals(prompts.get(), 1);
        Assert.assertTrue(human.getYieldController().isStackLoopPaused());
        Assert.assertTrue(game.getStack().size() > 200);
        Assert.assertFalse(game.isGameOver());
        for (Player p : game.getPlayers()) Assert.assertNull(p.getOutcome());
    }

    @Test(timeOut = 30000)
    public void mandatoryLoopPausesAHumanGameInsteadOfEndingIt() {
        Game game = initAndCreateGame();
        AtomicInteger prompts = new AtomicInteger();
        PlayerControllerHuman human = human(game, false, prompts);
        human.getPlayer().dangerouslySetController(human);
        Player player = game.getPlayers().get(1); // The AI drives the mandatory effects; the human receives the warning.
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player);
        addCard("Gremlin Tamer", player);
        addCard("Secret Arcade", player);
        addCard("Night of Souls' Betrayal", player);
        game.getAction().checkStateEffects(true);
        game.getAction().moveToPlay(addCardToZone("Sterling Grove", player, ZoneType.Hand), null, AbilityKey.newMap());
        for (int i = 0; i < 900 && !game.isGameOver(); i++) {
            game.getPhaseHandler().mainLoopStep();
        }
        Assert.assertEquals(prompts.get(), 1);
        Assert.assertTrue(human.getYieldController().isStackLoopPaused());
        Assert.assertFalse(game.isGameOver());
        Assert.assertNull(player.getOutcome());
        Assert.assertNull(human.getPlayer().getOutcome());
    }

    private PlayerControllerHuman human(final Game game, final boolean continueResolving,
                                        final AtomicInteger prompts) {
        return human(game, continueResolving, prompts, new AtomicReference<>());
    }

    private PlayerControllerHuman human(final Game game, final boolean continueResolving,
                                        final AtomicInteger prompts, final AtomicReference<String> question) {
        Player player = game.getPlayers().get(0);
        PlayerControllerHuman human = new PlayerControllerHuman(game, player, new LobbyPlayerHuman("Human")) {
            @Override
            public List<SpellAbility> chooseSpellAbilityToPlay() {
                return null; // Tests drive priority directly; confirmations still use the real handler.
            }
        };
        human.setGui((IGuiGame) Proxy.newProxyInstance(IGuiGame.class.getClassLoader(),
                new Class<?>[] {IGuiGame.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isLibgdxPort")) return true;
                    if (method.getName().equals("getGameView")) return game.getView();
                    if (method.getName().equals("confirm")) {
                        prompts.incrementAndGet();
                        question.set((String) args[1]);
                        return continueResolving;
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                }));
        return human;
    }
}
