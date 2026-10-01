package forge.ai.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.effect.ValuationCompleteness;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

public class CombatShieldCounterTest extends AITest {
    private record Fixture(Game game, Player attacker, Player defender, Combat combat, Card source, List<Card> blockers) { }

    @DataProvider(name = "strikes")
    public Object[][] strikes() {
        return new Object[][] {{1, List.of(), 0, 0, 0}, {1, List.of("K:Double strike", "K:Lifelink"), 0, 2, 2},
                {2, List.of("K:Double strike", "K:Lifelink"), 0, 0, 0},
                {1, List.of("K:Deathtouch", "K:Lifelink"), 0, 0, 0}};
    }

    @Test(dataProvider = "strikes")
    public void shieldPreventionAndStrikeContinuationMatchEngine(final int count, final List<String> keywords,
            final int expectedShields, final int expectedDamage, final int expectedGain) {
        final var f = fixture(2, 4, keywords, List.of(List.of("PT:1/3")));
        final Card blocker = f.blockers().get(0);
        blocker.setCounters(CounterEnumType.SHIELD, count);
        final var before = PublicCombatSnapshot.capture(f.attacker(), f.combat());
        final var result = CombatOutcomePredictor.predict(before, assignment(f, before));
        Assert.assertTrue(result.available(), result.reasons().toString());
        Assert.assertEquals(result.survivors().get(blocker.getId()).shieldCounters(), expectedShields);
        Assert.assertEquals(result.survivors().get(blocker.getId()).markedDamage(), expectedDamage);
        Assert.assertFalse(result.survivors().get(blocker.getId()).markedDeathtouch());
        Assert.assertEquals(result.playerLifeAfter().get(f.attacker().getId()).intValue(), 20 + expectedGain);
        Assert.assertEquals(blocker.getCounters(CounterEnumType.SHIELD), count, "Prediction does not spend live counters");
        resolveAndCompare(f, result);
    }

    @Test
    public void simultaneousGangDamageConsumesOneCounterAndGeneratesNoDamageOrLifelink() {
        final var f = fixture(0, 6, List.of(), List.of(List.of("PT:2/2", "K:Lifelink"), List.of("PT:3/3", "K:Deathtouch")));
        f.source().setCounters(CounterEnumType.SHIELD, 2);
        final var before = PublicCombatSnapshot.capture(f.attacker(), f.combat());
        final var values = values(before);
        final var result = CombatDamageOptimizer.optimize(before, assignment(f, before), values, new CombatSearchBudget(1000))
                .best().orElseThrow().projection();
        Assert.assertEquals(result.survivors().get(f.source().getId()).shieldCounters(), 1);
        Assert.assertEquals(result.survivors().get(f.source().getId()).markedDamage(), 0);
        Assert.assertFalse(result.survivors().get(f.source().getId()).markedDeathtouch());
        Assert.assertEquals(result.playerLifeAfter().get(f.defender().getId()).intValue(), 20);
        Assert.assertEquals(result.eventBatches().stream().flatMap(batch -> batch.events().stream())
                .filter(CombatEventBatch.CountersRemoved.class::isInstance).count(), 1L);
        Assert.assertFalse(result.eventBatches().stream().flatMap(batch -> batch.events().stream())
                .anyMatch(event -> event instanceof CombatEventBatch.Damage));
        Assert.assertEquals(CombatTransitionValueEvaluator.evaluate(before, values, result).permanentLoss().body(), -20);
        Assert.assertEquals(values.afterCombat(before, result).permanents().get(f.source().getId()).bodyLossValue(), -120);
        resolveAndCompare(f, result);
    }

    @Test
    public void trampleStillAssignsLethalBeforeShieldPreventionAndOnlySpilloverGainsLife() {
        final var f = fixture(4, 4, List.of("K:Trample", "K:Lifelink"), List.of(List.of("PT:1/2")));
        f.blockers().get(0).setCounters(CounterEnumType.SHIELD, 1);
        final var before = PublicCombatSnapshot.capture(f.attacker(), f.combat());
        final var result = CombatOutcomePredictor.predict(before, assignment(f, before));
        Assert.assertTrue(result.available(), result.reasons().toString());
        Assert.assertEquals(result.playerLifeAfter().get(f.defender().getId()).intValue(), 18);
        Assert.assertEquals(result.playerLifeAfter().get(f.attacker().getId()).intValue(), 22);
        Assert.assertTrue(result.lostCreatures().isEmpty());
        Assert.assertEquals(result.survivors().get(f.blockers().get(0).getId()).markedDamage(), 0);
        resolveAndCompare(f, result);
    }

    @Test
    public void zeroDamageDoesNotConsumeAShieldAndIndestructibleDoesNotDoubleValueIt() {
        final var f = fixture(0, 4, List.of(), List.of(List.of("PT:0/4", "K:Indestructible")));
        f.blockers().get(0).setCounters(CounterEnumType.SHIELD, 2);
        final var before = PublicCombatSnapshot.capture(f.attacker(), f.combat());
        final var result = CombatOutcomePredictor.predict(before, assignment(f, before));
        Assert.assertEquals(result.survivors().get(f.blockers().get(0).getId()).shieldCounters(), 2);
        Assert.assertEquals(values(before).survivorCounterUtility(before, result), 0);
        Assert.assertEquals(forge.ai.CreatureEvaluator.shieldCounterValue(2, true), 0);
        resolveAndCompare(f, result);
    }

    @Test
    public void providerDeathBetweenStrikeStepsRestoresPreventionWithoutErasingEarlierDamage() {
        final var f = fixture(2, 4, List.of("K:Double strike", "K:Lifelink"), List.of(List.of("PT:1/5")));
        final Card provider = creature(f.attacker(), List.of("PT:0/1",
                "S:Mode$ CantPreventDamage | ValidSource$ Creature.YouCtrl | IsCombat$ True"));
        final Card killer = creature(f.defender(), List.of("PT:1/1", "K:First strike"));
        final Card blocker = f.blockers().get(0);
        blocker.setCounters(CounterEnumType.SHIELD, 2);
        f.combat().addAttacker(provider, f.defender());
        f.combat().addBlocker(provider, killer);
        f.combat().setBlocked(provider, true);
        final var before = PublicCombatSnapshot.capture(f.attacker(), f.combat());
        final var result = CombatOutcomePredictor.predict(before, new CombatAssignment(before.attackersToDefenders(),
                Map.of(f.source().getId(), List.of(blocker.getId()), provider.getId(), List.of(killer.getId()))));
        Assert.assertTrue(result.available(), result.reasons().toString());
        Assert.assertEquals(result.lostCreatures(), Set.of(provider.getId()));
        Assert.assertEquals(result.survivors().get(blocker.getId()).markedDamage(), 2);
        Assert.assertEquals(result.survivors().get(blocker.getId()).shieldCounters(), 0);
        Assert.assertEquals(result.playerLifeAfter().get(f.attacker().getId()).intValue(), 22);
        Assert.assertEquals(result.batches().get(0).creatureDamage().get(blocker.getId()).intValue(), 2);
        Assert.assertFalse(result.batches().get(1).creatureDamage().containsKey(blocker.getId()));
        Assert.assertEquals(CombatAttackCandidates.select(before, before.attackersToDefenders().keySet().stream().toList())
                .preventionRules(), before.preventionRules(), "Alternative declarations retain source policy");
        final var reply = CombatSafetyEvaluator.forecastNextAttack(before, result,
                new PublicCombatReadiness(f.defender().getId(), Set.of(killer.getId()), Map.of(killer.getId(), Set.of()), List.of()),
                values(before), new CombatSearchBudget(1000));
        Assert.assertTrue(reply.evaluation().supported());
        Assert.assertTrue(reply.continuation().orElseThrow().snapshot().preventionRules().isEmpty(),
                "A departed provider cannot grant unpreventable damage in the reply");
        resolveAndCompare(f, result);
        Assert.assertFalse(provider.isInPlay());
    }

    @DataProvider(name = "preventionPolicies")
    public Object[][] preventionPolicies() {
        return new Object[][] {{" | ValidSource$ Card.Self", true}, {" | ValidSource$ Creature.YouCtrl", true},
                {" | ValidSource$ Creature.OppCtrl", false}, {"", true},
                {" | ValidSource$ Card.Self | IsCombat$ False", false}};
    }

    @Test(dataProvider = "preventionPolicies")
    public void sourcePolicyKeepsUnpreventableDamageDeathtouchAndLifelinkButStillSpendsAShield(
            final String policy, final boolean unpreventable) {
        final var f = fixture(2, 4, List.of("K:Deathtouch", "K:Lifelink", "S:Mode$ CantPreventDamage" + policy),
                List.of(List.of("PT:1/3")));
        final Card blocker = f.blockers().get(0);
        blocker.setCounters(CounterEnumType.SHIELD, 2);
        final var before = PublicCombatSnapshot.capture(f.attacker(), f.combat());
        final var result = CombatOutcomePredictor.predict(before, assignment(f, before));
        Assert.assertTrue(result.available(), result.reasons().toString());
        Assert.assertEquals(result.lostCreatures().contains(blocker.getId()), unpreventable);
        Assert.assertEquals(result.playerLifeAfter().get(f.attacker().getId()).intValue(), unpreventable ? 22 : 20);
        Assert.assertEquals(result.eventBatches().stream().flatMap(batch -> batch.events().stream())
                .filter(CombatEventBatch.CountersRemoved.class::isInstance).count(), 1L);
        if (!unpreventable) { Assert.assertEquals(result.survivors().get(blocker.getId()).shieldCounters(), 1); }
        resolveAndCompare(f, result);
    }

    @Test
    public void commandZonePolicyWorksNowButRequiresCleanupModelBeforeFutureCombat() {
        final var f = fixture(2, 4, List.of("K:Deathtouch"), List.of(List.of("PT:1/3")));
        f.blockers().get(0).setCounters(CounterEnumType.SHIELD, 2);
        final Card effect = Card.fromPaperCard(new PaperCard(CardRules.fromScript(List.of(
                "Name:Public Prevention Policy", "Types:Effect",
                "S:Mode$ CantPreventDamage | EffectZone$ Command")), CardEdition.UNKNOWN_CODE, CardRarity.Special), f.attacker());
        effect.setGameTimestamp(f.game().getNextTimestamp());
        f.attacker().getZone(ZoneType.Command).add(effect);
        final var before = PublicCombatSnapshot.capture(f.attacker(), f.combat());
        final var result = CombatOutcomePredictor.predict(before, assignment(f, before));
        Assert.assertTrue(result.available(), result.reasons().toString());
        Assert.assertTrue(result.lostCreatures().contains(f.blockers().get(0).getId()));
        Assert.assertEquals(before.preventionRules().size(), 1);
        Assert.assertFalse(before.preventionRules().get(0).survivesCleanup());
        Assert.assertTrue(PublicCombatReadiness.capture(f.attacker(), before).unsupportedReasons().stream()
                .anyMatch(reason -> reason.contains("duration-aware cleanup")));
    }

    @DataProvider(name = "unsupportedPreventionPolicies")
    public Object[][] unsupportedPreventionPolicies() {
        return new Object[][] {{" | ValidSource$ Creature.powerGE4"}, {" | CheckSVar$ X | SVarCompare$ GE1"},
                {" | EffectZone$ Graveyard"}, {" | ValidSource$ Creature.Red"}};
    }

    @Test(dataProvider = "unsupportedPreventionPolicies")
    public void conditionalAndBroaderSourcePoliciesRemainExplicitFallbacks(final String policy) {
        final var f = fixture(2, 4, List.of("S:Mode$ CantPreventDamage" + policy), List.of(List.of("PT:1/3")));
        final var before = PublicCombatSnapshot.capture(f.attacker(), f.combat());
        Assert.assertTrue(before.preventionRules().isEmpty());
        Assert.assertTrue(before.unsupportedReasons().stream().anyMatch(reason -> reason.startsWith("Unprojected static effect")));
        Assert.assertFalse(CombatOutcomePredictor.predict(before, assignment(f, before)).supported());
    }

    @Test
    public void aSpentShieldIsNotChargedAgainWhenTheCreatureDiesInRegularDamage() {
        final var f = fixture(3, 4, List.of("K:Double strike"), List.of(List.of("PT:1/3")));
        final Card blocker = f.blockers().get(0);
        blocker.setCounters(CounterEnumType.SHIELD, 1);
        final var before = PublicCombatSnapshot.capture(f.attacker(), f.combat());
        final var result = CombatOutcomePredictor.predict(before, assignment(f, before));
        Assert.assertEquals(result.lostCreatures(), Set.of(blocker.getId()));
        Assert.assertEquals(CombatTransitionValueEvaluator.evaluate(before, values(before), result).permanentLoss().body(), 120,
                "The old full body loss includes the counter; do not also add a survivor counter loss");
        resolveAndCompare(f, result);
    }

    @Test
    public void indestructibleStillSpendsShieldsWithoutAShieldBodyBonus() {
        final var f = fixture(2, 4, List.of("K:Deathtouch"), List.of(List.of("PT:1/3", "K:Indestructible")));
        f.blockers().get(0).setCounters(CounterEnumType.SHIELD, 1);
        final var before = PublicCombatSnapshot.capture(f.attacker(), f.combat());
        final var result = CombatOutcomePredictor.predict(before, assignment(f, before));
        Assert.assertEquals(result.survivors().get(f.blockers().get(0).getId()).shieldCounters(), 0);
        Assert.assertEquals(values(before).survivorCounterUtility(before, result), 0);
        resolveAndCompare(f, result);
    }

    @Test
    public void spentShieldsFollowSurvivorsIntoThePublicReplyAndDoNotLoseValueTwice() {
        final var f = fixture(2, 4, List.of(), List.of(List.of("PT:1/3")));
        final Card blocker = f.blockers().get(0);
        blocker.setCounters(CounterEnumType.SHIELD, 1);
        final var before = PublicCombatSnapshot.capture(f.attacker(), f.combat());
        final var result = CombatOutcomePredictor.predict(before, assignment(f, before));
        final var forecast = CombatSafetyEvaluator.forecastNextAttack(before, result,
                new PublicCombatReadiness(f.defender().getId(), Set.of(blocker.getId()), Map.of(blocker.getId(), Set.of()), List.of()),
                values(before), new CombatSearchBudget(1000));
        Assert.assertTrue(forecast.evaluation().supported(), forecast.evaluation().reasons().toString());
        final var next = forecast.continuation().orElseThrow();
        Assert.assertEquals(next.snapshot().creatures().get(blocker.getId()).shieldCounters(), 0);
        Assert.assertEquals(next.values().permanents().get(blocker.getId()).bodyLossValue(), 100);
        Assert.assertEquals(next.values().survivorCounterUtility(next.snapshot(), next.projection()), 0);
    }

    private Fixture fixture(final int power, final int toughness, final List<String> keywords, final List<List<String>> blockScripts) {
        final Game game = initAndCreateGame();
        final Player attacker = game.getPlayers().get(0);
        final Player defender = game.getPlayers().get(1);
        attacker.setTeam(0);
        defender.setTeam(1);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, attacker);
        final List<String> sourceScript = new ArrayList<>(keywords);
        sourceScript.add("PT:" + power + "/" + toughness);
        final Card source = creature(attacker, sourceScript);
        final List<Card> blockers = blockScripts.stream().map(script -> creature(defender, script)).toList();
        final Combat combat = new Combat(attacker);
        game.getPhaseHandler().setCombat(combat);
        combat.addAttacker(source, defender);
        return new Fixture(game, attacker, defender, combat, source, blockers);
    }

    private static CombatAssignment assignment(final Fixture f, final PublicCombatSnapshot before) {
        return new CombatAssignment(before.attackersToDefenders(), Map.of(f.source().getId(), f.blockers().stream().map(Card::getId).toList()));
    }

    private static PreparedCombatValuation values(final PublicCombatSnapshot before) {
        final Map<Integer, PreparedCombatValuation.PermanentValue> values = new LinkedHashMap<>();
        before.creatures().forEach((id, card) -> values.put(id, new PreparedCombatValuation.PermanentValue(id, card.controllerId(),
                (card.controllerId() == before.observingPlayerId() ? -1 : 1)
                        * (100 + forge.ai.CreatureEvaluator.shieldCounterValue(card.shieldCounters(), card.indestructible())), 0, 0, List.of())));
        return new PreparedCombatValuation(values, List.of(), ValuationCompleteness.COMPLETE, List.of());
    }

    private static void resolveAndCompare(final Fixture f, final CombatProjection result) {
        f.blockers().forEach(blocker -> f.combat().addBlocker(f.source(), blocker));
        f.combat().setBlocked(f.source(), true);
        f.combat().orderBlockersForDamageAssignment();
        f.combat().orderAttackersForDamageAssignment();
        for (final boolean first : List.of(true, false)) {
            f.game().getPhaseHandler().devModeSet(first ? PhaseType.COMBAT_FIRST_STRIKE_DAMAGE : PhaseType.COMBAT_DAMAGE, f.attacker(), false);
            f.combat().removeAbsentCombatants();
            if (f.combat().assignCombatDamage(first)) { f.combat().dealAssignedDamage(); }
            f.game().getAction().checkStateEffects(true);
        }
        Assert.assertEquals(f.attacker().getLife(), result.playerLifeAfter().get(f.attacker().getId()).intValue());
        Assert.assertEquals(f.defender().getLife(), result.playerLifeAfter().get(f.defender().getId()).intValue());
        for (final Card card : java.util.stream.Stream.concat(java.util.stream.Stream.of(f.source()), f.blockers().stream()).toList()) {
            Assert.assertEquals(!card.isInPlay(), result.lostCreatures().contains(card.getId()));
            if (card.isInPlay()) {
                Assert.assertEquals(card.getDamage(), result.survivors().get(card.getId()).markedDamage());
                Assert.assertEquals(card.getCounters(CounterEnumType.SHIELD), result.survivors().get(card.getId()).shieldCounters());
                Assert.assertEquals(card.hasBeenDealtDeathtouchDamage(), result.survivors().get(card.getId()).markedDeathtouch());
            }
        }
    }

    private static Card creature(final Player owner, final List<String> properties) {
        final List<String> script = new ArrayList<>(List.of("Name:Shield Combat Fixture", "ManaCost:3", "Types:Creature Human"));
        script.addAll(properties);
        final Card card = Card.fromPaperCard(new PaperCard(CardRules.fromScript(script), CardEdition.UNKNOWN_CODE, CardRarity.Special), owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());
        owner.getZone(ZoneType.Battlefield).add(card);
        card.setSickness(false);
        return card;
    }
}
