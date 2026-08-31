package forge.ai.effect;

import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.ComputerUtilCard;
import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.trigger.TriggerHandler;

public class EffectSynergyEvaluatorTest extends AITest {
    @Test
    public void testProductionAndConsequenceReceiveSameFirstOrderValue() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseTokenProducer("Grizzly Bears", opponent, 1);
        final Card consequence = addTokenCounterConsequence("Runeclaw Bear", opponent);

        final Map<Card, Integer> values = EffectSynergyEvaluator.evaluateRemovalSynergies(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0);
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testConsequenceAccumulatesValueFromMultipleProducers() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card firstProducer = addPhaseTokenProducer("Grizzly Bears", opponent, 1);
        final Card secondProducer = addPhaseTokenProducer("Runeclaw Bear", opponent, 3);
        final Card consequence = addTokenCounterConsequence("Bear Cub", opponent);

        final Map<Card, Integer> values = EffectSynergyEvaluator.evaluateRemovalSynergies(
                ai, List.of(firstProducer, secondProducer, consequence));

        Assert.assertEquals(values.get(secondProducer).intValue(), values.get(firstProducer) * 3);
        Assert.assertEquals(values.get(consequence).intValue(),
                values.get(firstProducer) + values.get(secondProducer));
    }

    @Test
    public void testProducerAloneGetsNoRawTokenValue() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseTokenProducer("Grizzly Bears", opponent, 3);

        final Map<Card, Integer> values = EffectSynergyEvaluator.evaluateRemovalSynergies(ai, List.of(producer));

        Assert.assertEquals(values.getOrDefault(producer, 0).intValue(), 0);
    }

    @Test
    public void testConditionalProductionIsIgnored() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addCard("Grizzly Bears", opponent);
        producer.setSVar("X", "1");
        addTokenAbility(producer, "EffectTestToken", 1);
        addTrigger(producer,
                "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | CheckSVar$ X | Execute$ EffectTestToken"
                        + " | TriggerZones$ Battlefield");
        final Card consequence = addTokenCounterConsequence("Runeclaw Bear", opponent);

        final Map<Card, Integer> values = EffectSynergyEvaluator.evaluateRemovalSynergies(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.isEmpty());
    }

    @Test
    public void testCreatedCreatureTokenCanBeTheCounterTarget() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseTokenProducer("Sol Ring", opponent, 1);
        final Card consequence = addTokenCounterConsequence("Memnite", opponent);

        final Map<Card, Integer> values = EffectSynergyEvaluator.evaluateRemovalSynergies(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0,
                "The created creature exists before its TokenCreated consequence resolves");
    }

    @Test
    public void testRelationshipHasNoValueWithoutALegalCounterTarget() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addCard("Sol Ring", opponent);
        producer.setSVar("EffectTestToken",
                "DB$ Token | TokenScript$ c_a_treasure_sac | TokenOwner$ You");
        addTrigger(producer, "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | Execute$ EffectTestToken"
                + " | TriggerZones$ Battlefield");
        final Card consequence = addTokenCounterConsequence("Memnite", opponent);

        final Map<Card, Integer> values = EffectSynergyEvaluator.evaluateRemovalSynergies(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.isEmpty());
    }

    @Test
    public void testRoyalTalonJetAndRosieAreRecognizedWhenJetCanBeCrewed() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card rosie = addCard("Rosie Cotton of South Lane", opponent);
        final Card jet = addCard("Royal Talon Fighter Jet", opponent);
        jet.setCounters(CounterEnumType.P1P1, 2);

        Map<Card, Integer> values = EffectSynergyEvaluator.evaluateRemovalSynergies(ai, List.of(rosie, jet));
        Assert.assertTrue(values.isEmpty(), "Rosie alone cannot currently meet Crew 2");

        addCard("Grizzly Bears", opponent);
        values = EffectSynergyEvaluator.evaluateRemovalSynergies(ai, List.of(rosie, jet));

        Assert.assertTrue(values.getOrDefault(jet, 0) > 0);
        Assert.assertEquals(values.get(jet), values.get(rosie));
    }

    @Test
    public void testRemovalSelectionOnlyChangesForEnabledProfile() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseTokenProducer("Memnite", opponent, 1);
        final Card ordinaryThreat = addCard("Centaur Courser", opponent);
        for (int i = 0; i < 4; i++) {
            addTokenCounterConsequence("Memnite", opponent);
        }

        final LobbyPlayerAi lobbyAi = (LobbyPlayerAi) ai.getLobbyPlayer();
        lobbyAi.setAiProfile("Default");
        Assert.assertFalse(AiProfileUtil.getBoolProperty(ai, AiProps.ENABLE_EFFECT_ANALYSIS));
        Assert.assertSame(ComputerUtilCard.getBestRemovalTargetAI(ai, List.of(producer, ordinaryThreat)),
                ordinaryThreat);

        lobbyAi.setAiProfile("Mastermind");
        Assert.assertTrue(AiProfileUtil.getBoolProperty(ai, AiProps.ENABLE_EFFECT_ANALYSIS));
        Assert.assertEquals(AiProfileUtil.getIntProperty(ai, AiProps.EFFECT_SYNERGY_WEIGHT), 100);
        Assert.assertSame(ComputerUtilCard.getBestRemovalTargetAI(ai, List.of(producer, ordinaryThreat)), producer);
    }

    private Card addPhaseTokenProducer(final String cardName, final Player controller, final int amount) {
        final Card card = addCard(cardName, controller);
        addTokenAbility(card, "EffectTestToken", amount);
        addTrigger(card, "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | Execute$ EffectTestToken"
                + " | TriggerZones$ Battlefield");
        return card;
    }

    private Card addTokenCounterConsequence(final String cardName, final Player controller) {
        final Card card = addCard(cardName, controller);
        card.setSVar("EffectTestCounter",
                "DB$ PutCounter | ValidTgts$ Creature.YouCtrl+Other | CounterType$ P1P1");
        addTrigger(card, "Mode$ TokenCreated | ValidPlayer$ You | ValidToken$ Card.token+YouCtrl"
                + " | Execute$ EffectTestCounter | TriggerZones$ Battlefield");
        return card;
    }

    private void addTokenAbility(final Card card, final String name, final int amount) {
        card.setSVar(name, "DB$ Token | TokenScript$ w_1_1_soldier | TokenOwner$ You | TokenAmount$ " + amount);
    }

    private void addTrigger(final Card card, final String definition) {
        card.addTrigger(TriggerHandler.parseTrigger(definition, card, false));
    }

    private static void setOpposingTeams(final Player ai, final Player opponent) {
        ai.setTeam(0);
        opponent.setTeam(1);
    }
}
