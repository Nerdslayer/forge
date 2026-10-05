package forge.ai.effect;

import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.combat.PreparedCombatValuation;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

/** Signed loss, identity, snapshot and information-boundary regressions for combat preparation. */
public class CombatValuationFoundationTest extends AITest {
    @Test
    public void everyPreparationCheckpointCancelsWithoutPublishingAPartialLedgerOrChangingGameState() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        final Card source = addDefinition(opponent, "Bounded Producer", "2", List.of(
                "T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | Execute$ Produce | TriggerZones$ Battlefield",
                "SVar:Produce:DB$ GainLife | Defined$ You | LifeAmount$ 2"));
        final Card consumer = addDefinition(opponent, "Bounded Consumer", "2", List.of(
                "T:Mode$ LifeGained | ValidPlayer$ You | Execute$ Benefit | TriggerZones$ Battlefield",
                "SVar:Benefit:DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1"));
        final var context = ValuationContext.forCombat(ai, ValuationDecision.BLOCK, 100, 0);
        final int sentinel = game.nextCardId();
        final int[] calls = {0};
        final var complete = CombatValuationEvaluator.prepare(context, () -> { calls[0]++; return true; }).orElseThrow();
        Assert.assertFalse(complete.relationships().isEmpty(), "Exercise relationship matching as well as card preparation");
        Assert.assertEquals(complete, CombatValuationEvaluator.prepare(context));
        for (int limit = 0; limit < calls[0]; limit++) {
            final int allowance = limit;
            final int[] attempted = {0};
            Assert.assertTrue(CombatValuationEvaluator.prepare(context, () -> ++attempted[0] <= allowance).isEmpty(),
                    "No partial ledger may escape at checkpoint " + allowance);
            Assert.assertEquals(attempted[0], allowance + 1, "Stop immediately at the first failed checkpoint");
        }
        Assert.assertEquals(game.nextCardId(), sentinel + 1);
        Assert.assertEquals(consumer.getCounters(forge.game.card.CounterEnumType.P1P1), 0);
        Assert.assertEquals(opponent.getLife(), 20);
        Assert.assertTrue(source.isUntapped() && consumer.isUntapped() && game.getStack().isEmpty());
    }

    @Test
    public void bodyLossesAreSymmetricAndSnapshotDoesNotFollowLiveMutation() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        final Card friendly = addDefinition(ai, "Friendly", "2", List.of());
        final Card hostile = addDefinition(opponent, "Hostile", "2", List.of());
        final PreparedCombatValuation snapshot = CombatValuationEvaluator.prepare(
                ValuationContext.forCombat(ai, ValuationDecision.BLOCK, 0, 0));
        final int body = UnifiedPermanentValueEvaluator.evaluate(ai, friendly);
        Assert.assertEquals(snapshot.evaluateLosses(List.of(friendly.getId())).body(), -body);
        Assert.assertEquals(snapshot.evaluateLosses(List.of(hostile.getId())).body(), body);
        Assert.assertEquals(snapshot.evaluateLosses(List.of(friendly.getId(), hostile.getId())).total(), 0);
        Assert.assertEquals(snapshot.evaluateLosses(List.of(friendly.getId(), friendly.getId())).body(), -body);
        friendly.setTapped(true);
        Assert.assertEquals(snapshot.evaluateLosses(List.of(friendly.getId())).body(), -body);
        Assert.assertEquals(snapshot.completeness(), ValuationCompleteness.PARTIAL);
        Assert.expectThrows(UnsupportedOperationException.class, () -> snapshot.permanents().clear());
        Assert.expectThrows(IllegalArgumentException.class, () -> snapshot.evaluateLosses(List.of(-999)));
    }

    @Test
    public void futurePreparationHasNoImmediateUseAndDoesNotDependOnOpposingHandIdentity() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        final List<String> abilities = List.of("A:AB$ Draw | Cost$ T | Defined$ You | NumCards$ 1");
        final Card friendly = addDefinition(ai, "Friendly Engine", "2 U", abilities);
        final Card hostile = addDefinition(opponent, "Hostile Engine", "2 U", abilities);
        final Card hidden = addCardToZone("Grizzly Bears", opponent, ZoneType.Hand);
        final ValuationContext context = ValuationContext.forCombat(ai, ValuationDecision.ATTACK, 0, 50);
        final PreparedCombatValuation before = CombatValuationEvaluator.prepare(context);
        Assert.assertTrue(before.permanents().get(friendly.getId()).futureAbilityLossValue() < 0);
        Assert.assertEquals(before.permanents().get(friendly.getId()).futureAbilityLossValue(),
                -before.permanents().get(hostile.getId()).futureAbilityLossValue());
        friendly.setSickness(false);
        hostile.setSickness(false);
        hidden.getOwner().getZone(ZoneType.Hand).remove(hidden);
        addCardToZone("Giant Growth", opponent, ZoneType.Hand);
        final PreparedCombatValuation after = CombatValuationEvaluator.prepare(context);
        Assert.assertEquals(after.permanents().get(friendly.getId()).futureAbilityLossValue(),
                before.permanents().get(friendly.getId()).futureAbilityLossValue());
        Assert.assertEquals(after.permanents().get(hostile.getId()).futureAbilityLossValue(),
                before.permanents().get(hostile.getId()).futureAbilityLossValue());
        Assert.assertFalse(after.permanents().get(friendly.getId()).reasons().stream()
                .anyMatch(reason -> reason.contains("One legal immediate activation")));
        Assert.assertTrue(friendly.isUntapped());
        Assert.assertTrue(hostile.isUntapped());
        Assert.assertTrue(game.getStack().isEmpty());
    }

    @Test
    public void multipleUnknownAbilitiesReceiveOneManaScaledAllowanceSymmetrically() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        // Tribal entry/death triggers are supported now; Metalcraft is a real engine condition
        // whose intrinsic occurrence is not yet modeled. Keep these fixtures genuinely unknown.
        final List<String> unknowns = List.of(
                "T:Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield | ValidCard$ Human.Other+YouCtrl"
                        + " | TriggerZones$ Battlefield | Metalcraft$ True | Execute$ AddCounter",
                "T:Mode$ ChangesZone | Origin$ Any | Destination$ Graveyard | ValidCard$ Human.Other+YouCtrl"
                        + " | TriggerZones$ Battlefield | Metalcraft$ True | Execute$ AddCounter",
                "SVar:AddCounter:DB$ PutCounter | CounterType$ P1P1 | CounterNum$ 1");
        for (final int mana : List.of(0, 1, 4, 6)) {
            final Card friendly = addDefinition(ai, "Unknown Friendly " + mana, Integer.toString(mana), unknowns);
            final Card hostile = addDefinition(opponent, "Unknown Hostile " + mana, Integer.toString(mana), unknowns);
            final var values = CombatValuationEvaluator.prepare(ValuationContext.forCombat(ai, ValuationDecision.BLOCK, 0, 100));
            final int expected = Math.max(10, mana * 5);
            Assert.assertEquals(values.permanents().get(friendly.getId()).unknownAbilityLossValue(), -expected);
            Assert.assertEquals(values.permanents().get(hostile.getId()).unknownAbilityLossValue(), expected);
            Assert.assertEquals(values.evaluateLosses(List.of(friendly.getId(), hostile.getId())).unknownAbility(), 0);
        }
    }

    @Test
    public void zeroManaUnknownAbilityGetsCombatMinimumWithoutChangingRemoval() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        final Card engine = addDefinition(ai, "Unknown Engine", "0", List.of(
                "T:Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield"
                        + " | ValidCard$ Human.Other+YouCtrl | TriggerZones$ Battlefield | Metalcraft$ True | Execute$ AddCounter",
                "SVar:AddCounter:DB$ PutCounter | CounterType$ P1P1 | CounterNum$ 1"));
        final PreparedCombatValuation snapshot = CombatValuationEvaluator.prepare(
                ValuationContext.forCombat(ai, ValuationDecision.BLOCK, 0, 100));
        Assert.assertEquals(snapshot.permanents().get(engine.getId()).unknownAbilityLossValue(), -10);
        Assert.assertEquals(UnifiedCardValueEvaluator.evaluatePermanent(engine,
                ValuationContext.forRemoval(ai, 0, 100)).currentPresenceValue(),
                UnifiedPermanentValueEvaluator.evaluate(ai, engine));
        engine.setState(CardStateName.FaceDown, false);
        final PreparedCombatValuation hidden = CombatValuationEvaluator.prepare(
                ValuationContext.forCombat(ai, ValuationDecision.BLOCK, 0, 100));
        Assert.assertEquals(hidden.permanents().get(engine.getId()).futureAbilityLossValue(), 0);
        Assert.assertEquals(hidden.permanents().get(engine.getId()).unknownAbilityLossValue(), 0);
    }

    @Test
    public void relationshipLedgerDeduplicatesEndpointsButKeepsSourcesAndBatchesDistinct() {
        final PreparedCombatValuation.PermanentValue first = permanent(1);
        final PreparedCombatValuation.PermanentValue second = permanent(2);
        final PreparedCombatValuation.PermanentValue consumer = permanent(3);
        final PreparedCombatValuation.RelationshipValue edge = edge(1, 3, "batch:0", 20);
        final PreparedCombatValuation snapshot = new PreparedCombatValuation(
                Map.of(1, first, 2, second, 3, consumer), List.of(edge, edge,
                        edge(2, 3, "batch:0", 20), edge(1, 3, "batch:1", 7)),
                ValuationCompleteness.PARTIAL, List.of());
        Assert.assertEquals(snapshot.evaluateLosses(List.of(1)).relationships(), 27);
        Assert.assertEquals(snapshot.evaluateLosses(List.of(1, 3)).relationships(), 47);
        Assert.assertEquals(snapshot.evaluateLosses(List.of(1, 2, 3)).relationships(), 47);
        final PreparedCombatValuation conflicting = new PreparedCombatValuation(Map.of(1, first, 3, consumer),
                List.of(edge, edge(1, 3, "batch:0", 21)), ValuationCompleteness.PARTIAL, List.of());
        Assert.expectThrows(IllegalArgumentException.class, () -> conflicting.evaluateLosses(List.of(1)));
    }

    @Test
    public void combatContextRejectsOtherDecisionsAndWeightsRoundSymmetrically() {
        Assert.expectThrows(IllegalArgumentException.class,
                () -> ValuationContext.forCombat(null, ValuationDecision.CAST, 0, 0));
        Assert.expectThrows(IllegalArgumentException.class,
                () -> CombatValuationEvaluator.prepare(ValuationContext.intrinsicCard()));
        Assert.assertEquals(EffectMath.scalePercent(27, 50), 14);
        Assert.assertEquals(EffectMath.scalePercent(-27, 50), -14);
    }

    @DataProvider(name = "scheduledProducers")
    public Object[][] scheduledProducers() {
        return new Object[][] {
            {"DB$ GainLife | Defined$ You | LifeAmount$ 2", "Mode$ LifeGained | ValidPlayer$ You"},
            {"DB$ LoseLife | Defined$ You | LifeAmount$ 2", "Mode$ LifeLostAll | ValidPlayer$ You"},
            {"DB$ Draw | Defined$ You | NumCards$ 1", "Mode$ Drawn | ValidPlayer$ You"},
            {"DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 2",
                    "Mode$ CounterAddedOnce | ValidCard$ Creature.YouCtrl | CounterType$ P1P1"}
        };
    }

    @Test(dataProvider = "scheduledProducers")
    public void scheduledRelationshipsAreSymmetricAndLostEndpointsCountOnce(
            final String production, final String consequenceTrigger) {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        final List<String> producerScript = List.of(
                "T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | Execute$ Produce | TriggerZones$ Battlefield",
                "SVar:Produce:" + production);
        final List<String> consumerScript = List.of(
                "T:" + consequenceTrigger
                        + " | Execute$ Benefit | TriggerZones$ Battlefield",
                "SVar:Benefit:DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1");
        final Card friendlyProducer = addDefinition(ai, "Friendly Producer", "2", producerScript);
        final Card hostileProducer = addDefinition(opponent, "Hostile Producer", "2", producerScript);
        final Card friendlyConsumer = addDefinition(ai, "Friendly Consumer", "2", consumerScript);
        final Card hostileConsumer = addDefinition(opponent, "Hostile Consumer", "2", consumerScript);
        addCardToZone("Grizzly Bears", ai, ZoneType.Library);
        addCardToZone("Grizzly Bears", opponent, ZoneType.Library);
        final int sentinel = game.nextCardId();
        final PreparedCombatValuation snapshot = CombatValuationEvaluator.prepare(
                ValuationContext.forCombat(ai, ValuationDecision.BLOCK, 100, 0));
        Assert.assertEquals(snapshot.relationships().size(), 2);
        final int friendly = snapshot.evaluateLosses(List.of(friendlyProducer.getId())).relationships();
        final int hostile = snapshot.evaluateLosses(List.of(hostileProducer.getId())).relationships();
        Assert.assertTrue(friendly < 0);
        Assert.assertEquals(friendly, -hostile);
        Assert.assertEquals(snapshot.evaluateLosses(List.of(friendlyConsumer.getId())).relationships(), friendly);
        Assert.assertEquals(snapshot.evaluateLosses(List.of(friendlyProducer.getId(), friendlyConsumer.getId()))
                .relationships(), friendly);
        Assert.assertEquals(snapshot.evaluateLosses(List.of(hostileProducer.getId(), hostileConsumer.getId()))
                .relationships(), hostile);
        Assert.assertEquals(friendlyConsumer.getCounters(forge.game.card.CounterEnumType.P1P1), 0);
        Assert.assertEquals(game.getCardsIn(ZoneType.Battlefield).size(), 4);
        Assert.assertTrue(game.getStack().isEmpty());
        Assert.assertEquals(game.nextCardId(), sentinel + 1, "Preparation must not allocate live card IDs");
        Assert.assertTrue(CombatValuationEvaluator.prepare(ValuationContext.forCombat(ai,
                ValuationDecision.ATTACK, 0, 0)).relationships().isEmpty());
    }

    @Test
    public void crossControllerDrawAndHarmfulLifeRelationshipsUseRecipientPerspective() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        final Card producer = addDefinition(ai, "Public Life Producer", "2", List.of(
                "T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | Execute$ Gain | TriggerZones$ Battlefield",
                "SVar:Gain:DB$ GainLife | Defined$ You | LifeAmount$ 2"));
        final Card beneficiary = addDefinition(opponent, "Opposing Draw Consumer", "2", List.of(
                "T:Mode$ LifeGained | ValidPlayer$ Opponent | Execute$ Draw | TriggerZones$ Battlefield",
                "SVar:Draw:DB$ Draw | Defined$ You | NumCards$ 1"));
        final Card drawback = addDefinition(opponent, "Opposing Drawback", "2", List.of(
                "T:Mode$ LifeGained | ValidPlayer$ Opponent | Execute$ Loss | TriggerZones$ Battlefield",
                "SVar:Loss:DB$ LoseLife | Defined$ You | LifeAmount$ 1"));
        addCardToZone("Grizzly Bears", opponent, ZoneType.Library);
        final Card hidden = addCardToZone("Grizzly Bears", opponent, ZoneType.Hand);
        final ValuationContext context = ValuationContext.forCombat(ai, ValuationDecision.ATTACK, 100, 0);
        final PreparedCombatValuation before = CombatValuationEvaluator.prepare(context);
        final int benefit = before.evaluateLosses(List.of(beneficiary.getId())).relationships();
        final int harm = before.evaluateLosses(List.of(drawback.getId())).relationships();
        Assert.assertTrue(benefit > 0);
        Assert.assertTrue(harm < 0);
        Assert.assertEquals(before.evaluateLosses(List.of(producer.getId())).relationships(), benefit + harm);
        opponent.getZone(ZoneType.Hand).remove(hidden);
        addCardToZone("Giant Growth", opponent, ZoneType.Hand);
        final PreparedCombatValuation after = CombatValuationEvaluator.prepare(context);
        Assert.assertEquals(after.relationships(), before.relationships());
        Assert.assertEquals(opponent.getCardsIn(ZoneType.Hand).size(), 1);
        Assert.assertEquals(opponent.getCardsIn(ZoneType.Library).size(), 1);
        Assert.assertEquals(ai.getLife(), 20);
        Assert.assertEquals(opponent.getLife(), 20);
    }

    @Test
    public void preparationRejectsCombatActivationDynamicAndTargetedRelationships() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        final Card source = addDefinition(opponent, "Public Event Producer", "2", List.of(
                "T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | Execute$ Produce | TriggerZones$ Battlefield",
                "SVar:Produce:DB$ GainLife | Defined$ You | LifeAmount$ 2",
                "A:AB$ Token | Cost$ 0 | TokenScript$ w_1_1_soldier | TokenOwner$ You",
                "T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | Execute$ Tokens | TriggerZones$ Battlefield",
                "SVar:Tokens:DB$ Token | TokenScript$ w_1_1_soldier | TokenOwner$ You"));
        final Card consumer = addDefinition(opponent, "Targeted Consumer", "2", List.of(
                "T:Mode$ LifeGained | ValidPlayer$ You"
                        + " | Execute$ Benefit | TriggerZones$ Battlefield",
                "SVar:Benefit:DB$ PutCounter | ValidTgts$ Creature.YouCtrl | CounterType$ P1P1"));
        final int sentinel = game.nextCardId();
        final PreparedCombatValuation snapshot = CombatValuationEvaluator.prepare(
                ValuationContext.forCombat(ai, ValuationDecision.ATTACK, 100, 0));
        Assert.assertTrue(snapshot.relationships().isEmpty());
        Assert.assertTrue(snapshot.reasons().stream().anyMatch(reason -> reason.contains("Unprepared LIFE_GAINED")));
        Assert.assertEquals(snapshot.completeness(), ValuationCompleteness.PARTIAL);
        Assert.assertTrue(source.getSpellAbilities().stream().allMatch(ability -> ability.getTargets().isEmpty()));
        Assert.assertEquals(consumer.getCounters(forge.game.card.CounterEnumType.P1P1), 0);
        Assert.assertEquals(game.nextCardId(), sentinel + 1);
        final forge.game.spellability.SpellAbility dynamic = forge.game.ability.AbilityFactory.getAbility(
                "DB$ Draw | Defined$ You | NumCards$ X", source);
        Assert.assertFalse(TriggeredRelationshipEvaluator.isFixedPublicOutcome(dynamic));
        final forge.game.spellability.SpellAbility modal = forge.game.ability.AbilityFactory.getAbility(
                "DB$ Charm | Choices$ Produce | CharmNum$ 1", source);
        Assert.assertFalse(TriggeredRelationshipEvaluator.isFixedPublicOutcome(modal));
    }

    private static PreparedCombatValuation.PermanentValue permanent(final int id) {
        return new PreparedCombatValuation.PermanentValue(id, 1, 0, 0, 0, List.of());
    }

    private static PreparedCombatValuation.RelationshipValue edge(final int source, final int consumer,
            final String batch, final int value) {
        return new PreparedCombatValuation.RelationshipValue(new PreparedCombatValuation.RelationshipKey(
                source, "Original/ability:0", consumer, "Original/trigger:0", "TOKEN_CREATED", batch), value);
    }

    private static Card addDefinition(final Player owner, final String name, final String mana,
            final List<String> abilities) {
        final List<String> script = new java.util.ArrayList<>(List.of(
                "Name:" + name, "ManaCost:" + mana, "Types:Creature Human", "PT:2/2"));
        script.addAll(abilities);
        final Card card = Card.fromPaperCard(new PaperCard(CardRules.fromScript(script),
                CardEdition.UNKNOWN_CODE, CardRarity.Special), owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());
        owner.getZone(ZoneType.Battlefield).add(card);
        return card;
    }
}
