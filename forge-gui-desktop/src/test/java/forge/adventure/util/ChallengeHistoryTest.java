package forge.adventure.util;

import forge.adventure.player.PlayerStatistic;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import org.testng.annotations.Test;

import java.util.Map;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

@Test(groups = {"UnitTest", "fast"})
public class ChallengeHistoryTest {
    public void oldXiraWinNeedsEvidenceFromTheCorrectRoom() {
        var history = new PlayerStatistic();
        var room = new PointOfInterestChanges();
        String hive = "19Xiras Hive/../common/maps/map/minibosses/xira.tmx";
        room.deleteObject(67);
        var locations = Map.of(hive, room);
        assertFalse(ChallengeProgress.hasEvidenceOfVictory("Xira", history, locations));
        history.setResult("Xira", true);
        assertFalse(ChallengeProgress.hasEvidenceOfVictory("Xira", history, Map.of()));
        assertFalse(ChallengeProgress.hasEvidenceOfVictory("Xira", history,
                Map.of("19Unrelated/../common/maps/map/minibosses/other.tmx", room)));
        assertTrue(ChallengeProgress.hasEvidenceOfVictory("Xira", history, locations));
        var hardRoom = new PointOfInterestChanges();
        hardRoom.deleteObject(104);
        assertTrue(ChallengeProgress.hasEvidenceOfVictory("Xira", history, Map.of(hive, hardRoom)));
    }

    public void acceptsCanonicalLocationRecordOrReviewedAliasButNotAnUnrelatedWin() {
        var history = new PlayerStatistic();
        history.setResult("Basilisk of the Chamber", false);
        assertFalse(ChallengeProgress.hasEvidenceOfVictory("HP Chamber Basilisk", history, Map.of()));
        history.setResult("Basilisk of the Chamber", true);
        assertTrue(ChallengeProgress.hasEvidenceOfVictory("HP Chamber Basilisk", history, Map.of()));
        assertFalse(ChallengeProgress.hasEvidenceOfVictory("HP Roaming Basilisk", history, Map.of()));
        var location = new PointOfInterestChanges();
        location.recordEnemyDefeat("Different boss");
        assertTrue(ChallengeProgress.hasEvidenceOfVictory("Different boss", history, Map.of("root", location)));
    }
}
