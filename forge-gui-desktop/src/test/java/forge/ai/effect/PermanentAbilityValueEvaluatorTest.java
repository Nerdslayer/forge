package forge.ai.effect;

import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.trigger.TriggerType;

/** Semantic regressions for combined relationship and intrinsic removal value. */
public class PermanentAbilityValueEvaluatorTest extends AITest {
    private static void setOpposingTeams(final Player ai, final Player opponent) {
        ai.setTeam(0);
        opponent.setTeam(1);
    }

    @Test
    public void scheduledDrawKeepsDirectValueWhenItAlsoHasADrawPayoff() {
        final Game standaloneGame = initAndCreateGame();
        final Player standaloneAi = standaloneGame.getPlayers().get(1);
        final Player standaloneOpponent = standaloneGame.getPlayers().get(0);
        setOpposingTeams(standaloneAi, standaloneOpponent);
        final Card standalone = addCard("Staff of Nin", standaloneOpponent);
        fillLibrary(standaloneOpponent, 5);
        final PermanentAbilityValueEvaluator.Breakdown standaloneValue = evaluate(
                standaloneAi, List.of(standalone)).get(standalone);

        final Game comboGame = initAndCreateGame();
        final Player comboAi = comboGame.getPlayers().get(1);
        final Player comboOpponent = comboGame.getPlayers().get(0);
        setOpposingTeams(comboAi, comboOpponent);
        final Card drawEngine = addCard("Staff of Nin", comboOpponent);
        fillLibrary(comboOpponent, 5);
        final Card payoff = addDrawPayoff(comboOpponent);
        final PermanentAbilityValueEvaluator.Breakdown comboValue = evaluate(
                comboAi, List.of(drawEngine, payoff)).get(drawEngine);

        Assert.assertTrue(standaloneValue.intrinsicValue() > 0, standaloneValue.toString());
        Assert.assertEquals(comboValue.intrinsicValue(), standaloneValue.intrinsicValue(),
                "The engine's scheduled draw is its own intrinsic opportunity");
        Assert.assertTrue(comboValue.relationshipValue() > 0, comboValue.toString());
    }

    @Test
    public void completeIntrinsicTapSequencesContributeToRemovalValue() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        // Wylie has a printed Taps trigger whose outcome is the complete GainLife -> Draw
        // sequence. This verifies the live removal bridge uses the same sequence planner as
        // standalone intrinsic evaluation.
        final Card card = addCard("Wylie Duke, Atiin Hero", opponent);

        final PermanentAbilityValueEvaluator.Breakdown value = evaluate(ai, List.of(card)).get(card);
        Assert.assertTrue(value.intrinsicValue() > 0, value.toString());
        Assert.assertTrue(value.reasons().stream()
                .anyMatch(reason -> reason.contains("Independent future-support allowance")),
                value.toString());
    }

    @Test
    public void intrinsicManaTapTriggersContributeFutureRemovalValue() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card manaEngine = addCard("Groundchuck & Dirtbag", opponent);
        final PermanentAbilityValueEvaluator.Breakdown value = evaluate(ai, List.of(manaEngine))
                .get(manaEngine);

        Assert.assertTrue(value.intrinsicValue() > 0, value.toString());
        Assert.assertTrue(value.reasons().stream()
                .anyMatch(reason -> reason.contains("Independent future-support allowance")),
                value.toString());
    }

    @Test
    public void intrinsicActivatedAbilityContributesFutureRemovalValue() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card pinger = addCard("Prodigal Sorcerer", opponent);
        final PermanentAbilityValueEvaluator.Breakdown value = evaluate(ai, List.of(pinger))
                .get(pinger);

        Assert.assertTrue(value.intrinsicValue() > 0, value.toString());
        Assert.assertTrue(value.reasons().stream()
                .anyMatch(reason -> reason.contains("activated DealDamage ability")),
                value.toString());
    }

    @Test
    public void supportedEventTriggerContributesFutureRemovalValueWithoutCurrentProducer() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card deathEngine = addCard("Blood Artist", opponent);
        final PermanentAbilityValueEvaluator.Breakdown value = evaluate(ai, List.of(deathEngine))
                .get(deathEngine);

        Assert.assertTrue(value.intrinsicValue() > 0, value.toString());
        Assert.assertTrue(value.reasons().stream()
                .anyMatch(reason -> reason.contains("Independent future-support allowance")),
                value.toString());
    }

    @Test
    public void spellCastTriggerContributesFutureRemovalValueWithoutCurrentProducer() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);

        final Card spellEngine = addCard("Young Pyromancer", opponent);
        final PermanentAbilityValueEvaluator.Breakdown value = evaluate(ai, List.of(spellEngine))
                .get(spellEngine);

        Assert.assertTrue(value.intrinsicValue() > 0, value.toString());
        Assert.assertTrue(value.reasons().stream()
                .anyMatch(reason -> reason.contains("Independent future-support allowance")),
                value.toString());
    }

    @Test
    public void relationshipOnlyBreakdownPreservesLegacyRelationshipMap() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);
        final RelationshipFixture fixture = addCounterRelationship(opponent);
        final List<Card> candidates = List.of(fixture.producer(), fixture.consumer());

        final Map<Card, Integer> legacy = EffectRelationshipEvaluator
                .evaluateRemovalRelationships(ai, candidates);
        final Map<Card, PermanentAbilityValueEvaluator.Breakdown> combined = evaluate(
                ai, candidates, false);
        for (final Card candidate : candidates) {
            Assert.assertEquals(combined.get(candidate).relationshipValue(),
                    legacy.getOrDefault(candidate, 0).intValue(), candidate.getName());
            Assert.assertEquals(combined.get(candidate).intrinsicValue(), 0);
        }
    }

    @Test
    public void relationshipAttributionKeepsSourceAndConsumerCardsDistinct() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);
        final RelationshipFixture first = addCounterRelationship(opponent);
        final RelationshipFixture second = addCounterRelationship(opponent);

        final Map<Card, List<AbilityValueContribution>> contributions =
                EffectRelationshipEvaluator.evaluateRemovalContributions(ai,
                        List.of(first.producer(), second.producer(), first.consumer()),
                        EffectAnalysisTrace.disabled());
        final AbilityValueContribution firstEdge = knownEdge(contributions.get(first.producer()),
                first.consumer());
        final AbilityValueContribution secondEdge = knownEdge(contributions.get(second.producer()),
                first.consumer());

        Assert.assertNotNull(firstEdge, "first producer edge");
        Assert.assertNotNull(secondEdge, "second producer edge");
        Assert.assertTrue(firstEdge.sourceAbility().mapped());
        Assert.assertTrue(secondEdge.sourceAbility().mapped());
        Assert.assertEquals(firstEdge.sourceAbility().path(), secondEdge.sourceAbility().path(),
                "The two test abilities intentionally have the same local path");
        Assert.assertEquals(firstEdge.completeness(), AbilityValueCompleteness.SUPPORTED_SUBTOTAL);
        Assert.assertEquals(secondEdge.completeness(), AbilityValueCompleteness.SUPPORTED_SUBTOTAL);
        Assert.assertSame(firstEdge.source(), first.producer());
        Assert.assertSame(secondEdge.source(), second.producer());
        Assert.assertNotSame(firstEdge.source(), secondEdge.source());
        Assert.assertSame(firstEdge.relatedSource(), first.consumer());
        Assert.assertSame(secondEdge.relatedSource(), first.consumer());
    }

    @Test
    public void selfAttackRelationshipIsCountedOnce() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);
        final Card selfEngine = addCard("Library Larcenist", opponent);
        fillLibrary(opponent, 5);
        selfEngine.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opponent);
        game.getAction().checkStateEffects(true);

        final Map<Card, List<AbilityValueContribution>> contributions =
                EffectRelationshipEvaluator.evaluateRemovalContributions(ai, List.of(selfEngine),
                        EffectAnalysisTrace.disabled());
        final long selfEdges = contributions.getOrDefault(selfEngine, List.of()).stream()
                .filter(entry -> entry.kind() == AbilityValueKind.KNOWN_RELATIONSHIP)
                .filter(entry -> entry.source() == selfEngine && entry.relatedSource() == selfEngine)
                .count();

        Assert.assertEquals(selfEdges, 1L,
                "A self-producing/self-consuming attack opportunity must not be doubled");
        final Map<Card, PermanentAbilityValueEvaluator.Breakdown> withRelationshipCredit =
                PermanentAbilityValueEvaluator.evaluateRemovalAbilities(ai, List.of(selfEngine),
                        EffectAnalysisTrace.disabled(), true, true);
        final Map<Card, PermanentAbilityValueEvaluator.Breakdown> withoutRelationshipCredit =
                PermanentAbilityValueEvaluator.evaluateRemovalAbilities(ai, List.of(selfEngine),
                        EffectAnalysisTrace.disabled(), true, false);
        Assert.assertEquals(withRelationshipCredit.get(selfEngine).relationshipValue(),
                withoutRelationshipCredit.get(selfEngine).relationshipValue(),
                "Disabling intrinsic relationship credit must not change raw relationships");
        Assert.assertEquals(withRelationshipCredit.get(selfEngine).intrinsicValue(), 0,
                "Known self-attack relationship should suppress its intrinsic baseline");
        Assert.assertTrue(withoutRelationshipCredit.get(selfEngine).intrinsicValue() > 0,
                "Without relationship credit, the same attack trigger has an intrinsic baseline");
    }

    @Test
    public void dormantAnthemHasFixedFutureAllowanceIndependentOfCurrentRecipients() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);
        final Card anthem = addStaticAnthem(opponent, "Creature.YouCtrl+Soldier");
        game.getAction().checkStaticAbilities();

        final PermanentAbilityValueEvaluator.Breakdown dormant = evaluate(ai, List.of(anthem))
                .get(anthem);
        addCard("Eager Cadet", opponent);
        game.getAction().checkStaticAbilities();
        final PermanentAbilityValueEvaluator.Breakdown occupied = evaluate(ai, List.of(anthem))
                .get(anthem);

        Assert.assertTrue(dormant.intrinsicValue() > 25, dormant.toString());
        Assert.assertEquals(occupied.intrinsicValue(), dormant.intrinsicValue(),
                "Future allowance is fixed rather than a board-evolution projection");
        Assert.assertTrue(occupied.relationshipValue() > dormant.relationshipValue(),
                "Current recipient value remains separate from future allowance");
    }

    @Test
    public void staticFutureAllowanceRejectsDynamicHintedAndConditionalForms() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        setOpposingTeams(ai, opponent);
        final Card dynamic = addStaticAnthem(opponent, "Creature.YouCtrl", "X", null);
        final Card hinted = addStaticAnthem(opponent, "Creature.YouCtrl", "1", "100");
        final Card conditional = addStaticAnthem(opponent, "Creature.YouCtrl+powerGE4", "1", null);
        game.getAction().checkStaticAbilities();

        final Map<Card, PermanentAbilityValueEvaluator.Breakdown> values = evaluate(ai,
                List.of(dynamic, hinted, conditional));
        Assert.assertEquals(values.get(dynamic).intrinsicValue(), 0, values.get(dynamic).toString());
        Assert.assertTrue(values.get(hinted).intrinsicValue() > 0,
                "Literal AIEffectValue supplements the automatic static delta: "
                        + values.get(hinted));
        Assert.assertEquals(values.get(conditional).intrinsicValue(), 0,
                values.get(conditional).toString());
    }

    @Test
    public void intrinsicValueRejectsInactiveChangedAndFaceDownTriggers() {
        final Game suppressedGame = initAndCreateGame();
        final Player suppressedAi = suppressedGame.getPlayers().get(1);
        final Player suppressedOpponent = suppressedGame.getPlayers().get(0);
        setOpposingTeams(suppressedAi, suppressedOpponent);
        final Card suppressed = addCard("Staff of Nin", suppressedOpponent);
        drawTrigger(suppressed).setSuppressed(true);
        final PermanentAbilityValueEvaluator.Breakdown suppressedValue = evaluate(
                suppressedAi, List.of(suppressed)).get(suppressed);
        Assert.assertTrue(suppressedValue.intrinsicValue() > 0, suppressedValue.toString());
        Assert.assertFalse(suppressedValue.reasons().stream()
                .anyMatch(reason -> reason.contains("INTRINSIC_SCHEDULED")),
                suppressedValue.toString());

        final Game changedGame = initAndCreateGame();
        final Player changedAi = changedGame.getPlayers().get(1);
        final Player changedOpponent = changedGame.getPlayers().get(0);
        setOpposingTeams(changedAi, changedOpponent);
        final Card changed = addCard("Staff of Nin", changedOpponent);
        drawTrigger(changed).putParam("ValidPlayer", "Opponent");
        final PermanentAbilityValueEvaluator.Breakdown changedValue = evaluate(
                changedAi, List.of(changed)).get(changed);
        Assert.assertTrue(changedValue.intrinsicValue() > 0, changedValue.toString());
        Assert.assertFalse(changedValue.reasons().stream()
                .anyMatch(reason -> reason.contains("INTRINSIC_SCHEDULED")),
                changedValue.toString());

        final Game faceDownGame = initAndCreateGame();
        final Player faceDownAi = faceDownGame.getPlayers().get(1);
        final Player faceDownOpponent = faceDownGame.getPlayers().get(0);
        setOpposingTeams(faceDownAi, faceDownOpponent);
        final Card faceDown = addCard("Staff of Nin", faceDownOpponent);
        faceDown.setState(CardStateName.FaceDown, false);
        Assert.assertEquals(evaluate(faceDownAi, List.of(faceDown)).get(faceDown)
                .intrinsicValue(), 0);
    }

    private Map<Card, PermanentAbilityValueEvaluator.Breakdown> evaluate(final Player ai,
            final List<Card> candidates) {
        return evaluate(ai, candidates, true);
    }

    private Map<Card, PermanentAbilityValueEvaluator.Breakdown> evaluate(final Player ai,
            final List<Card> candidates, final boolean includeIntrinsic) {
        return PermanentAbilityValueEvaluator.evaluateRemovalAbilities(ai, candidates,
                EffectAnalysisTrace.disabled(), includeIntrinsic);
    }

    private Card addDrawPayoff(final Player controller) {
        final Card payoff = addCard("Grizzly Bears", controller);
        payoff.setSVar("EffectTestDrawPayoff",
                "DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1");
        addTrigger(payoff, "Mode$ Drawn | ValidCard$ Card.YouCtrl"
                + " | Execute$ EffectTestDrawPayoff | TriggerZones$ Battlefield");
        return payoff;
    }

    private RelationshipFixture addCounterRelationship(final Player controller) {
        final Card producer = addCard("Sol Ring", controller);
        producer.setSVar("EffectTestCounterProduction",
                "DB$ PutCounter | Defined$ Self | CounterType$ CHARGE | CounterNum$ 1");
        addTrigger(producer, "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You"
                + " | Execute$ EffectTestCounterProduction | TriggerZones$ Battlefield");

        final Card consumer = addCard("Grizzly Bears", controller);
        consumer.setSVar("EffectTestCounterOutcome",
                "DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1");
        addTrigger(consumer, "Mode$ CounterAddedOnce | ValidCard$ Artifact.YouCtrl"
                + " | CounterType$ CHARGE | Execute$ EffectTestCounterOutcome"
                + " | TriggerZones$ Battlefield");
        return new RelationshipFixture(producer, consumer);
    }

    private Card addStaticAnthem(final Player controller, final String affected) {
        return addStaticAnthem(controller, affected, "1", null);
    }

    private Card addStaticAnthem(final Player controller, final String affected,
            final String power, final String hint) {
        final Card source = addCard("Sol Ring", controller);
        String definition = "Mode$ Continuous | Affected$ " + affected
                + " | AddPower$ " + power + " | AddToughness$ 1";
        if (hint != null) {
            definition += " | AIEffectValue$ " + hint;
        }
        source.addStaticAbility(definition);
        return source;
    }

    private Trigger drawTrigger(final Card card) {
        return card.getTriggers().stream()
                .filter(trigger -> trigger.getMode() == TriggerType.Phase)
                .findFirst()
                .orElseThrow();
    }

    private void addTrigger(final Card card, final String definition) {
        card.addTrigger(TriggerHandler.parseTrigger(definition, card, false));
    }

    private static AbilityValueContribution knownEdge(
            final List<AbilityValueContribution> entries, final Card consumer) {
        if (entries == null) {
            return null;
        }
        return entries.stream()
                .filter(entry -> entry.kind() == AbilityValueKind.KNOWN_RELATIONSHIP)
                .filter(entry -> entry.relatedSource() == consumer)
                .findFirst()
                .orElse(null);
    }

    private record RelationshipFixture(Card producer, Card consumer) {
    }
}
