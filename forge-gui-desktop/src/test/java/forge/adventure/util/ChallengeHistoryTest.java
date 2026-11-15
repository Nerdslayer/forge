package forge.adventure.util;

import forge.adventure.data.ItemData;
import forge.adventure.player.AdventureChallenges.Challenge;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.PlayerStatistic;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.item.PaperCard;
import org.testng.annotations.Test;

import java.util.Map;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Test(groups = {"UnitTest", "fast"})
public class ChallengeHistoryTest {
    private static final String EMRAKUL_CASTLE = "48Emrakul/../common/maps/map/emrakul/emrakul.tmx";

    public void recoversCastleKeysWithoutEnablingTheirChallenges() {
        var player = new AdventurePlayer();
        giveKey(player, "Blue Key");
        ChallengeProgress.recoverShandalarStoryChallenges(player, Map.of());
        var progress = player.getChallenges();
        assertTrue(progress.isUnlocked(Challenge.MOX_SAPPHIRE));
        assertFalse(progress.isUnlocked(Challenge.MOX_RUBY));
        assertFalse(progress.hasEnabledChallenges());

        giveKey(player, "Strange Key");
        ChallengeProgress.recoverShandalarStoryChallenges(player, Map.of());
        assertTrue(progress.isUnlocked(Challenge.MOX_RUBY));
        assertFalse(progress.isUnlocked(Challenge.SOL_RING));
    }

    public void distinguishesConsumedKeysFromAnEmrakulVictory() {
        var player = new AdventurePlayer();
        var castle = new PointOfInterestChanges();
        castle.deleteObject(71);
        castle.deleteObject(73);
        var progress = player.getChallenges();
        ChallengeProgress.recoverShandalarStoryChallenges(player, Map.of(EMRAKUL_CASTLE, castle));
        assertTrue(progress.isUnlocked(Challenge.MOX_EMERALD));
        assertFalse(progress.isUnlocked(Challenge.SOL_RING));

        player.getStatistic().setResult("Emrakul", true);
        ChallengeProgress.recoverShandalarStoryChallenges(player, Map.of("unrelated", castle));
        assertFalse(progress.isUnlocked(Challenge.SOL_RING));
        ChallengeProgress.recoverShandalarStoryChallenges(player, Map.of(EMRAKUL_CASTLE, castle));
        assertTrue(progress.isUnlocked(Challenge.SOL_RING));
        assertFalse(progress.hasEnabledChallenges());
    }

    public void artifactRewardsUseCardColorAndPrintingRarity() {
        var blueRare = printedCard(MagicColor.BLUE | MagicColor.RED, CardRarity.Rare);
        var blueUncommon = printedCard(MagicColor.BLUE, CardRarity.Uncommon);
        var colorlessMythic = printedCard(MagicColor.COLORLESS, CardRarity.MythicRare);
        assertTrue(ChallengeProgress.isArtifactReward(blueRare, MagicColor.BLUE));
        assertFalse(ChallengeProgress.isArtifactReward(blueRare, MagicColor.WHITE));
        assertFalse(ChallengeProgress.isArtifactReward(blueRare, MagicColor.COLORLESS));
        assertFalse(ChallengeProgress.isArtifactReward(blueUncommon, MagicColor.BLUE));
        assertTrue(ChallengeProgress.isArtifactReward(colorlessMythic, MagicColor.COLORLESS));
        assertFalse(ChallengeProgress.isArtifactReward(colorlessMythic, MagicColor.BLUE));
    }

    private static PaperCard printedCard(int colors, CardRarity rarity) {
        var card = mock(PaperCard.class);
        var rules = mock(CardRules.class);
        when(card.getRules()).thenReturn(rules);
        when(card.getRarity()).thenReturn(rarity);
        when(rules.getColor()).thenReturn(ColorSet.fromMask(colors));
        return card;
    }

    private static void giveKey(AdventurePlayer player, String name) {
        var key = new ItemData();
        key.name = name;
        player.getItems().add(key);
    }

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
