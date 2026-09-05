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
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;

public class EffectRelationshipEvaluatorTest extends AITest {
    @Test
    public void testExpectedBlockEvaluatesSupportedBlocksOutcome() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);
        opponent.setLife(1, null);

        final Card attacker = addCard("Grizzly Bears", ai);
        attacker.setSickness(false);
        final Card secondAttacker = addCard("Runeclaw Bear", ai);
        secondAttacker.setSickness(false);
        final Card blocker = addCard("Bear Cub", opponent);
        blocker.setSVar("EffectTestBlockOutcome",
                "DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1");
        addTrigger(blocker, "Mode$ Blocks | ValidCard$ Card.Self"
                + " | Execute$ EffectTestBlockOutcome | TriggerZones$ Battlefield");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(blocker));

        Assert.assertTrue(values.getOrDefault(blocker, 0) > 0, values.toString());
    }

    @Test
    public void testExpectedBlockedAttackerEvaluatesOutcome() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);
        ai.setLife(1, null);

        addCard("Grizzly Bears", ai);
        final Card attacker = addCard("Craw Wurm", opponent);
        attacker.setSickness(false);
        attacker.setSVar("EffectTestBlockedOutcome",
                "DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1");
        addTrigger(attacker, "Mode$ AttackerBlocked | ValidCard$ Card.Self"
                + " | Execute$ EffectTestBlockedOutcome | TriggerZones$ Battlefield");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opponent);
        game.getAction().checkStateEffects(true);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(attacker));

        Assert.assertTrue(values.getOrDefault(attacker, 0) > 0, values.toString());
    }

    @Test
    public void testExpectedUnblockedAttackerEvaluatesOutcome() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card attacker = addCard("Grizzly Bears", opponent);
        attacker.setSickness(false);
        attacker.setSVar("EffectTestUnblockedOutcome",
                "DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1");
        addTrigger(attacker, "Mode$ AttackerUnblocked | ValidCard$ Card.Self"
                + " | Execute$ EffectTestUnblockedOutcome | TriggerZones$ Battlefield");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opponent);
        game.getAction().checkStateEffects(true);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(attacker));

        Assert.assertTrue(values.getOrDefault(attacker, 0) > 0, values.toString());
    }

    @Test
    public void testExpectedAttackEvaluatesSupportedAttackOutcome() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card attacker = addCard("Grizzly Bears", opponent);
        attacker.setSickness(false);
        attacker.setSVar("EffectTestAttackOutcome",
                "DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1");
        addTrigger(attacker, "Mode$ Attacks | ValidCard$ Card.Self"
                + " | Execute$ EffectTestAttackOutcome | TriggerZones$ Battlefield");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(attacker));

        Assert.assertTrue(values.getOrDefault(attacker, 0) > 0, values.toString());
    }

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
    public void testCreatureTokensProduceBattlefieldEntryEvents() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card oneTokenProducer = addPhaseTokenProducer(
                "Grizzly Bears", opponent, 1);
        final Card threeTokenProducer = addPhaseTokenProducer(
                "Runeclaw Bear", opponent, 3);
        final Card consequence = addZoneEntryCounterConsequence(
                "Bear Cub", opponent, "Creature.YouCtrl");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(oneTokenProducer, threeTokenProducer, consequence));

        Assert.assertTrue(values.getOrDefault(oneTokenProducer, 0) > 0, values.toString());
        Assert.assertEquals(values.get(threeTokenProducer).intValue(),
                values.get(oneTokenProducer) * 3);
        Assert.assertEquals(values.get(consequence).intValue(),
                values.get(oneTokenProducer) + values.get(threeTokenProducer));
    }

    @Test
    public void testTokenBattlefieldEntryChecksCardValidity() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseTokenProducer("Grizzly Bears", opponent, 1);
        final Card consequence = addZoneEntryCounterConsequence(
                "Runeclaw Bear", opponent, "Artifact.YouCtrl");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.isEmpty(), values.toString());
    }

    @Test
    public void testOpponentControlledTokenDoesNotMatchYourEntryTrigger() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseTokenProducer(
                "Grizzly Bears", opponent, 1, "Opponent");
        final Card consequence = addZoneEntryCounterConsequence(
                "Runeclaw Bear", opponent, "Creature.YouCtrl");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.isEmpty(), values.toString());
    }

    @Test
    public void testCopiedTokensProduceBattlefieldEntryEvents() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addCard("Grizzly Bears", opponent);
        producer.setSVar("EffectTestCopy", "DB$ CopyPermanent | Defined$ Self");
        addTrigger(producer, "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You"
                + " | Execute$ EffectTestCopy | TriggerZones$ Battlefield");
        final Card consequence = addZoneEntryCounterConsequence(
                "Runeclaw Bear", opponent, "Creature.YouCtrl");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0, values.toString());
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testTokenBatchEntryTriggerResolvesOncePerBatch() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card oneTokenProducer = addPhaseTokenProducer(
                "Grizzly Bears", opponent, 1);
        final Card threeTokenProducer = addPhaseTokenProducer(
                "Runeclaw Bear", opponent, 3);
        final Card consequence = addZoneEntryBatchCounterConsequence(
                "Bear Cub", opponent, "Creature.YouCtrl", "1");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(oneTokenProducer, threeTokenProducer, consequence));

        Assert.assertTrue(values.getOrDefault(oneTokenProducer, 0) > 0, values.toString());
        Assert.assertEquals(values.get(threeTokenProducer), values.get(oneTokenProducer));
        Assert.assertEquals(values.get(consequence).intValue(), values.get(oneTokenProducer) * 2);
    }

    @Test
    public void testTokenBatchEntryExposesMatchingCardAmount() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card oneTokenProducer = addPhaseTokenProducer(
                "Grizzly Bears", opponent, 1);
        final Card threeTokenProducer = addPhaseTokenProducer(
                "Runeclaw Bear", opponent, 3);
        final Card consequence = addZoneEntryBatchCounterConsequence(
                "Bear Cub", opponent, "Creature.YouCtrl", "X");
        consequence.setSVar("X", "TriggerCount$Amount");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(oneTokenProducer, threeTokenProducer, consequence));

        Assert.assertTrue(values.getOrDefault(oneTokenProducer, 0) > 0, values.toString());
        Assert.assertEquals(values.get(threeTokenProducer).intValue(),
                values.get(oneTokenProducer) * 3);
    }

    @Test
    public void testLifeGainProductionMatchesDefinedSelfCounterConsequence() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseLifeGainProducer(
                "Grizzly Bears", opponent, 3, "You");
        final Card consequence = addCard("Hallowed Priest", opponent);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0, values.toString());
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testPayableActivatedLifeGainIsAProduction() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addCard("Grizzly Bears", opponent);
        producer.addSpellAbility(AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ 0 | Defined$ You | LifeAmount$ 2", producer));
        final Card consequence = addCard("Hallowed Priest", opponent);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0, values.toString());
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testLifeGainAmountScalesTriggeredCounterOutcome() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card oneLife = addPhaseLifeGainProducer(
                "Grizzly Bears", opponent, 1, "You");
        final Card threeLife = addPhaseLifeGainProducer(
                "Runeclaw Bear", opponent, 3, "You");
        final Card consequence = addCard("Bear Cub", opponent);
        consequence.setSVar("X", "TriggerCount$LifeAmount");
        consequence.setSVar("EffectTestLifeOutcome",
                "DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ X");
        addTrigger(consequence, "Mode$ LifeGained | ValidPlayer$ You"
                + " | Execute$ EffectTestLifeOutcome | TriggerZones$ Battlefield");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(oneLife, threeLife, consequence));

        Assert.assertTrue(values.getOrDefault(oneLife, 0) > 0, values.toString());
        Assert.assertTrue(values.getOrDefault(threeLife, 0) > values.get(oneLife), values.toString());
        Assert.assertEquals(values.get(consequence).intValue(),
                values.get(oneLife) + values.get(threeLife));
    }

    @Test
    public void testLifeGainConsequenceChecksGainingPlayer() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseLifeGainProducer(
                "Grizzly Bears", opponent, 3, "Opponent");
        final Card consequence = addCard("Hallowed Priest", opponent);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.isEmpty(), values.toString());
    }

    @Test
    public void testAttackingLifelinkCreatureProducesLifeGain() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addCard("Runeclaw Bear", opponent);
        producer.addIntrinsicKeyword("Lifelink");
        final Card consequence = addCard("Hallowed Priest", opponent);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0, values.toString());
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testDoubleStrikeCreatesTwoLifelinkGainEvents() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card singleStrike = addCard("Runeclaw Bear", opponent);
        singleStrike.addIntrinsicKeyword("Lifelink");
        final Card doubleStrike = addCard("Grizzly Bears", opponent);
        doubleStrike.addIntrinsicKeyword("Lifelink");
        doubleStrike.addIntrinsicKeyword("Double Strike");
        final Card consequence = addCard("Hallowed Priest", opponent);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(singleStrike, doubleStrike, consequence));

        Assert.assertTrue(values.getOrDefault(singleStrike, 0) > 0, values.toString());
        Assert.assertEquals(values.get(doubleStrike).intValue(), values.get(singleStrike) * 2);
    }

    @Test
    public void testCombatDamageDerivedLifeGainUsesDamageAmount() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card combatProducer = addCard("Runeclaw Bear", opponent);
        combatProducer.setSVar("X", "TriggerCount$DamageAmount");
        combatProducer.setSVar("EffectTestCombatLifeGain",
                "DB$ GainLife | Defined$ You | LifeAmount$ X");
        addTrigger(combatProducer, "Mode$ DamageDone | ValidSource$ Card.Self"
                + " | ValidTarget$ Player | CombatDamage$ True"
                + " | Execute$ EffectTestCombatLifeGain | TriggerZones$ Battlefield");
        final Card fixedProducer = addPhaseLifeGainProducer(
                "Grizzly Bears", opponent, 2, "You");
        final Card consequence = addCard("Bear Cub", opponent);
        consequence.setSVar("X", "TriggerCount$LifeAmount");
        consequence.setSVar("EffectTestLifeOutcome",
                "DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ X");
        addTrigger(consequence, "Mode$ LifeGained | ValidPlayer$ You"
                + " | Execute$ EffectTestLifeOutcome | TriggerZones$ Battlefield");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(combatProducer, fixedProducer, consequence));

        Assert.assertTrue(values.getOrDefault(combatProducer, 0) > 0, values.toString());
        Assert.assertEquals(values.get(combatProducer), values.get(fixedProducer));
    }

    @Test
    public void testUnrestrictedDamageTriggerIncludesEstimatedCombatDamage() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addCard("Runeclaw Bear", opponent);
        producer.setSVar("X", "TriggerCount$DamageAmount");
        producer.setSVar("EffectTestDamageLifeGain",
                "DB$ GainLife | Defined$ You | LifeAmount$ X");
        addTrigger(producer, "Mode$ DamageDone | ValidSource$ Card.Self"
                + " | ValidTarget$ Player | Execute$ EffectTestDamageLifeGain"
                + " | TriggerZones$ Battlefield");
        final Card consequence = addCard("Hallowed Priest", opponent);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0, values.toString());
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testLifeGainConsequenceMatchesValidSource() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseLifeGainProducer(
                "Grizzly Bears", opponent, 2, "You");
        final Card matchingConsequence = addCard("Bear Cub", opponent);
        matchingConsequence.setSVar("EffectTestLifeOutcome",
                "DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1");
        addTrigger(matchingConsequence, "Mode$ LifeGained | ValidPlayer$ You"
                + " | ValidSource$ Creature | Execute$ EffectTestLifeOutcome"
                + " | TriggerZones$ Battlefield");
        final Card nonmatchingConsequence = addCard("Runeclaw Bear", opponent);
        nonmatchingConsequence.setSVar("EffectTestLifeOutcome",
                "DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1");
        addTrigger(nonmatchingConsequence, "Mode$ LifeGained | ValidPlayer$ You"
                + " | ValidSource$ Artifact | Execute$ EffectTestLifeOutcome"
                + " | TriggerZones$ Battlefield");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, matchingConsequence, nonmatchingConsequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0, values.toString());
        Assert.assertEquals(values.get(producer), values.get(matchingConsequence));
        Assert.assertFalse(values.containsKey(nonmatchingConsequence), values.toString());
    }

    @Test
    public void testFixedDamageMatchesDamageDoneConsequence() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseDamageProducer(
                "Grizzly Bears", opponent, "DealDamage", "Defined$ Self", 1);
        final Card consequence = addDamageCounterConsequence(
                "Runeclaw Bear", opponent, "DamageDone",
                "ValidSource$ Creature.YouCtrl | ValidTarget$ Creature.YouCtrl", "1");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0, values.toString());
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testPayableActivatedDamageIsAProduction() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addCard("Grizzly Bears", opponent);
        producer.addSpellAbility(AbilityFactory.getAbility(
                "AB$ DealDamage | Cost$ 0 | Defined$ Self | NumDmg$ 1", producer));
        final Card consequence = addDamageCounterConsequence(
                "Runeclaw Bear", opponent, "DamageDone",
                "ValidSource$ Creature.YouCtrl | ValidTarget$ Creature.YouCtrl", "1");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0, values.toString());
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testDamageConsequenceChecksAmountAndCombatKind() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseDamageProducer(
                "Grizzly Bears", opponent, "DealDamage", "Defined$ Self", 1);
        final Card matching = addDamageCounterConsequence(
                "Runeclaw Bear", opponent, "DamageDone",
                "ValidTarget$ Creature.YouCtrl | DamageAmount$ EQ1"
                        + " | CombatDamage$ False", "1");
        final Card wrongAmount = addDamageCounterConsequence(
                "Bear Cub", opponent, "DamageDone",
                "ValidTarget$ Creature.YouCtrl | DamageAmount$ GE2", "1");
        final Card combatOnly = addDamageCounterConsequence(
                "Memnite", opponent, "DamageDone",
                "ValidTarget$ Creature.YouCtrl | CombatDamage$ True", "1");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, matching, wrongAmount, combatOnly));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0, values.toString());
        Assert.assertEquals(values.get(producer), values.get(matching));
        Assert.assertFalse(values.containsKey(wrongAmount), values.toString());
        Assert.assertFalse(values.containsKey(combatOnly), values.toString());
    }

    @Test
    public void testDamageAmountScalesTriggeredOutcome() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card oneDamage = addPhaseDamageProducer(
                "Grizzly Bears", opponent, "DealDamage", "Defined$ Self", 1);
        final Card threeDamage = addPhaseDamageProducer(
                "Runeclaw Bear", opponent, "DealDamage", "Defined$ Self", 3);
        final Card consequence = addDamageCounterConsequence(
                "Bear Cub", opponent, "DamageDone",
                "ValidSource$ Creature.YouCtrl | ValidTarget$ Creature.YouCtrl",
                "X");
        consequence.setSVar("X", "TriggerCount$DamageAmount");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(oneDamage, threeDamage, consequence));

        Assert.assertTrue(values.getOrDefault(oneDamage, 0) > 0, values.toString());
        Assert.assertTrue(values.getOrDefault(threeDamage, 0) > values.get(oneDamage),
                values.toString());
    }

    @Test
    public void testDamageDoneOnceGroupsDamageByRecipient() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card oneRecipient = addPhaseDamageProducer(
                "Grizzly Bears", opponent, "DealDamage", "Defined$ Self", 1);
        final Card allRecipients = addPhaseDamageProducer(
                "Sol Ring", opponent, "DamageAll", "ValidCards$ Creature.YouCtrl", 1);
        final Card consequence = addDamageCounterConsequence(
                "Runeclaw Bear", opponent, "DamageDoneOnce",
                "ValidSource$ Card.YouCtrl | ValidTarget$ Creature.YouCtrl", "1");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(oneRecipient, allRecipients, consequence));

        Assert.assertTrue(values.getOrDefault(oneRecipient, 0) > 0, values.toString());
        Assert.assertTrue(values.getOrDefault(allRecipients, 0) > values.get(oneRecipient),
                values.toString());
    }

    @Test
    public void testDamageDealtOnceGroupsDamageBySource() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card oneRecipient = addPhaseDamageProducer(
                "Grizzly Bears", opponent, "DealDamage", "Defined$ Self", 1);
        final Card allRecipients = addPhaseDamageProducer(
                "Sol Ring", opponent, "DamageAll", "ValidCards$ Creature.YouCtrl", 1);
        final Card consequence = addDamageCounterConsequence(
                "Runeclaw Bear", opponent, "DamageDealtOnce",
                "ValidSource$ Card.YouCtrl | ValidTarget$ Creature.YouCtrl", "1");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(oneRecipient, allRecipients, consequence));

        Assert.assertTrue(values.getOrDefault(oneRecipient, 0) > 0, values.toString());
        Assert.assertEquals(values.get(allRecipients), values.get(oneRecipient));
    }

    @Test
    public void testTargetedDamageIsNotYetAProduction() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addCard("Prodigal Pyromancer", opponent);
        final Card consequence = addDamageCounterConsequence(
                "Runeclaw Bear", opponent, "DamageDone",
                "ValidSource$ Creature.YouCtrl | ValidTarget$ Creature", "1");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.isEmpty(), values.toString());
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
    public void testTransformOutcomeUsesResultingPermanentValue() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card consequence = addCounterTriggeredOutcome("Delver of Secrets", opponent,
                "DB$ SetState | Defined$ Self | Mode$ Transform");
        final long timestampBeforeAnalysis = game.getTimestamp();

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0, values.toString());
        Assert.assertEquals(values.get(producer), values.get(consequence));
        Assert.assertEquals(game.getTimestamp(), timestampBeforeAnalysis,
                "Analysis-only transform must not consume live game timestamps");
    }

    @Test
    public void testTurnFaceDownOutcomeUsesResultingPermanentValue() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card consequence = addCounterTriggeredOutcome("Serra Angel", opponent,
                "DB$ SetState | Defined$ Self | Mode$ TurnFaceDown");
        final long timestampBeforeAnalysis = game.getTimestamp();

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) < 0, values.toString());
        Assert.assertEquals(values.get(producer), values.get(consequence));
        Assert.assertEquals(game.getTimestamp(), timestampBeforeAnalysis,
                "Analysis-only face change must not consume live game timestamps");
    }

    @Test
    public void testTurnFaceUpOutcomeUsesKnownAiControlledCard() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card consequence = addCounterTriggeredOutcome("Grizzly Bears", opponent,
                "DB$ SetState | ValidTgts$ Creature.faceDown+OppCtrl | Mode$ TurnFaceUp");
        final Card faceDown = addCard("Serra Angel", ai);
        faceDown.turnFaceDown(true);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) < 0, values.toString());
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testTurnFaceUpOutcomeDoesNotReadOpponentHiddenCard() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card consequence = addCounterTriggeredOutcome("Grizzly Bears", opponent,
                "DB$ SetState | ValidTgts$ Creature.faceDown+YouCtrl | Mode$ TurnFaceUp");
        final Card hidden = addCard("Serra Angel", opponent);
        hidden.turnFaceDown(true);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.isEmpty(), values.toString());
    }

    @Test
    public void testDestroyAndPermanentExileOutcomesUseRemovedPermanentValue() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card destroy = addCounterTriggeredOutcome("Grizzly Bears", opponent,
                "DB$ Destroy | ValidTgts$ Creature.OppCtrl");
        final Card exile = addCounterTriggeredOutcome("Runeclaw Bear", opponent,
                "DB$ ChangeZone | ValidTgts$ Creature.OppCtrl"
                        + " | Origin$ Battlefield | Destination$ Exile");
        addCard("Serra Angel", ai);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, destroy, exile));

        Assert.assertTrue(values.getOrDefault(destroy, 0) > 0, values.toString());
        Assert.assertEquals(values.get(destroy), values.get(exile));
        Assert.assertEquals(values.get(producer).intValue(),
                values.get(destroy) + values.get(exile));
    }

    @Test
    public void testDestroyOutcomeIgnoresPermanentThatWouldRemain() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card consequence = addCounterTriggeredOutcome("Grizzly Bears", opponent,
                "DB$ Destroy | ValidTgts$ Creature.OppCtrl");
        addCard("Darksteel Myr", ai);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.isEmpty(), values.toString());
    }

    @Test
    public void testKnownSacrificeOutcomesUseDepartingPermanentValue() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card selfSacrifice = addCounterTriggeredOutcome("Grizzly Bears", opponent,
                "DB$ Sacrifice | SacValid$ Self");
        final Card forcedSacrifice = addCounterTriggeredOutcome("Runeclaw Bear", opponent,
                "DB$ Sacrifice | Defined$ Opponent | SacValid$ Creature");
        addCard("Serra Angel", ai);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, selfSacrifice, forcedSacrifice));

        Assert.assertTrue(values.getOrDefault(selfSacrifice, 0) < 0, values.toString());
        Assert.assertTrue(values.getOrDefault(forcedSacrifice, 0) > 0, values.toString());
        Assert.assertEquals(values.get(producer).intValue(),
                values.get(selfSacrifice) + values.get(forcedSacrifice));
    }

    @Test
    public void testPlayerChosenSacrificeOutcomeIsDeferred() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card consequence = addCounterTriggeredOutcome("Grizzly Bears", opponent,
                "DB$ Sacrifice | Defined$ Opponent | SacValid$ Creature");
        addCard("Serra Angel", ai);
        addCard("Runeclaw Bear", ai);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.isEmpty(), values.toString());
    }

    @Test
    public void testSacrificeCostProducesSacrificeAndDiesEvents() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card outlet = addCard("Viscera Seer", opponent);
        outlet.addSpellAbility(AbilityFactory.getAbility(
                "AB$ Scry | Cost$ Sac<1/Creature.Bear> | ScryNum$ 1", outlet));
        addCard("Grizzly Bears", opponent);
        final Card sacrificeConsequence = addSacrificeCounterConsequence(
                "Memnite", opponent, "Sacrificed", "Creature.YouCtrl", "1");
        final Card diesConsequence = addDiesCounterConsequence(
                "Ornithopter", opponent, "Creature.YouCtrl", "1");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(outlet, sacrificeConsequence, diesConsequence));

        Assert.assertTrue(values.getOrDefault(sacrificeConsequence, 0) > 0, values.toString());
        Assert.assertTrue(values.getOrDefault(diesConsequence, 0) > 0, values.toString());
        Assert.assertEquals(values.get(outlet).intValue(),
                values.get(sacrificeConsequence) + values.get(diesConsequence));
    }

    @Test
    public void testSacrificedOnceObservesWholeCostBatch() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card outlet = addCard("Viscera Seer", opponent);
        outlet.addSpellAbility(AbilityFactory.getAbility(
                "AB$ Scry | Cost$ Sac<2/Creature.Bear> | ScryNum$ 1", outlet));
        addCard("Grizzly Bears", opponent);
        addCard("Runeclaw Bear", opponent);
        final Card consequence = addSacrificeCounterConsequence(
                "Memnite", opponent, "SacrificedOnce", "Creature.YouCtrl", "X");
        consequence.setSVar("X", "TriggerCount$Amount");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(outlet, consequence));

        Assert.assertTrue(values.getOrDefault(outlet, 0) > 0, values.toString());
        Assert.assertEquals(values.get(outlet), values.get(consequence));
    }

    @Test
    public void testSelfSacrificeCanMatchOwnDiesTrigger() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addCard("Grizzly Bears", opponent);
        producer.addSpellAbility(AbilityFactory.getAbility(
                "AB$ Scry | Cost$ Sac<1/CARDNAME> | ScryNum$ 1", producer));
        producer.setSVar("EffectTestDiesToken",
                "DB$ Token | TokenScript$ w_1_1_soldier | TokenOwner$ You");
        addTrigger(producer, "Mode$ ChangesZone | Origin$ Battlefield"
                + " | Destination$ Graveyard | ValidCard$ Card.Self"
                + " | Execute$ EffectTestDiesToken | TriggerZones$ Battlefield");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0, values.toString());
    }

    @Test
    public void testTriggeredSacrificeEffectProducesDiesEvent() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addCard("Sol Ring", opponent);
        producer.setSVar("EffectTestSacrifice",
                "DB$ Sacrifice | Defined$ You | SacValid$ Creature.Bear");
        addTrigger(producer, "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You"
                + " | Execute$ EffectTestSacrifice | TriggerZones$ Battlefield");
        addCard("Grizzly Bears", opponent);
        final Card consequence = addDiesCounterConsequence(
                "Memnite", opponent, "Creature.YouCtrl", "1");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0, values.toString());
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testPermanentControlChangeCountsLossAndGain() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card consequence = addCounterTriggeredOutcome("Grizzly Bears", opponent,
                "DB$ GainControl | ValidTgts$ Creature.OppCtrl");
        final Card target = addCard("Serra Angel", ai);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        final int targetValue = ComputerUtilCard.evaluatePermanent(ai, target);
        Assert.assertEquals(values.get(consequence).intValue(), targetValue * 2);
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testGivingPermanentToAiHasNegativeOutcomeValue() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card consequence = addCounterTriggeredOutcome("Grizzly Bears", opponent,
                "DB$ GainControl | Defined$ Self | NewController$ Opponent");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(consequence, 0) < 0, values.toString());
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testAttachingEquipmentOrCurseAuraUsesResultingCardStates() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card equipment = addCounterTriggeredOutcome("Bonesplitter", opponent,
                "DB$ Attach | ValidTgts$ Creature.YouCtrl");
        final Card aura = addCounterTriggeredOutcome("Dead Weight", opponent,
                "DB$ Attach | ValidTgts$ Creature.OppCtrl");
        addCard("Craw Wurm", opponent);
        addCard("Serra Angel", ai);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, equipment, aura));

        Assert.assertTrue(values.getOrDefault(equipment, 0) > 0, values.toString());
        Assert.assertTrue(values.getOrDefault(aura, 0) > 0, values.toString());
    }

    @Test
    public void testUnattachingEquipmentOrAuraUsesResultingCardStates() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card equipment = addCard("Bonesplitter", opponent);
        equipment.attachToEntity(addCard("Craw Wurm", opponent), null);
        final Card aura = addCard("Dead Weight", opponent);
        aura.attachToEntity(addCard("Serra Angel", ai), null);
        game.getAction().checkStaticAbilities(false);

        final Card detachEquipment = addCounterTriggeredOutcome("Grizzly Bears", opponent,
                "DB$ Unattach | Defined$ Remembered");
        detachEquipment.addRemembered(equipment);
        final Card detachAura = addCounterTriggeredOutcome("Runeclaw Bear", opponent,
                "DB$ Unattach | Defined$ Remembered");
        detachAura.addRemembered(aura);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, detachEquipment, detachAura));

        Assert.assertTrue(values.getOrDefault(detachEquipment, 0) < 0, values.toString());
        Assert.assertTrue(values.getOrDefault(detachAura, 0) < 0, values.toString());
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

        Assert.assertTrue(values.getOrDefault(rosie, 0) > 0);
        Assert.assertTrue(values.get(jet) > values.get(rosie),
                "Jet should include both its attack outcome and its relationship with Rosie");
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
    public void testSupportedCounterOutcomesUsePermanentEvaluation() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card plusCounter = addCounterOutcomeConsequence(
                "Grizzly Bears", opponent, "P1P1", "Creature.YouCtrl");
        final Card minusCounter = addCounterOutcomeConsequence(
                "Runeclaw Bear", opponent, "M1M1", "Creature.YouCtrl");
        final Card shieldCounter = addCounterOutcomeConsequence(
                "Bear Cub", opponent, "SHIELD", "Creature.YouCtrl");
        final Card stunCounter = addCounterOutcomeConsequence(
                "Centaur Courser", opponent, "STUN", "Creature.YouCtrl");
        final Card loyaltyCounter = addCounterOutcomeConsequence(
                "Mox Amber", opponent, "LOYALTY", "Planeswalker.YouCtrl");
        addCard("Jace Beleren", opponent);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, plusCounter, minusCounter,
                        shieldCounter, stunCounter, loyaltyCounter));

        Assert.assertTrue(values.getOrDefault(plusCounter, 0) > 0);
        Assert.assertTrue(values.getOrDefault(minusCounter, 0) < 0);
        Assert.assertTrue(values.getOrDefault(shieldCounter, 0) > 0);
        Assert.assertTrue(values.getOrDefault(stunCounter, 0) < 0);
        Assert.assertEquals(values.getOrDefault(loyaltyCounter, 0).intValue(), 10);
    }

    @Test
    public void testKeywordAndRestrictionOutcomesUseCreatureEvaluation() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card flyingGain = addCounterTriggeredOutcome("Grizzly Bears", opponent,
                "DB$ Pump | Defined$ Self | KW$ Flying | Duration$ Permanent");
        final Card flyingLoss = addCounterTriggeredOutcome("Wind Drake", opponent,
                "DB$ Debuff | Defined$ Self | Keywords$ Flying | Duration$ Permanent");
        final Card defender = addCounterTriggeredOutcome("Runeclaw Bear", opponent,
                "DB$ Pump | Defined$ Self | KW$ Defender");
        final Card defenderLoss = addCounterTriggeredOutcome("Wall of Wood", opponent,
                "DB$ Animate | Defined$ Self | RemoveKeywords$ Defender"
                        + " | Duration$ Permanent");
        final Card combatRestriction = addCounterTriggeredOutcome("Centaur Courser", opponent,
                "DB$ Pump | Defined$ Self | KW$ HIDDEN CARDNAME can't attack or block.");
        final Card untapRestriction = addCounterTriggeredOutcome("Craw Wurm", opponent,
                "DB$ Pump | Defined$ Self"
                        + " | KW$ HIDDEN This card doesn't untap during your next untap step."
                        + " | Duration$ Permanent");
        untapRestriction.setTapped(true);
        final Card detain = addCounterTriggeredOutcome("Bear Cub", opponent,
                "DB$ Detain | Defined$ Self");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, flyingGain, flyingLoss, defender, defenderLoss,
                        combatRestriction, untapRestriction, detain));

        Assert.assertTrue(values.getOrDefault(flyingGain, 0) > 0, values.toString());
        Assert.assertTrue(values.getOrDefault(flyingLoss, 0) < 0, values.toString());
        Assert.assertTrue(values.getOrDefault(defender, 0) < 0, values.toString());
        Assert.assertTrue(values.getOrDefault(defenderLoss, 0) > 0, values.toString());
        Assert.assertTrue(values.getOrDefault(combatRestriction, 0) < 0, values.toString());
        Assert.assertTrue(values.getOrDefault(untapRestriction, 0) < 0, values.toString());
        Assert.assertTrue(values.getOrDefault(detain, 0) < 0, values.toString());
    }

    @Test
    public void testPersistentPumpIncludesBothPtAndKeywordValue() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card ptOnly = addCounterTriggeredOutcome("Grizzly Bears", opponent,
                "DB$ Pump | Defined$ Self | NumAtt$ +1 | Duration$ Permanent");
        final Card ptAndFlying = addCounterTriggeredOutcome("Runeclaw Bear", opponent,
                "DB$ Pump | Defined$ Self | NumAtt$ +1 | KW$ Flying | Duration$ Permanent");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, ptOnly, ptAndFlying));

        Assert.assertTrue(values.getOrDefault(ptAndFlying, 0)
                > values.getOrDefault(ptOnly, 0));
    }

    @Test
    public void testGroupKeywordOutcomeAggregatesAffectedCreatures() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card consequence = addCounterTriggeredOutcome("Mox Amber", opponent,
                "DB$ PumpAll | ValidCards$ Creature.YouCtrl | KW$ Flying"
                        + " | Duration$ Permanent");
        addCard("Grizzly Bears", opponent);
        addCard("Runeclaw Bear", opponent);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(consequence, 0) > 0);
    }

    @Test
    public void testCreatureTokenOutcomeUsesPrototypeValueAndAmount() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card oneTokenConsequence = addCounterTokenConsequence(
                "Grizzly Bears", opponent, 1, "w_1_1_soldier");
        final Card threeTokenConsequence = addCounterTokenConsequence(
                "Runeclaw Bear", opponent, 3, "w_1_1_soldier");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, oneTokenConsequence, threeTokenConsequence));

        Assert.assertTrue(values.getOrDefault(oneTokenConsequence, 0) > 0);
        Assert.assertEquals(values.get(threeTokenConsequence).intValue(),
                values.get(oneTokenConsequence) * 3);
        Assert.assertEquals(values.get(producer).intValue(),
                values.get(oneTokenConsequence) + values.get(threeTokenConsequence));
    }

    @Test
    public void testMultipleCreatureTokenScriptsAreValuedTogether() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card singleTokenConsequence = addCounterTokenConsequence(
                "Grizzly Bears", opponent, 1, "w_1_1_soldier");
        final Card multipleTokenConsequence = addCounterTokenConsequence(
                "Runeclaw Bear", opponent, 1, "w_1_1_soldier,g_3_3_beast");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, singleTokenConsequence, multipleTokenConsequence));

        Assert.assertTrue(values.get(multipleTokenConsequence)
                > values.get(singleTokenConsequence));
    }

    @Test
    public void testCreatureTokenOutcomeCanUseTriggerAmount() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 3, "Self");
        final Card fixedConsequence = addCounterTokenConsequence(
                "Grizzly Bears", opponent, 1, "w_1_1_soldier");
        final Card dynamicConsequence = addCounterTokenConsequence(
                "Runeclaw Bear", opponent, "X", "w_1_1_soldier");
        dynamicConsequence.setSVar("X", "TriggerCount$Amount");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, fixedConsequence, dynamicConsequence));

        Assert.assertEquals(values.get(dynamicConsequence).intValue(),
                values.get(fixedConsequence) * 3);
    }

    @Test
    public void testOpponentOwnedCreatureTokenOutcomeReducesThreat() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card consequence = addCounterTokenConsequence(
                "Grizzly Bears", opponent, 1, "w_1_1_soldier", "Opponent", "");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(consequence, 0) < 0);
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testOpponentOwnedTokenProductionUsesActualCreator() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseTokenProducer(
                "Sol Ring", opponent, 1, "Opponent");
        final Card consequence = addCard("Grizzly Bears", opponent);
        consequence.setSVar("EffectTestCounter", "DB$ PutCounter"
                + " | ValidTgts$ Creature.YouCtrl | CounterType$ P1P1");
        addTrigger(consequence, "Mode$ TokenCreatedOnce | ValidToken$ Creature.OppCtrl"
                + " | Execute$ EffectTestCounter | TriggerZones$ Battlefield");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0);
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testTokenCombatEntryFlagsDoNotChangeOutcomeValue() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card ordinary = addCounterTokenConsequence(
                "Grizzly Bears", opponent, 1, "w_1_1_soldier");
        final Card attacking = addCounterTokenConsequence(
                "Runeclaw Bear", opponent, 1, "w_1_1_soldier",
                " | TokenAttacking$ True");
        final Card blocking = addCounterTokenConsequence(
                "Bear Cub", opponent, 1, "w_1_1_soldier",
                " | TokenBlocking$ TriggeredCard");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, ordinary, attacking, blocking));

        Assert.assertEquals(values.get(attacking), values.get(ordinary));
        Assert.assertEquals(values.get(blocking), values.get(ordinary));
    }

    @Test
    public void testKnownCopiedPermanentOutcomeUsesCopyValueAndAmount() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card oneCopy = addCounterCopyConsequence(
                "Grizzly Bears", opponent, 1);
        final Card twoCopies = addCounterCopyConsequence(
                "Runeclaw Bear", opponent, 2);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, oneCopy, twoCopies));

        Assert.assertTrue(values.getOrDefault(oneCopy, 0) > 0);
        Assert.assertEquals(values.get(twoCopies).intValue(), values.get(oneCopy) * 2);
    }

    @Test
    public void testKnownCopiedPermanentProducesTokenCreatedEvents() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addCard("Grizzly Bears", opponent);
        producer.setSVar("EffectTestCopy", "DB$ CopyPermanent | Defined$ Self");
        addTrigger(producer, "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You"
                + " | Execute$ EffectTestCopy | TriggerZones$ Battlefield");
        final Card consequence = addTokenCounterConsequence("Runeclaw Bear", opponent);

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0);
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testTemporaryAndNoncreatureTokenOutcomesRemainUnsupported() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card temporaryConsequence = addCounterTokenConsequence(
                "Grizzly Bears", opponent, 1, "w_1_1_soldier", " | AtEOT$ Exile");
        final Card noncreatureConsequence = addCounterTokenConsequence(
                "Runeclaw Bear", opponent, 1, "c_a_treasure_sac");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, temporaryConsequence, noncreatureConsequence));

        Assert.assertTrue(values.isEmpty());
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
    public void testDrawProductionCreatesOneEventPerDrawnCard() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);
        stockLibrary(opponent, 6);

        final Card oneDraw = addPhaseDrawProducer("Grizzly Bears", opponent, 1, "You");
        final Card threeDraws = addPhaseDrawProducer("Runeclaw Bear", opponent, 3, "You");
        final Card consequence = addDrawCounterConsequence(
                "Bear Cub", opponent, "Card.YouCtrl", "");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(oneDraw, threeDraws, consequence));

        Assert.assertTrue(values.getOrDefault(oneDraw, 0) > 0, values.toString());
        Assert.assertEquals(values.get(threeDraws).intValue(), values.get(oneDraw) * 3);
        Assert.assertEquals(values.get(consequence).intValue(),
                values.get(oneDraw) + values.get(threeDraws));
    }

    @Test
    public void testDrawNumberMeansOrdinalDrawThisTurn() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);
        stockLibrary(opponent, 4);

        final Card oneDraw = addPhaseDrawProducer("Grizzly Bears", opponent, 1, "You");
        final Card twoDraws = addPhaseDrawProducer("Runeclaw Bear", opponent, 2, "You");
        final Card consequence = addDrawCounterConsequence(
                "Bear Cub", opponent, "Card.YouCtrl", " | Number$ 2");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(oneDraw, twoDraws, consequence));

        Assert.assertFalse(values.containsKey(oneDraw), values.toString());
        Assert.assertTrue(values.getOrDefault(twoDraws, 0) > 0, values.toString());
        Assert.assertEquals(values.get(twoDraws), values.get(consequence));
    }

    @Test
    public void testDrawOutsideDrawStepMatchesFirstCardExclusion() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);
        stockLibrary(opponent, 2);

        final Card producer = addPhaseDrawProducer("Grizzly Bears", opponent, 1, "You");
        final Card consequence = addDrawCounterConsequence(
                "Runeclaw Bear", opponent, "Card.YouCtrl",
                " | FirstCardInDrawStep$ False");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0, values.toString());
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testOpponentDrawMatchesUnknownCardOwnership() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);
        stockLibrary(ai, 2);

        final Card producer = addPhaseDrawProducer(
                "Grizzly Bears", opponent, 1, "Opponent");
        final Card consequence = addDrawCounterConsequence(
                "Runeclaw Bear", opponent, "Card.OppOwn", "");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0, values.toString());
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testOpponentDrawMatchesPlayerConstraint() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);
        stockLibrary(ai, 2);

        final Card producer = addPhaseDrawProducer(
                "Grizzly Bears", opponent, 1, "Opponent");
        final Card consequence = addCard("Runeclaw Bear", opponent);
        consequence.setSVar("EffectTestDrawOutcome",
                "DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1");
        addTrigger(consequence, "Mode$ Drawn | ValidPlayer$ Opponent"
                + " | Execute$ EffectTestDrawOutcome | TriggerZones$ Battlefield");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0, values.toString());
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testDrawnCardCharacteristicFiltersRemainUnsupported() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);
        stockLibrary(opponent, 2);

        final Card producer = addPhaseDrawProducer("Grizzly Bears", opponent, 1, "You");
        final Card consequence = addDrawCounterConsequence(
                "Runeclaw Bear", opponent, "Card.nonLand", "");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.isEmpty(), values.toString());
    }

    @Test
    public void testPayableActivatedDrawIsAProduction() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);
        stockLibrary(opponent, 2);

        final Card producer = addCard("Grizzly Bears", opponent);
        producer.addSpellAbility(AbilityFactory.getAbility(
                "AB$ Draw | Cost$ 0 | Defined$ You | NumCards$ 1", producer));
        final Card consequence = addDrawCounterConsequence(
                "Runeclaw Bear", opponent, "Card.YouCtrl", "");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertTrue(values.getOrDefault(producer, 0) > 0, values.toString());
        Assert.assertEquals(values.get(producer), values.get(consequence));
    }

    @Test
    public void testDrawOutcomeUsesNonlinearHandValue() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);
        stockLibrary(opponent, 4);
        addCardToZone("Forest", opponent, ZoneType.Hand);
        addCardToZone("Forest", opponent, ZoneType.Hand);
        addCardToZone("Forest", opponent, ZoneType.Hand);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card consequence = addCounterTriggeredOutcome("Grizzly Bears", opponent,
                "DB$ Draw | Defined$ You | NumCards$ 2");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertEquals(values.get(producer).intValue(), 104 + 92, values.toString());
        Assert.assertEquals(values.get(consequence), values.get(producer));
    }

    @Test
    public void testDrawOutcomeForEvaluatingAiHasNegativeThreatValue() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);
        stockLibrary(ai, 2);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card consequence = addCounterTriggeredOutcome("Grizzly Bears", opponent,
                "DB$ Draw | Defined$ Opponent | NumCards$ 1");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertEquals(values.get(producer).intValue(), -140, values.toString());
        Assert.assertEquals(values.get(consequence), values.get(producer));
    }

    @Test
    public void testManaOutcomeUsesSharedManaValue() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card producer = addPhaseCounterProducer(
                "Sol Ring", opponent, "CHARGE", 1, "Self");
        final Card consequence = addCounterTriggeredOutcome("Grizzly Bears", opponent,
                "DB$ Mana | Defined$ You | Produced$ G | Amount$ 3");

        final Map<Card, Integer> values = EffectRelationshipEvaluator.evaluateRemovalRelationships(
                ai, List.of(producer, consequence));

        Assert.assertEquals(values.get(producer).intValue(), 105, values.toString());
        Assert.assertEquals(values.get(consequence), values.get(producer));
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
        return addPhaseTokenProducer(cardName, controller, amount, "You");
    }

    private Card addPhaseTokenProducer(final String cardName, final Player controller,
            final int amount, final String owner) {
        final Card card = addCard(cardName, controller);
        card.setSVar("EffectTestToken", "DB$ Token | TokenScript$ w_1_1_soldier"
                + " | TokenOwner$ " + owner + " | TokenAmount$ " + amount);
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

    private Card addPhaseLifeGainProducer(final String cardName, final Player controller,
            final int amount, final String defined) {
        final Card card = addCard(cardName, controller);
        card.setSVar("EffectTestLifeGain", "DB$ GainLife | Defined$ " + defined
                + " | LifeAmount$ " + amount);
        addTrigger(card, "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You"
                + " | Execute$ EffectTestLifeGain | TriggerZones$ Battlefield");
        return card;
    }

    private Card addPhaseDrawProducer(final String cardName, final Player controller,
            final int amount, final String defined) {
        final Card card = addCard(cardName, controller);
        card.setSVar("EffectTestDraw", "DB$ Draw | Defined$ " + defined
                + " | NumCards$ " + amount);
        addTrigger(card, "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You"
                + " | Execute$ EffectTestDraw | TriggerZones$ Battlefield");
        return card;
    }

    private Card addPhaseDamageProducer(final String cardName, final Player controller,
            final String api, final String recipientDefinition, final int amount) {
        final Card card = addCard(cardName, controller);
        card.setSVar("EffectTestDamage", "DB$ " + api + " | " + recipientDefinition
                + " | NumDmg$ " + amount);
        addTrigger(card, "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You"
                + " | Execute$ EffectTestDamage | TriggerZones$ Battlefield");
        return card;
    }

    private Card addDamageCounterConsequence(final String cardName, final Player controller,
            final String triggerMode, final String triggerRestrictions,
            final String counterAmount) {
        final Card card = addCard(cardName, controller);
        card.setSVar("EffectTestDamageOutcome",
                "DB$ PutCounter | Defined$ Self | CounterType$ P1P1"
                        + " | CounterNum$ " + counterAmount);
        addTrigger(card, "Mode$ " + triggerMode + " | " + triggerRestrictions
                + " | Execute$ EffectTestDamageOutcome | TriggerZones$ Battlefield");
        return card;
    }

    private Card addDrawCounterConsequence(final String cardName, final Player controller,
            final String validCard, final String extraTriggerParams) {
        final Card card = addCard(cardName, controller);
        card.setSVar("EffectTestDrawOutcome",
                "DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1");
        addTrigger(card, "Mode$ Drawn | ValidCard$ " + validCard
                + extraTriggerParams + " | Execute$ EffectTestDrawOutcome"
                + " | TriggerZones$ Battlefield");
        return card;
    }

    private void stockLibrary(final Player player, final int amount) {
        for (int i = 0; i < amount; i++) {
            addCardToZone("Forest", player, ZoneType.Library);
        }
    }

    private Card addZoneEntryCounterConsequence(final String cardName, final Player controller,
            final String validCard) {
        final Card card = addCard(cardName, controller);
        card.setSVar("EffectTestZoneOutcome",
                "DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1");
        addTrigger(card, "Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield"
                + " | ValidCard$ " + validCard
                + " | Execute$ EffectTestZoneOutcome | TriggerZones$ Battlefield");
        return card;
    }

    private Card addZoneEntryBatchCounterConsequence(final String cardName,
            final Player controller, final String validCards, final String counterAmount) {
        final Card card = addCard(cardName, controller);
        card.setSVar("EffectTestZoneOutcome",
                "DB$ PutCounter | Defined$ Self | CounterType$ P1P1"
                        + " | CounterNum$ " + counterAmount);
        addTrigger(card, "Mode$ ChangesZoneAll | Origin$ Any | Destination$ Battlefield"
                + " | ValidCards$ " + validCards
                + " | Execute$ EffectTestZoneOutcome | TriggerZones$ Battlefield");
        return card;
    }

    private Card addDiesCounterConsequence(final String cardName, final Player controller,
            final String validCard, final String counterAmount) {
        final Card card = addCard(cardName, controller);
        card.setSVar("EffectTestDiesOutcome",
                "DB$ PutCounter | Defined$ Self | CounterType$ P1P1"
                        + " | CounterNum$ " + counterAmount);
        addTrigger(card, "Mode$ ChangesZone | Origin$ Battlefield | Destination$ Graveyard"
                + " | ValidCard$ " + validCard + " | Execute$ EffectTestDiesOutcome"
                + " | TriggerZones$ Battlefield");
        return card;
    }

    private Card addSacrificeCounterConsequence(final String cardName,
            final Player controller, final String triggerMode, final String validCard,
            final String counterAmount) {
        final Card card = addCard(cardName, controller);
        card.setSVar("EffectTestSacrificeOutcome",
                "DB$ PutCounter | Defined$ Self | CounterType$ P1P1"
                        + " | CounterNum$ " + counterAmount);
        addTrigger(card, "Mode$ " + triggerMode + " | ValidPlayer$ You"
                + " | ValidCard$ " + validCard + " | Execute$ EffectTestSacrificeOutcome"
                + " | TriggerZones$ Battlefield");
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

    private Card addCounterOutcomeConsequence(final String cardName, final Player controller,
            final String outcomeCounterType, final String validTargets) {
        final Card card = addCard(cardName, controller);
        card.setSVar("EffectTestCounterOutcome", "DB$ PutCounter | ValidTgts$ " + validTargets
                + " | CounterType$ " + outcomeCounterType + " | CounterNum$ 1");
        addTrigger(card, "Mode$ CounterAddedOnce | ValidCard$ Artifact.YouCtrl"
                + " | CounterType$ CHARGE | Execute$ EffectTestCounterOutcome"
                + " | TriggerZones$ Battlefield");
        return card;
    }

    private Card addCounterTriggeredOutcome(final String cardName, final Player controller,
            final String outcomeDefinition) {
        final Card card = addCard(cardName, controller);
        card.setSVar("EffectTestOutcome", outcomeDefinition);
        addTrigger(card, "Mode$ CounterAddedOnce | ValidCard$ Artifact.YouCtrl"
                + " | CounterType$ CHARGE | Execute$ EffectTestOutcome"
                + " | TriggerZones$ Battlefield");
        return card;
    }

    private Card addCounterTokenConsequence(final String cardName, final Player controller,
            final int amount, final String tokenScript) {
        return addCounterTokenConsequence(
                cardName, controller, Integer.toString(amount), tokenScript, "");
    }

    private Card addCounterTokenConsequence(final String cardName, final Player controller,
            final int amount, final String tokenScript, final String extraParams) {
        return addCounterTokenConsequence(
                cardName, controller, Integer.toString(amount), tokenScript, extraParams);
    }

    private Card addCounterTokenConsequence(final String cardName, final Player controller,
            final String amount, final String tokenScript) {
        return addCounterTokenConsequence(cardName, controller, amount, tokenScript, "");
    }

    private Card addCounterTokenConsequence(final String cardName, final Player controller,
            final String amount, final String tokenScript, final String extraParams) {
        return addCounterTokenConsequence(
                cardName, controller, amount, tokenScript, "You", extraParams);
    }

    private Card addCounterTokenConsequence(final String cardName, final Player controller,
            final int amount, final String tokenScript, final String owner,
            final String extraParams) {
        return addCounterTokenConsequence(cardName, controller,
                Integer.toString(amount), tokenScript, owner, extraParams);
    }

    private Card addCounterTokenConsequence(final String cardName, final Player controller,
            final String amount, final String tokenScript, final String owner,
            final String extraParams) {
        final Card card = addCard(cardName, controller);
        card.setSVar("EffectTestTokenOutcome", "DB$ Token | TokenOwner$ " + owner
                + " | TokenAmount$ " + amount + " | TokenScript$ " + tokenScript + extraParams);
        addTrigger(card, "Mode$ CounterAddedOnce | ValidCard$ Artifact.YouCtrl"
                + " | CounterType$ CHARGE | Execute$ EffectTestTokenOutcome"
                + " | TriggerZones$ Battlefield");
        return card;
    }

    private Card addCounterCopyConsequence(final String cardName, final Player controller,
            final int amount) {
        final Card card = addCard(cardName, controller);
        card.setSVar("EffectTestCopyOutcome", "DB$ CopyPermanent | Defined$ TriggeredCard"
                + " | NumCopies$ " + amount);
        addTrigger(card, "Mode$ CounterAddedOnce | ValidCard$ Artifact.YouCtrl"
                + " | CounterType$ CHARGE | Execute$ EffectTestCopyOutcome"
                + " | TriggerZones$ Battlefield");
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
