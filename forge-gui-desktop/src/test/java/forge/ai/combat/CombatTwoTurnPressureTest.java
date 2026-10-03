package forge.ai.combat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.effect.ValuationCompleteness;

public class CombatTwoTurnPressureTest {
    @Test
    public void exactLethalAfterRemovingTwoAttackersIsPressureButOneLifeMoreIsNot() {
        for (final int life : List.of(16, 17)) {
            final var before = board(20, life, null);
            final var current = CombatOutcomePredictor.predict(before, new CombatAssignment(before.attackersToDefenders(), Map.of()));
            final var ready = readiness(before);
            final var reply = CombatSafetyEvaluator.forecastNextAttack(before, current, ready, values(before, 100), new CombatSearchBudget(10000));
            final var forecast = CombatTwoTurnPressureEvaluator.evaluate(before, current, ready, reply, new CombatSearchBudget(10000));
            Assert.assertTrue(forecast.usable(), forecast.reasons().toString());
            Assert.assertEquals(forecast.removedAttackers(), List.of(10, 11));
            Assert.assertEquals(forecast.currentDamage(), 10);
            Assert.assertEquals(forecast.nextDamage(), 6);
            Assert.assertEquals(forecast.lethalOpportunity(), life == 16);
            Assert.assertEquals(forecast.value(), life == 16 ? 500 : 0);
            Assert.assertEquals(current.playerLifeAfter().get(2).intValue(), life - 10);
            Assert.assertEquals(current.survivors().size(), 5, "Stress must not mutate the actual current projection");
        }
    }

    @Test
    public void replyLifelinkDefeatsAGrossDamageSumAndLethalReplySuppressesPressure() {
        for (final int ownLife : List.of(20, 6)) {
            final var before = board(ownLife, 16, creature(30, 2, 6, 6, false, true));
            final var current = CombatOutcomePredictor.predict(before, new CombatAssignment(before.attackersToDefenders(), Map.of()));
            final var ready = readiness(before);
            final var reply = CombatSafetyEvaluator.forecastNextAttack(before, current, ready, values(before, 100), new CombatSearchBudget(10000));
            Assert.assertTrue(reply.evaluation().usable());
            final var forecast = CombatTwoTurnPressureEvaluator.evaluate(before, current, ready, reply, new CombatSearchBudget(10000));
            Assert.assertFalse(forecast.lethalOpportunity());
            Assert.assertEquals(forecast.value(), 0);
            if (ownLife == 20) {
                Assert.assertEquals(forecast.currentDamage() + forecast.nextDamage(), 16,
                        "Gross damage would look lethal, but the reply gains six life");
                Assert.assertEquals(forecast.opponentLifeAfterReply(), 12);
            } else {
                Assert.assertTrue(reply.evaluation().lethalOpportunity());
                Assert.assertTrue(forecast.removedAttackers().isEmpty(), "No deeper work after a lethal reply");
            }
        }
    }

    @Test
    public void strongestAttackersUseDoubleStrikeDamageAndStressRemovesAllWhenOnlyOneCanAttack() {
        final var original = board(20, 30, null);
        final var creatures = new LinkedHashMap<>(original.creatures());
        creatures.put(10, creature(10, 1, 6, 6, false, false));
        creatures.put(11, creature(11, 1, 4, 4, true, false));
        final var before = new PublicCombatSnapshot(1, 1, 2, creatures, original.players(), original.attackersToDefenders(),
                original.legalBlockers(), List.of(), List.of(), false);
        final var current = CombatOutcomePredictor.predict(before, new CombatAssignment(before.attackersToDefenders(), Map.of()));
        final var ready = readiness(before);
        final var reply = CombatSafetyEvaluator.forecastNextAttack(before, current, ready, values(before, 100), new CombatSearchBudget(10000));
        final var forecast = CombatTwoTurnPressureEvaluator.evaluate(before, current, ready, reply, new CombatSearchBudget(10000));
        Assert.assertEquals(forecast.removedAttackers(), List.of(11, 10));
        final var singleReady = new PublicCombatReadiness(2, Set.of(10), ready.blockPairsNextTurn(), List.of());
        final var single = CombatTwoTurnPressureEvaluator.evaluate(before, current, singleReady, reply, new CombatSearchBudget(10000));
        Assert.assertEquals(single.removedAttackers(), List.of(10));
        Assert.assertEquals(single.nextDamage(), 0);
        Assert.assertFalse(single.lethalOpportunity());
    }

    @Test
    public void incompleteStressSearchCannotProduceABonus() {
        final var before = board(20, 16, null);
        final var current = CombatOutcomePredictor.predict(before, new CombatAssignment(before.attackersToDefenders(), Map.of()));
        final var ready = readiness(before);
        final var reply = CombatSafetyEvaluator.forecastNextAttack(before, current, ready, values(before, 100), new CombatSearchBudget(10000));
        final var forecast = CombatTwoTurnPressureEvaluator.evaluate(before, current, ready, reply, new CombatSearchBudget(1));
        Assert.assertFalse(forecast.usable());
        Assert.assertEquals(forecast.value(), 0);
        Assert.assertFalse(forecast.reasons().isEmpty());
    }

    @Test
    public void aggressivePressureCanOutweighAnEngineTradeButNeverAnAvoidableLethalReply() {
        for (final int ownLife : List.of(20, 2)) {
            final var before = board(ownLife, 12, creature(30, 2, 3, 2, false, false));
            final var result = CombatAttackSearch.search(before, values(before, 700), readiness(before), new CombatSearchBudget(500000));
            Assert.assertTrue(result.outcomeDomainComplete() && result.candidateSearchComplete(), result.reasons().toString());
            Assert.assertFalse(result.searchExhaustive(), "A completed greedy frontier is not an exhaustive subset search");
            final var chosen = result.best().orElseThrow();
            Assert.assertFalse(chosen.reply().orElseThrow().lethalOpportunity());
            if (ownLife == 20) {
                Assert.assertEquals(chosen.attackers().size(), 5);
                Assert.assertTrue(chosen.combat().score().permanentLoss().total() < 0, "Supported pressure can justify the costly trade");
                Assert.assertEquals(chosen.twoTurnPressure().orElseThrow().value(), 500);
                Assert.assertEquals(chosen.total(), chosen.combat().score().total() + chosen.replyAdjustment() + 500,
                        "Charge the single pressure allowance once, not again through reply utility");
                Assert.assertFalse(chosen.certifiedWin(), "Two-turn pressure is not an actual terminal win");
                Assert.assertTrue(chosen.total() < 10000);
            } else {
                Assert.assertTrue(chosen.attackers().size() < 5, "Retain a defender against the lethal three-power reply");
            }
        }
    }

    private static PublicCombatSnapshot board(final int ownLife, final int opposingLife, final PublicCombatSnapshot.Creature opponent) {
        final Map<Integer, PublicCombatSnapshot.Creature> creatures = new LinkedHashMap<>();
        final Map<Integer, Integer> attacks = new LinkedHashMap<>();
        final Map<Integer, Set<Integer>> blocks = new LinkedHashMap<>();
        for (int id = 10; id < 15; id++) {
            creatures.put(id, creature(id, 1, 2, 2, false, false));
            attacks.put(id, 2);
            blocks.put(id, opponent == null ? Set.of() : Set.of(opponent.id()));
        }
        if (opponent != null) { creatures.put(opponent.id(), opponent); }
        return new PublicCombatSnapshot(1, 1, 2, creatures, Map.of(
                1, new PublicCombatSnapshot.LifeState(ownLife, true, true, false, false),
                2, new PublicCombatSnapshot.LifeState(opposingLife, true, true, false, false)), attacks, blocks, List.of(), List.of(), false);
    }

    private static PublicCombatSnapshot.Creature creature(final int id, final int controller, final int power, final int toughness,
            final boolean doubleStrike, final boolean lifelink) {
        return new PublicCombatSnapshot.Creature(id, controller, power, toughness, 0,
                false, false, doubleStrike, false, false, false, lifelink, false, false);
    }

    private static PublicCombatReadiness readiness(final PublicCombatSnapshot before) {
        final Map<Integer, Set<Integer>> pairs = new LinkedHashMap<>();
        before.creatures().forEach((id, creature) -> pairs.put(id, before.creatures().keySet().stream()
                .filter(other -> before.creatures().get(other).controllerId() != creature.controllerId())
                .collect(java.util.stream.Collectors.toSet())));
        return new PublicCombatReadiness(2, before.creatures().keySet(), pairs, List.of());
    }

    private static PreparedCombatValuation values(final PublicCombatSnapshot before, final int ownValue) {
        final Map<Integer, PreparedCombatValuation.PermanentValue> values = new LinkedHashMap<>();
        before.creatures().forEach((id, creature) -> values.put(id, new PreparedCombatValuation.PermanentValue(id, creature.controllerId(),
                creature.controllerId() == 1 ? -ownValue : 100, 0, 0, List.of())));
        return new PreparedCombatValuation(values, List.of(), ValuationCompleteness.PARTIAL, List.of());
    }
}
