package forge.ai.effect;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.ComputerUtilCard;
import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;

/** Gameplay rollout regressions, separate from standalone reference-score tests. */
public class PermanentAbilityRemovalTest extends AITest {
    @Test
    public void supportedFutureValueChangesOnlyEnabledRemovalSelection() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        final Card drawEngine = addCard("Staff of Nin", opponent);
        // Give Staff a high-impact, one-toughness opposing creature that its activated ability can
        // actually kill; the removal analysis should then credit that hostile future use.
        addCard("Grim Lavamancer", ai);
        final Card expensiveBody = addCard("Akroma's Memorial", opponent);
        final List<Card> candidates = List.of(expensiveBody, drawEngine);
        final LobbyPlayerAi lobby = (LobbyPlayerAi) ai.getLobbyPlayer();

        lobby.setAiProfile("Default");
        Assert.assertFalse(AiProfileUtil.getBoolProperty(ai, AiProps.ENABLE_INTRINSIC_REMOVAL_ANALYSIS));
        Assert.assertSame(ComputerUtilCard.getBestRemovalTargetAI(ai, candidates), expensiveBody);

        lobby.setAiProfile("Mastermind");
        Assert.assertTrue(AiProfileUtil.getBoolProperty(ai, AiProps.ENABLE_INTRINSIC_REMOVAL_ANALYSIS));
        Assert.assertEquals(AiProfileUtil.getIntProperty(ai, AiProps.INTRINSIC_REMOVAL_WEIGHT), 100);
        Assert.assertSame(ComputerUtilCard.getBestRemovalTargetAI(ai, candidates), drawEngine);
        Assert.assertEquals(drawEngine.getCounters().size(), 0);
    }

    @Test
    public void disablingIntrinsicCompositionPreservesRelationshipValues() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        final Card source = addCard("Glorious Anthem", opponent);
        addCard("Grizzly Bears", opponent);
        game.getAction().checkStaticAbilities();
        final List<Card> candidates = List.of(source);
        final int existing = EffectRelationshipEvaluator.evaluateRemovalRelationships(ai, candidates)
                .getOrDefault(source, 0);
        final PermanentAbilityValueEvaluator.Breakdown result = PermanentAbilityValueEvaluator
                .evaluateRemovalAbilities(ai, candidates, EffectAnalysisTrace.disabled(), false).get(source);

        Assert.assertNotNull(result);
        Assert.assertEquals(result.relationshipValue(), existing);
        Assert.assertEquals(result.intrinsicValue(), 0);
    }

    @Test
    public void futureStaticKeywordAllowanceUsesSharedIntrinsicCreatureDelta() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        final Card source = addCard("Sol Ring", opponent);
        source.addStaticAbility("Mode$ Continuous | Affected$ Creature.YouCtrl | AddKeyword$ Flying");
        game.getAction().checkStaticAbilities();

        final PermanentAbilityValueEvaluator.Breakdown result = PermanentAbilityValueEvaluator
                .evaluateRemovalAbilities(ai, List.of(source), EffectAnalysisTrace.disabled()).get(source);

        Assert.assertNotNull(result);
        Assert.assertTrue(result.intrinsicValue() > 0, result.toString());
        Assert.assertTrue(result.reasons().stream().anyMatch(reason ->
                reason.contains("Fixed future static allowance")), result.reasons().toString());
    }

    @Test
    public void futureStaticHintSupportsNonCreatureRecipients() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        final Card source = addCard("Sol Ring", opponent);
        source.addStaticAbility("Mode$ Continuous | Affected$ Artifact.YouCtrl"
                + " | AIEffectValue$ -10");
        game.getAction().checkStaticAbilities();

        final PermanentAbilityValueEvaluator.Breakdown result = PermanentAbilityValueEvaluator
                .evaluateRemovalAbilities(ai, List.of(source), EffectAnalysisTrace.disabled()).get(source);

        Assert.assertNotNull(result);
        Assert.assertTrue(result.intrinsicValue() < 0, result.toString());
        Assert.assertTrue(result.reasons().stream().anyMatch(reason ->
                reason.contains("AIEffectValue supplement")), result.reasons().toString());
    }
}
