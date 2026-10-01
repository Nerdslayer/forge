package forge.ai.combat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

/** Reusable event provenance boundaries, independent of card scripts or live game objects. */
public class CombatEventBatchTest {
    @Test
    public void declarationsSeparateTappingBlockingAndBecomingBlocked() {
        final var snapshot = board(Map.of(10, creature(10, 1, 2, 2, false, false, false),
                11, creature(11, 1, 2, 2, false, false, true),
                20, creature(20, 2, 1, 3, false, false, false)), Map.of(10, 2, 11, 2), true, true);
        final var result = CombatOutcomePredictor.predict(snapshot,
                new CombatAssignment(snapshot.attackersToDefenders(), Map.of(10, List.of(20))));
        final var attack = batch(result, CombatEventBatch.Stage.ATTACK_DECLARATION);
        Assert.assertEquals(attack.events(), List.of(new CombatEventBatch.Taps(10),
                new CombatEventBatch.Attacks(10, 2), new CombatEventBatch.Attacks(11, 2)));
        Assert.assertFalse(attack.battlefieldBefore().get(10).tapped());
        final var block = batch(result, CombatEventBatch.Stage.BLOCK_DECLARATION);
        Assert.assertEquals(block.events(), List.of(new CombatEventBatch.Blocks(20, 10),
                new CombatEventBatch.BecomesBlocked(10, List.of(20))));
        Assert.assertTrue(block.battlefieldBefore().get(10).tapped());
        Assert.assertFalse(block.battlefieldBefore().get(11).tapped());
        Assert.expectThrows(UnsupportedOperationException.class, () -> attack.events().clear());
        Assert.expectThrows(UnsupportedOperationException.class, () -> block.battlefieldBefore().clear());
    }

    @Test
    public void simultaneousTradeRetainsBothSourcesAndPostDamageLastKnownState() {
        final var snapshot = board(Map.of(10, creature(10, 1, 2, 2, false, true, false),
                20, creature(20, 2, 2, 2, false, true, false)), Map.of(10, 2), true, true);
        final var result = CombatOutcomePredictor.predict(snapshot,
                new CombatAssignment(snapshot.attackersToDefenders(), Map.of(10, List.of(20))));
        final var damage = batch(result, CombatEventBatch.Stage.REGULAR_DAMAGE);
        Assert.assertEquals(damage.events(), List.of(
                new CombatEventBatch.Damage(10, 20, CombatEventBatch.Recipient.CREATURE, 2),
                new CombatEventBatch.Damage(20, 10, CombatEventBatch.Recipient.CREATURE, 2),
                new CombatEventBatch.LifeGain(10, 1, 2), new CombatEventBatch.LifeGain(20, 2, 2)));
        Assert.assertEquals(damage.playerLifeBefore(), Map.of(1, 20, 2, 20));
        final var deaths = batch(result, CombatEventBatch.Stage.REGULAR_DEATHS);
        Assert.assertEquals(deaths.events(), List.of(new CombatEventBatch.Dies(10), new CombatEventBatch.Dies(20)));
        Assert.assertEquals(deaths.battlefieldBefore().keySet(), Set.of(10, 20));
        Assert.assertEquals(deaths.battlefieldBefore().get(10).markedDamage(), 2);
        Assert.assertEquals(deaths.battlefieldBefore().get(20).markedDamage(), 2);
        Assert.assertEquals(deaths.playerLifeBefore(), Map.of(1, 22, 2, 22));
        Assert.assertTrue(result.survivors().isEmpty());
    }

    @Test
    public void doubleStrikeUsesDistinctBatchesAndDoesNotRepeatFirstStrikeDeaths() {
        final var snapshot = board(Map.of(10, creature(10, 1, 3, 3, true, false, false),
                20, creature(20, 2, 1, 2, false, false, false)), Map.of(10, 2), true, true);
        final var result = CombatOutcomePredictor.predict(snapshot,
                new CombatAssignment(snapshot.attackersToDefenders(), Map.of(10, List.of(20))));
        Assert.assertEquals(result.eventBatches().stream().map(CombatEventBatch::stage).toList(), List.of(
                CombatEventBatch.Stage.ATTACK_DECLARATION, CombatEventBatch.Stage.BLOCK_DECLARATION,
                CombatEventBatch.Stage.FIRST_STRIKE_DAMAGE, CombatEventBatch.Stage.FIRST_STRIKE_DEATHS,
                CombatEventBatch.Stage.REGULAR_DAMAGE));
        Assert.assertEquals(batch(result, CombatEventBatch.Stage.FIRST_STRIKE_DEATHS).events(),
                List.of(new CombatEventBatch.Dies(20)));
        final var regular = batch(result, CombatEventBatch.Stage.REGULAR_DAMAGE);
        Assert.assertFalse(regular.battlefieldBefore().containsKey(20));
        Assert.assertEquals(regular.events(), List.of(
                new CombatEventBatch.Damage(10, 2, CombatEventBatch.Recipient.PLAYER, 3),
                new CombatEventBatch.LifeLoss(2, 3)));
    }

    @Test
    public void lifelinkGainIsPerSourceNotPerRecipientAndLifeRestrictionsDoNotEraseDamage() {
        for (final boolean canChangeLife : List.of(true, false)) {
            final var snapshot = board(Map.of(10, creature(10, 1, 5, 5, false, true, false),
                    20, creature(20, 2, 1, 2, false, false, false)), Map.of(10, 2), canChangeLife, canChangeLife);
            final var result = CombatOutcomePredictor.predict(snapshot,
                    new CombatAssignment(snapshot.attackersToDefenders(), Map.of(10, List.of(20))));
            final var events = batch(result, CombatEventBatch.Stage.REGULAR_DAMAGE).events();
            Assert.assertTrue(events.contains(new CombatEventBatch.Damage(10, 20, CombatEventBatch.Recipient.CREATURE, 2)));
            Assert.assertTrue(events.contains(new CombatEventBatch.Damage(10, 2, CombatEventBatch.Recipient.PLAYER, 3)));
            Assert.assertEquals(events.stream().filter(CombatEventBatch.LifeGain.class::isInstance).toList(),
                    canChangeLife ? List.of(new CombatEventBatch.LifeGain(10, 1, 5)) : List.of());
            Assert.assertEquals(events.stream().filter(CombatEventBatch.LifeLoss.class::isInstance).toList(),
                    canChangeLife ? List.of(new CombatEventBatch.LifeLoss(2, 3)) : List.of());
        }
    }

    @Test
    public void zeroDamageAndRejectedAssignmentsProduceNoDamageOrSyntheticEvents() {
        final var snapshot = board(Map.of(10, creature(10, 1, 0, 2, false, true, false)), Map.of(10, 2), true, true);
        final var result = CombatOutcomePredictor.predict(snapshot, new CombatAssignment(snapshot.attackersToDefenders(), Map.of()));
        Assert.assertEquals(result.eventBatches().size(), 1);
        Assert.assertEquals(result.eventBatches().get(0).stage(), CombatEventBatch.Stage.ATTACK_DECLARATION);
        final var rejected = CombatOutcomePredictor.predict(snapshot, new CombatAssignment(Map.of(), Map.of()));
        Assert.assertFalse(rejected.available());
        Assert.assertTrue(rejected.eventBatches().isEmpty());
        final var synthetic = new CombatProjection(result.supported(), result.available(), result.reasons(), result.lostCreatures(),
                result.survivors(), result.playerLifeAfter(), result.batches(), result.terminal());
        Assert.assertTrue(synthetic.eventBatches().isEmpty());
    }

    private static CombatEventBatch batch(final CombatProjection projection, final CombatEventBatch.Stage stage) {
        Assert.assertTrue(projection.available(), projection.reasons().toString());
        return projection.eventBatches().stream().filter(value -> value.stage() == stage).findFirst().orElseThrow();
    }

    private static PublicCombatSnapshot.Creature creature(final int id, final int controller, final int power,
            final int toughness, final boolean doubleStrike, final boolean lifelink, final boolean vigilance) {
        return new PublicCombatSnapshot.Creature(id, controller, power, toughness, 0,
                false, false, doubleStrike, false, false, true, lifelink, vigilance, false);
    }

    private static PublicCombatSnapshot board(final Map<Integer, PublicCombatSnapshot.Creature> creatures,
            final Map<Integer, Integer> attackers, final boolean canLoseLife, final boolean canGainLife) {
        final Map<Integer, Set<Integer>> blocks = new java.util.LinkedHashMap<>();
        attackers.keySet().forEach(id -> blocks.put(id, creatures.values().stream().filter(card -> card.controllerId() == 2)
                .map(PublicCombatSnapshot.Creature::id).collect(java.util.stream.Collectors.toSet())));
        return new PublicCombatSnapshot(1, 1, 2, creatures, Map.of(
                1, new PublicCombatSnapshot.LifeState(20, canLoseLife, canGainLife, false, false),
                2, new PublicCombatSnapshot.LifeState(20, canLoseLife, canGainLife, false, false)),
                attackers, blocks, List.of(), List.of(), false);
    }
}
