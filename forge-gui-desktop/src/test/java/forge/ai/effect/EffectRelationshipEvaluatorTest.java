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
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.trigger.TriggerHandler;

public class EffectRelationshipEvaluatorTest extends AITest {
    @Test
    public void testProductionAndConsequenceReceiveSameFirstOrderValue() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseTokenProducer("Grizzly Bears", opponent, 1);
        final Card consequence = addTokenCounterConsequence("Runeclaw Bear", opponent);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
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

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
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

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer));

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

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
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

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
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

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.isEmpty());
    }

    @Test
    public void testPermanentPtConsequenceUsesCreatureValueDelta() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseTokenProducer("Sol Ring", opponent, 1);
        final Card consequence = addTokenPumpConsequence(
                "Grizzly Bears", opponent, "+1", "+1", "Permanent", "Self");
        final long timestampBeforeAnalysis = game.getTimestamp();

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0);
        Assert.assertEquals(values.get(producer), values.get(consequence));
        Assert.assertEquals(game.getTimestamp(), timestampBeforeAnalysis,
                "Analysis-only P/T changes must not consume live game timestamps");
    }

    @Test
    public void testPerpetualPtReductionOnAiCreatureIncreasesThreat() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseTokenProducer("Sol Ring", opponent, 1);
        final Card consequence = addCard("Grizzly Bears", opponent);
        consequence.setSVar("EffectTestPump",
                "DB$ Pump | ValidTgts$ Creature.OppCtrl | NumAtt$ -1 | NumDef$ -1"
                        + " | Duration$ Perpetual");
        addTrigger(consequence, "Mode$ TokenCreated | ValidPlayer$ You"
                + " | ValidToken$ Card.token+YouCtrl | Execute$ EffectTestPump"
                + " | TriggerZones$ Battlefield");
        addCard("Runeclaw Bear", ai);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0);
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testNegativeTriggeredRelationshipValueIsPreserved() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseTokenProducer("Sol Ring", opponent, 1);
        final Card consequence = addCard("Grizzly Bears", opponent);
        consequence.setSVar("EffectTestCounter",
                "DB$ PutCounter | ValidTgts$ Creature.OppCtrl | CounterType$ P1P1");
        addTrigger(consequence, "Mode$ TokenCreated | ValidPlayer$ You"
                + " | ValidToken$ Card.token+YouCtrl | Execute$ EffectTestCounter"
                + " | TriggerZones$ Battlefield");
        addCard("Runeclaw Bear", ai);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) < 0,
                "A mandatory trigger that benefits the AI should reduce removal priority");
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testTemporaryPtConsequenceIsNotTreatedAsPermanentValue() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseTokenProducer("Sol Ring", opponent, 1);
        final Card consequence = addTokenPumpConsequence(
                "Grizzly Bears", opponent, "+3", "+3", null, "Self");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.isEmpty());
    }

    @Test
    public void testPermanentBasePtAnimationUsesResultingPermanentValue() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseTokenProducer("Grizzly Bears", opponent, 1);
        final Card consequence = addCard("Sol Ring", opponent);
        consequence.setSVar("EffectTestAnimate", "DB$ Animate | Defined$ Self"
                + " | Power$ 4 | Toughness$ 4 | Types$ Creature,Elemental | Duration$ Permanent");
        addTrigger(consequence, "Mode$ TokenCreated | ValidPlayer$ You"
                + " | ValidToken$ Card.token+YouCtrl | Execute$ EffectTestAnimate"
                + " | TriggerZones$ Battlefield");
        final long timestampBeforeAnalysis = game.getTimestamp();

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0);
        Assert.assertEquals(values.get(producer), values.get(consequence));
        Assert.assertEquals(game.getTimestamp(), timestampBeforeAnalysis,
                "Analysis-only animation must not consume live game timestamps");
    }

    @Test
    public void testPermanentPumpAllIncludesMatchingBattlefieldCardsAndCreatedToken() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseTokenProducer("Sol Ring", opponent, 1);
        final Card consequence = addCard("Grizzly Bears", opponent);
        consequence.setSVar("EffectTestPumpAll", "DB$ PumpAll | ValidCards$ Creature.YouCtrl"
                + " | NumAtt$ +1 | NumDef$ +1 | Duration$ Permanent");
        addTrigger(consequence, "Mode$ TokenCreated | ValidPlayer$ You"
                + " | ValidToken$ Card.token+YouCtrl | Execute$ EffectTestPumpAll"
                + " | TriggerZones$ Battlefield");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0);
        Assert.assertEquals(values.get(producer), values.get(consequence));
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

        Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(rosie, jet));
        Assert.assertTrue(values.isEmpty(), "Rosie alone cannot currently meet Crew 2");

        addCard("Grizzly Bears", opponent);
        values = EffectRelationshipEvaluator.evaluateRemovalRelationships(ai, List.of(rosie, jet));

        Assert.assertTrue(values.getOrDefault(jet, 0) > 0);
        Assert.assertEquals(values.get(jet), values.get(rosie));
    }

    @Test
    public void testPayableActivatedTokenAbilityIsRecognized() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addCard("Grizzly Bears", opponent);
        producer.addSpellAbility(AbilityFactory.getAbility(
                "AB$ Token | Cost$ 0 | TokenAmount$ 2 | TokenScript$ w_1_1_soldier"
                        + " | TokenOwner$ You",
                producer));
        final Card consequence = addTokenCounterConsequence("Runeclaw Bear", opponent);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0);
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testTokenCreatedOnceCountsOneBatchInsteadOfEveryToken() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card oneTokenProducer = addPhaseTokenProducer("Grizzly Bears", opponent, 1);
        final Card threeTokenProducer = addPhaseTokenProducer("Bear Cub", opponent, 3);
        final Card batchConsequence = addTokenCounterConsequence(
                "Balduvian Bears", opponent, "TokenCreatedOnce");
        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(oneTokenProducer, threeTokenProducer, batchConsequence));

        Assert.assertEquals(values.get(threeTokenProducer), values.get(oneTokenProducer));
        Assert.assertEquals(values.get(batchConsequence).intValue(), values.get(oneTokenProducer) * 2);
    }

    @Test
    public void testBatchOutcomeRetainsProducedSubjectMultiplicities() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card oneTokenProducer = addPhaseTokenProducer("Sol Ring", opponent, 1);
        final Card threeTokenProducer = addPhaseTokenProducer("Mox Amber", opponent, 3);
        final Card consequence = addCard("Grizzly Bears", opponent);
        consequence.setSVar("EffectTestPumpAll", "DB$ PumpAll"
                + " | ValidCards$ Creature.token+YouCtrl | NumAtt$ +1 | NumDef$ +1"
                + " | Duration$ Permanent");
        addTrigger(consequence, "Mode$ TokenCreatedOnce | ValidToken$ Card.token+YouCtrl"
                + " | Execute$ EffectTestPumpAll | TriggerZones$ Battlefield");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(oneTokenProducer, threeTokenProducer, consequence));

        Assert.assertEquals(values.get(threeTokenProducer).intValue(),
                values.get(oneTokenProducer) * 3);
        Assert.assertEquals(values.get(consequence).intValue(),
                values.get(oneTokenProducer) + values.get(threeTokenProducer));
    }

    @Test
    public void testMultipleTokenScriptsProduceIndividualEvents() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card singleProducer = addPhaseTokenProducer("Grizzly Bears", opponent, 1);
        final Card consequence = addTokenCounterConsequence("Runeclaw Bear", opponent);
        final int singleValue = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(singleProducer, consequence)).get(singleProducer);

        final Card multiProducer = addCard("Bear Cub", opponent);
        multiProducer.setSVar("EffectTestToken",
                "DB$ Token | TokenScript$ w_1_1_soldier,c_a_treasure_sac | TokenOwner$ You");
        addTrigger(multiProducer,
                "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | Execute$ EffectTestToken"
                        + " | TriggerZones$ Battlefield");
        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(multiProducer, consequence));

        Assert.assertEquals(values.get(multiProducer).intValue(), singleValue * 2);
    }

    @Test
    public void testCounterProductionMatchesExactAndAnyCounterConsequences() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 2, "Self");
        final Card exactConsequence = addCounterAddedConsequence(
                "Grizzly Bears", opponent, "CounterAddedOnce", "CHARGE",
                "ValidCard$ Artifact.YouCtrl");
        final Card anyConsequence = addCounterAddedConsequence(
                "Runeclaw Bear", opponent, "CounterAddedOnce", "Any",
                "ValidCard$ Artifact.YouCtrl");
        final Card wrongTypeConsequence = addCounterAddedConsequence(
                "Bear Cub", opponent, "CounterAddedOnce", "P1P1",
                "ValidCard$ Artifact.YouCtrl");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, exactConsequence, anyConsequence, wrongTypeConsequence));

        Assert.assertTrue(values.getOrDefault(exactConsequence, 0) > 0);
        Assert.assertEquals(values.get(exactConsequence), values.get(anyConsequence));
        Assert.assertEquals(values.getOrDefault(wrongTypeConsequence, 0).intValue(), 0);
        Assert.assertEquals(values.get(producer).intValue(),
                values.get(exactConsequence) + values.get(anyConsequence));
    }

    @Test
    public void testCounterAddedTriggersPerCounterButCounterAddedOnceTriggersPerBatch() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 3, "Self");
        final Card individualConsequence = addCounterAddedConsequence(
                "Grizzly Bears", opponent, "CounterAdded", null,
                "ValidCard$ Artifact.YouCtrl");
        final Card batchConsequence = addCounterAddedConsequence(
                "Runeclaw Bear", opponent, "CounterAddedOnce", null,
                "ValidCard$ Artifact.YouCtrl");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, individualConsequence, batchConsequence));

        Assert.assertEquals(values.get(individualConsequence).intValue(),
                values.get(batchConsequence) * 3);
        Assert.assertEquals(values.get(producer).intValue(),
                values.get(individualConsequence) + values.get(batchConsequence));
    }

    @Test
    public void testCounterProductionSupportsPlayerRecipients() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "ENERGY", 2, "You");
        final Card consequence = addCounterAddedConsequence(
                "Grizzly Bears", opponent, "CounterAddedOnce", "Any",
                "ValidPlayer$ You");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0);
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testPayableActivatedCounterAbilityIsRecognized() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addCard("Sol Ring", opponent);
        producer.addSpellAbility(AbilityFactory.getAbility(
                "AB$ PutCounter | Cost$ 0 | Defined$ Self | CounterType$ OIL | CounterNum$ 2",
                producer));
        final Card consequence = addCounterAddedConsequence(
                "Grizzly Bears", opponent, "CounterAddedOnce", null,
                "ValidCard$ Artifact.YouCtrl");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0);
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testStaticPtAbilityAddsItsMarginalValueToTheSource() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card anthem = addCard("Sol Ring", opponent);
        anthem.addStaticAbility("Mode$ Continuous | Affected$ Creature.YouCtrl"
                + " | AddPower$ 1 | AddToughness$ 1");
        final Card firstCreature = addCard("Grizzly Bears", opponent);
        final Card secondCreature = addCard("Runeclaw Bear", opponent);
        game.getAction().checkStaticAbilities();

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(anthem, firstCreature, secondCreature));

        Assert.assertTrue(values.getOrDefault(anthem, 0) > 0);
        Assert.assertFalse(values.containsKey(firstCreature),
                "Existing recipients already include the anthem in their normal evaluation");
        Assert.assertFalse(values.containsKey(secondCreature));
    }

    @Test
    public void testStaticPtAbilityGetsNoValueWithoutAnotherAffectedCreature() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card anthemCreature = addCard("Grizzly Bears", opponent);
        anthemCreature.addStaticAbility("Mode$ Continuous | Affected$ Creature.YouCtrl"
                + " | AddPower$ 1 | AddToughness$ 1");
        game.getAction().checkStaticAbilities();

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(anthemCreature));

        Assert.assertFalse(values.containsKey(anthemCreature),
                "A source's own current stats are already included in its normal evaluation");
    }

    @Test
    public void testStaticAbilityUsesNetValueAcrossBothSides() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card globalAnthem = addCard("Sol Ring", opponent);
        globalAnthem.addStaticAbility("Mode$ Continuous | Affected$ Creature"
                + " | AddPower$ 1 | AddToughness$ 1");
        addCard("Grizzly Bears", opponent);
        addCard("Grizzly Bears", ai);
        addCard("Grizzly Bears", ai);
        game.getAction().checkStaticAbilities();

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(globalAnthem));

        Assert.assertTrue(values.getOrDefault(globalAnthem, 0) < 0,
                "The anthem helps more allied cards from the evaluating AI's perspective");
    }

    @Test
    public void testNegativeStaticPtAbilityOnAiCreatureIncreasesThreat() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card hostileEffect = addCard("Sol Ring", opponent);
        hostileEffect.addStaticAbility("Mode$ Continuous | Affected$ Creature.OppCtrl"
                + " | AddPower$ -1 | AddToughness$ -1");
        addCard("Grizzly Bears", ai);
        game.getAction().checkStaticAbilities();

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(hostileEffect));

        Assert.assertTrue(values.getOrDefault(hostileEffect, 0) > 0);
    }

    @Test
    public void testConditionalKeywordGrantUsesExistingCreatureEvaluation() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card trampleSource = addCard("Sol Ring", opponent);
        trampleSource.addStaticAbility("Mode$ Continuous"
                + " | Affected$ Creature.YouCtrl+powerGE4 | AddKeyword$ Trample");
        addCard("Water Elemental", opponent);
        addCard("Grizzly Bears", opponent);
        game.getAction().checkStaticAbilities();

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(trampleSource));

        Assert.assertTrue(values.getOrDefault(trampleSource, 0) > 0);
    }

    @Test
    public void testAiEffectValueIsAppliedPerAffectedCard() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card taxingSource = addCard("Grizzly Bears", opponent);
        taxingSource.addStaticAbility("Mode$ Continuous | Affected$ Artifact | AIEffectValue$ -10");
        addCard("Sol Ring", opponent);
        addCard("Sol Ring", ai);
        addCard("Sol Ring", ai);
        game.getAction().checkStaticAbilities();

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(taxingSource));

        Assert.assertEquals(values.get(taxingSource).intValue(), 10,
                "One enemy and two allied affected artifacts produce a net threat value of 10");
    }

    @Test
    public void testNegativeRelationshipValueReducesRemovalPriority() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card beneficialEnemyPermanent = addCard("Sol Ring", opponent);
        beneficialEnemyPermanent.addStaticAbility("Mode$ Continuous | Affected$ Creature"
                + " | AddPower$ 1 | AddToughness$ 1");
        final Card ordinaryEnemyPermanent = addCard("Sol Ring", opponent);
        addCard("Grizzly Bears", ai);
        game.getAction().checkStaticAbilities();

        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Mastermind");

        Assert.assertSame(ComputerUtilCard.getBestRemovalTargetAI(
                ai, List.of(beneficialEnemyPermanent, ordinaryEnemyPermanent)), ordinaryEnemyPermanent);
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

    private Card addPhaseCounterProducer(final String cardName, final Player controller,
            final String counterType, final int amount, final String defined) {
        final Card card = addCard(cardName, controller);
        card.setSVar("EffectTestCounterProduction", "DB$ PutCounter | Defined$ " + defined
                + " | CounterType$ " + counterType + " | CounterNum$ " + amount);
        addTrigger(card, "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You"
                + " | Execute$ EffectTestCounterProduction | TriggerZones$ Battlefield");
        return card;
    }

    private Card addCounterAddedConsequence(final String cardName, final Player controller,
            final String triggerMode, final String counterType, final String validity) {
        final Card card = addCard(cardName, controller);
        card.setSVar("EffectTestCounterOutcome",
                "DB$ PutCounter | ValidTgts$ Creature.YouCtrl"
                        + " | CounterType$ P1P1 | CounterNum$ 1");
        final String counterTypeParam = counterType == null
                ? "" : " | CounterType$ " + counterType;
        addTrigger(card, "Mode$ " + triggerMode + " | " + validity + counterTypeParam
                + " | Execute$ EffectTestCounterOutcome | TriggerZones$ Battlefield");
        return card;
    }

    private Card addTokenCounterConsequence(final String cardName, final Player controller) {
        return addTokenCounterConsequence(cardName, controller, "TokenCreated");
    }

    private Card addTokenCounterConsequence(final String cardName, final Player controller,
            final String triggerMode) {
        final Card card = addCard(cardName, controller);
        card.setSVar("EffectTestCounter",
                "DB$ PutCounter | ValidTgts$ Creature.YouCtrl+Other | CounterType$ P1P1");
        final String validPlayer = "TokenCreated".equals(triggerMode) ? " | ValidPlayer$ You" : "";
        addTrigger(card, "Mode$ " + triggerMode + validPlayer + " | ValidToken$ Card.token+YouCtrl"
                + " | Execute$ EffectTestCounter | TriggerZones$ Battlefield");
        return card;
    }

    private Card addTokenPumpConsequence(final String cardName, final Player controller,
            final String power, final String toughness, final String duration, final String defined) {
        final Card card = addCard(cardName, controller);
        final String durationParam = duration == null ? "" : " | Duration$ " + duration;
        card.setSVar("EffectTestPump", "DB$ Pump | Defined$ " + defined
                + " | NumAtt$ " + power + " | NumDef$ " + toughness + durationParam);
        addTrigger(card, "Mode$ TokenCreated | ValidPlayer$ You | ValidToken$ Card.token+YouCtrl"
                + " | Execute$ EffectTestPump | TriggerZones$ Battlefield");
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
