package forge.adventure;

import forge.adventure.player.AdventureChallenges;
import forge.adventure.player.AdventureChallenges.Challenge;
import forge.adventure.player.AdventureChallenges.Snapshot;
import org.testng.annotations.Test;

import java.util.Random;
import java.util.Set;

import static org.testng.Assert.*;

@Test(groups = {"UnitTest", "fast"})
public class AdventureChallengesTest {
    public void bossCreditsAndSelectionsRoundTripIndependently() {
        var progress = new AdventureChallenges();
        assertFalse(progress.beginHistoryImport("Shandalar"));
        progress.load(null);
        progress.setLevel(1);
        progress.setEnabled(Challenge.WASTED_POTENTIAL, true);
        assertEquals(progress.getLevel(), 0);
        assertFalse(progress.isEnabled(Challenge.WASTED_POTENTIAL));

        assertTrue(progress.creditBoss("Shandalar/enemy/Xira"));
        assertFalse(progress.creditBoss("Shandalar/enemy/Xira"));
        assertTrue(progress.creditBoss("Shandalar/encounter/second-xira"));
        assertEquals(progress.getLevel(), 0);
        assertTrue(progress.isUnlocked(Challenge.WASTED_POTENTIAL));
        progress.setLevel(7);
        progress.setEnabled(Challenge.WASTED_POTENTIAL, true);
        assertTrue(progress.beginHistoryImport("Shandalar"));

        var loaded = new AdventureChallenges();
        loaded.load(progress.save());
        assertEquals(loaded.getMaxLevel(), 2);
        assertEquals(loaded.getLevel(), 2);
        assertTrue(loaded.isEnabled(Challenge.WASTED_POTENTIAL));
        assertFalse(loaded.beginHistoryImport("Shandalar"));
        assertFalse(loaded.isUnlocked(Challenge.WELL_PREPARED));
        assertTrue(loaded.beginHistoryImport("Shandalar/enemy/optional boss"));

        var snapshot = loaded.snapshot(null);
        loaded.setLevel(0);
        loaded.setEnabled(Challenge.WASTED_POTENTIAL, false);
        assertEquals(snapshot.level(), 2);
        assertTrue(snapshot.enabled().contains(Challenge.WASTED_POTENTIAL));
        loaded.load(null);
        assertEquals(loaded.getMaxLevel(), 0);
        assertFalse(loaded.isUnlocked(Challenge.WASTED_POTENTIAL));
        loaded.clear();
        loaded.load(loaded.save());
        assertFalse(loaded.beginHistoryImport("Shandalar/encounter/second-xira"));
    }

    public void modifiersStackAndRoundWithoutExtraRollsAtZero() {
        var zero = Snapshot.NONE;
        Random unused = new Random() {
            @Override public int nextInt(int bound) {
                throw new AssertionError("No Challenge RNG expected");
            }
        };
        assertEquals(zero.startingLife(25, 0.82f), 21);
        assertEquals(zero.bonusCards(unused), 0);
        assertEquals(Snapshot.bonusCurrency(3, 0, unused), 0);
        assertEquals(zero.bonusGold(100, unused), 0);
        assertEquals(zero.bonusShards(20, unused), 0);

        var levelOne = new Snapshot(1, Set.of(), null);
        assertEquals(levelOne.bonusGold(0, unused), 5);
        assertEquals(levelOne.bonusShards(1, roll(4)), 2);
        assertEquals(levelOne.bonusShards(1, roll(5)), 1);

        var levelFive = new Snapshot(5, Set.of(Challenge.WASTED_POTENTIAL, Challenge.WELL_PREPARED, Challenge.WAR_CHEST), null);
        assertEquals(levelFive.startingLife(25, 0.82f), 31);
        assertEquals(levelFive.goldPercent(), 25);
        assertEquals(levelFive.bonusGold(0, unused), 75);
        assertEquals(levelFive.bonusGold(100, unused), 100);
        assertEquals(levelFive.shardPercent(), 25);
        assertEquals(levelFive.bonusShards(20, unused), 10);
        assertEquals(levelFive.startingEffect().startBattleWithCard, new String[] {"Wastes", "c_a_treasure_sac"});
        assertEquals(levelFive.guaranteedCards(), 0);
        assertEquals(levelFive.startingEffect().changeStartCards, 1);
        assertEquals(new Snapshot(1, Set.of(), null).bonusCards(roll(4)), 1);
        assertEquals(new Snapshot(1, Set.of(), null).bonusCards(roll(5)), 0);
        assertEquals(new Snapshot(19, Set.of(), null).bonusCards(roll(94)), 1);
        assertEquals(new Snapshot(19, Set.of(), null).bonusCards(roll(95)), 0);
        assertEquals(new Snapshot(20, Set.of(), null).bonusCards(unused), 1);
        assertEquals(new Snapshot(25, Set.of(), null).bonusCards(roll(24)), 2);
        assertEquals(new Snapshot(25, Set.of(), null).bonusCards(roll(25)), 1);
        assertEquals(Snapshot.bonusCurrency(3, 5, roll(14)), 1);
        assertEquals(Snapshot.bonusCurrency(3, 5, roll(15)), 0);
    }

    public void warChestUnlockPersistsAfterSpendingGold() {
        var progress = new AdventureChallenges();
        progress.recordGoldBalance(AdventureChallenges.WAR_CHEST_GOLD - 1);
        assertFalse(progress.isUnlocked(Challenge.WAR_CHEST));
        progress.recordGoldBalance(AdventureChallenges.WAR_CHEST_GOLD);
        assertFalse(progress.isEnabled(Challenge.WAR_CHEST));
        progress.setEnabled(Challenge.WAR_CHEST, true);
        progress.recordGoldBalance(0);
        var loaded = new AdventureChallenges();
        loaded.load(progress.save());
        assertTrue(loaded.snapshot(null).enabled().contains(Challenge.WAR_CHEST));
        assertEquals(loaded.snapshot(null).bonusGold(0, roll(0)), 50);
    }

    public void artifactChallengesStackWithExistingStartingPermanents() {
        var progress = new AdventureChallenges();
        for (Challenge challenge : Challenge.values()) {
            progress.unlock(challenge);
        }
        progress.setAllEnabled(true);
        var snapshot = progress.snapshot(null);
        progress.setAllEnabled(false);
        assertEquals(snapshot.startingEffect().startBattleWithCard,
                new String[] {"Wastes", "c_a_treasure_sac", "Mox Pearl", "Mox Sapphire", "Mox Jet",
                        "Mox Ruby", "Mox Emerald", "Sol Ring"});
        assertEquals(snapshot.startingEffect().changeStartCards, 1);
    }

    private static Random roll(int value) {
        return new Random() {
            @Override public int nextInt(int bound) {
                assertEquals(bound, 100);
                return value;
            }
        };
    }
}
