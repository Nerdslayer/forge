package forge.game.trigger;

import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.phase.MandatoryLoopVerifier;
import forge.game.phase.PhaseType;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class MandatoryLoopVerifierTest extends AITest {
    @DataProvider
    public Object[][] thresholds() {
        return new Object[][] {{0}, {20}, {50}};
    }

    @Test(dataProvider = "thresholds", timeOut = 30000)
    public void blockedMandatoryLifeThresholdDrawsWithoutClaimingConsent(final int life) {
        Game game = lossLoop(life);
        Assert.assertTrue(verified(game), "the proof must depend on the script, not a specific threshold: " + describe(game));
        for (int i = 0; i < 1000 && !game.isGameOver(); i++) game.getPhaseHandler().mainLoopStep();
        Assert.assertTrue(game.isGameOver());
        Assert.assertEquals(game.getOutcome().getWinCondition(), GameEndReason.Draw);
        for (Player player : game.getPlayers()) {
            Assert.assertEquals(player.getOutcome().lossState, GameLossReason.MandatoryLoop);
            Assert.assertFalse(player.getOutcome().hasWon());
            Assert.assertEquals(player.getOutcome().toString(), "has drawn because a mandatory loop cannot end");
        }
    }

    @DataProvider
    public Object[][] unsupportedTriggerParameters() {
        return new Object[][] {
                {"ActivationLimit", "250"}, {"GameActivationLimit", "250"}, {"ResolvedLimit", "250"},
                {"OptionalDecider", "You"}, {"CheckSVar", "X"}, {"TriggerController", "Opponent"},
                {"RememberTriggeredCard", "True"}, {"NoResolvingCheck", "True"}
        };
    }

    @Test(dataProvider = "unsupportedTriggerParameters")
    public void extraTriggerSemanticsCannotBeIgnored(final String parameter, final String value) {
        Game game = lossLoop(20);
        game.getStack().peekAbility().getTrigger().putParam(parameter, value);
        Assert.assertFalse(verified(game));
    }

    @DataProvider
    public Object[][] unsupportedEffectParameters() {
        return new Object[][] {
                {"Cost", "0"}, {"UnlessCost", "1"}, {"ConditionCheckSVar", "X"},
                {"RememberObjects", "Self"}, {"Defined", "Opponent"}, {"Optional", "True"}
        };
    }

    @Test(dataProvider = "unsupportedEffectParameters")
    public void additionalEffectsAndChoicesCannotBeIgnored(final String parameter, final String value) {
        Game game = lossLoop(20);
        ((WrappedAbility) game.getStack().peekAbility()).getWrappedAbility().putParam(parameter, value);
        Assert.assertFalse(verified(game));
    }

    @Test(timeOut = 30000)
    public void activationLimitedChainAboveTheWarningThresholdFinishesNormally() {
        Game game = lossLoop(20);
        game.getStack().peekAbility().getTrigger().putParam("ActivationLimit", "250");
        PhaseType phase = game.getPhaseHandler().getPhase();
        for (int i = 0; i < 1500 && !game.isGameOver() && game.getPhaseHandler().is(phase); i++) {
            game.getPhaseHandler().mainLoopStep();
        }
        Assert.assertFalse(game.isGameOver());
        Assert.assertTrue(game.getStack().isEmpty());
        Assert.assertFalse(game.getPhaseHandler().is(phase));
    }

    @DataProvider
    public Object[][] unsupportedProtectionParameters() {
        return new Object[][] {{"Optional", "True"}, {"CheckSVar", "X"}, {"ValidLoseReason", "SpellEffect"}};
    }

    @Test(dataProvider = "unsupportedProtectionParameters")
    public void protectionMustBeUnconditional(final String parameter, final String value) {
        Game game = lossLoop(20);
        Card angel = findCardWithName(game, "Platinum Angel");
        angel.getReplacementEffects().stream().filter(r -> "GameLoss".equals(r.getParam("Event")))
                .forEach(r -> r.putParam(parameter, value));
        Assert.assertFalse(verified(game));
    }

    @Test
    public void sideEffectsFromResolutionOrDelayedTriggersPreventProof() {
        Game game = lossLoop(20);
        Card source = findCardWithName(game, "Transcendence");
        Trigger hook = TriggerHandler.parseTrigger("Mode$ AbilityResolves | TriggerZones$ Battlefield", source, true);
        source.addTrigger(hook);
        Assert.assertFalse(verified(game));

        game = lossLoop(20);
        source = findCardWithName(game, "Transcendence");
        game.getTriggerHandler().registerDelayedTrigger(
                TriggerHandler.parseTrigger("Mode$ Phase | Phase$ End of Turn", source, true));
        Assert.assertFalse(verified(game));
    }

    @DataProvider
    public Object[][] additionalEventObservers() {
        return new Object[][] {{"AbilityResolves"}, {"AbilityTriggered"}, {"SpellAbilityCast"}, {"Always"}};
    }

    @Test(dataProvider = "additionalEventObservers")
    public void extraActiveEventObserversRequireASeparateProof(final String mode) {
        Game game = lossLoop(20);
        Card source = findCardWithName(game, "Transcendence");
        source.addTrigger(TriggerHandler.parseTrigger("Mode$ " + mode + " | TriggerZones$ Battlefield", source, true));
        Assert.assertFalse(verified(game));
    }

    @Test
    public void unrelatedStackObjectsAndMultiplayerAreNotShortcutted() {
        Game game = lossLoop(20);
        SpellAbility extra = AbilityFactory.getAbility("AB$ GainLife | Cost$ 0 | Defined$ You | LifeAmount$ 1",
                findCardWithName(game, "Transcendence"));
        extra.setActivatingPlayer(game.getPlayers().get(1));
        game.getStack().add(extra);
        Assert.assertFalse(verified(game));

        Game multiplayer = initAndCreateThreePlayerGame();
        prepareLossLoop(multiplayer, 20);
        Assert.assertFalse(verified(multiplayer));
    }

    @Test
    public void frozenAndResolvingStacksAreNotSettledProofBoundaries() {
        Game game = lossLoop(20);
        game.getStack().freezeStack(null);
        Assert.assertFalse(verified(game));
        game.getStack().unfreezeStack();
        game.getStack().setResolving(true);
        Assert.assertFalse(verified(game));
    }

    @Test
    public void otherStateTriggersAndContinuousRulesRequireSeparateProofs() {
        Game game = lossLoop(20);
        Card source = findCardWithName(game, "Transcendence");
        source.addTrigger(TriggerHandler.parseTrigger("Mode$ Always | TriggerZones$ Battlefield", source, true));
        Assert.assertFalse(verified(game));

        game = lossLoop(20);
        addCard("Glorious Anthem", game.getPlayers().get(1));
        game.getAction().checkStateEffects(true);
        Assert.assertFalse(verified(game));
    }

    @Test(timeOut = 30000)
    public void aResponseCanBreakTheLoopBeforeTheDrawIsApplied() {
        Game game = lossLoop(20);
        Player player = game.getPlayers().get(1);
        Card angel = findCardWithName(game, "Platinum Angel");
        AtomicBoolean responded = new AtomicBoolean();
        player.dangerouslySetController(new PlayerControllerAi(game, player, player.getLobbyPlayer()) {
            @Override
            public List<SpellAbility> chooseSpellAbilityToPlay() {
                SpellAbility next = game.getStack().peekAbility();
                if (next != null && !responded.get() && game.getPhaseHandler().shouldAiDeclineOptionalTrigger(next)) {
                    responded.set(true);
                    SpellAbility response = AbilityFactory.getAbility(
                            "AB$ ChangeZone | Cost$ 0 | Defined$ Self | Origin$ Battlefield | Destination$ Exile", angel);
                    response.setActivatingPlayer(player);
                    return List.of(response);
                }
                return null;
            }
        });
        for (int i = 0; i < 1500 && !game.isGameOver(); i++) game.getPhaseHandler().mainLoopStep();
        Assert.assertTrue(responded.get());
        Assert.assertTrue(game.isGameOver());
        Assert.assertEquals(player.getOutcome().lossState, GameLossReason.SpellEffect);
        Assert.assertNotEquals(game.getOutcome().getWinCondition(), GameEndReason.Draw);
    }

    private Game lossLoop(final int life) {
        Game game = initAndCreateGame();
        prepareLossLoop(game, life);
        return game;
    }

    private void prepareLossLoop(final Game game, final int life) {
        Player player = game.getPlayers().get(1);
        player.setLife(life, null);
        addCard("Platinum Angel", player);
        Card source = addCard("Transcendence", player);
        source.getTriggers().stream().filter(t -> t.getMode() == TriggerType.Always)
                .forEach(t -> t.putParam("LifeAmount", "GE" + life));
        game.getAction().checkStateEffects(true);
        game.getPhaseHandler().mainLoopStep();
        Assert.assertNotNull(game.getStack().peekAbility());
    }

    private boolean verified(final Game game) {
        return MandatoryLoopVerifier.isVerified(game, game.getStack().peekAbility());
    }

    private String describe(final Game game) {
        WrappedAbility ability = (WrappedAbility) game.getStack().peekAbility();
        StringBuilder result = new StringBuilder("mandatory=").append(ability.isMandatory())
                .append(" pending=").append(game.getTriggerHandler().hasDelayedOrWaitingTriggers())
                .append(" script=").append(ability.getWrappedAbility().getMapParams())
                .append(" trigger=").append(ability.getTrigger().getMapParams());
        for (Card card : game.getCardsInGame()) {
            result.append(" card=").append(card).append(" statics=")
                    .append(card.getStaticAbilities().stream().map(a -> a.getMapParams()).toList())
                    .append(" replacements=")
                    .append(card.getReplacementEffects().stream().map(r -> r.getMapParams()).toList());
        }
        return result.toString();
    }
}
