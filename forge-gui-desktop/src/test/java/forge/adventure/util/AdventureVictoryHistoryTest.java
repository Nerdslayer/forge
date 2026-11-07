package forge.adventure.util;

import forge.adventure.player.PlayerStatistic;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import org.testng.Assert;
import org.testng.annotations.Test;
import static org.mockito.Mockito.*;

@Test(groups = {"UnitTest", "fast"})
public class AdventureVictoryHistoryTest {
    @org.testng.annotations.BeforeClass
    public void initializeLocalization() {
        var languages = java.nio.file.Path.of(System.getProperty("user.dir"), "forge-gui", "res", "languages");
        if (!java.nio.file.Files.isDirectory(languages)) {
            languages = java.nio.file.Path.of(System.getProperty("user.dir"), "..", "forge-gui", "res", "languages");
        }
        forge.util.Localizer.getInstance().initialize("en-US", languages.normalize().toString());
    }

    @Test public void namedDefeatStagesMatchOnlyTheirTargetAndOptInToPreviousVictories() {
        var player = mock(forge.adventure.player.AdventurePlayer.class);
        var history = new PlayerStatistic();
        when(player.getStatistic()).thenReturn(history);
        var enemy = new forge.adventure.data.EnemyData();
        enemy.name = "Canonical ID";
        enemy.nameOverride = "Legacy display";
        var target = mock(forge.adventure.character.EnemySprite.class);
        when(target.getData()).thenReturn(enemy);
        var other = mock(forge.adventure.character.EnemySprite.class);
        var otherData = new forge.adventure.data.EnemyData();
        otherData.name = "Other enemy";
        when(other.getData()).thenReturn(otherData);
        var map = mock(forge.adventure.stage.MapStage.class);
        try (var current = mockStatic(Current.class);
             var world = mockStatic(forge.adventure.data.WorldData.class);
             var maps = mockStatic(forge.adventure.stage.MapStage.class);
             var hud = mockStatic(forge.adventure.stage.GameHUD.class);
             var quests = mockStatic(AdventureQuestController.class, CALLS_REAL_METHODS);
             var forgeMock = mockStatic(forge.Forge.class)) {
            current.when(Current::player).thenReturn(player);
            world.when(() -> forge.adventure.data.WorldData.getEnemy("Canonical ID")).thenReturn(enemy);
            maps.when(forge.adventure.stage.MapStage::getInstance).thenReturn(map);
            hud.when(forge.adventure.stage.GameHUD::getInstance).thenReturn(mock(forge.adventure.stage.GameHUD.class));
            quests.when(AdventureQuestController::instance).thenReturn(mock(AdventureQuestController.class));
            forgeMock.when(forge.Forge::getLocalizer).thenReturn(mock(forge.util.Localizer.class));
            var stage = new forge.adventure.data.AdventureQuestStage();
            stage.objective = AdventureQuestController.ObjectiveTypes.Defeat;
            stage.worldMapOK = true;
            stage.targetEnemy = "Canonical ID";
            stage.creditPreviousVictory = true;
            stage.count3 = 1;
            var quest = new forge.adventure.data.AdventureQuestData();
            quest.stages = new forge.adventure.data.AdventureQuestStage[]{stage};
            history.setResult("Legacy display", false);
            quest.activateNextStages();
            Assert.assertFalse(quest.completed);
            var event = new AdventureQuestEvent();
            event.type = AdventureQuestEventType.MATCHCOMPLETE;
            event.winner = true;
            event.enemy = other;
            quest.updateStages(event);
            Assert.assertFalse(quest.completed);
            event.winner = false;
            event.enemy = target;
            quest.updateStages(event);
            Assert.assertFalse(quest.completed);
            var copied = new forge.adventure.data.AdventureQuestStage(stage);
            Assert.assertEquals(copied.targetEnemy, "Canonical ID");
            Assert.assertTrue(copied.creditPreviousVictory);
            history.setResult("Legacy display", true);
            var catchup = new forge.adventure.data.AdventureQuestStage();
            catchup.objective = AdventureQuestController.ObjectiveTypes.Defeat;
            catchup.targetEnemy = "Canonical ID";
            catchup.creditPreviousVictory = true;
            var earlier = new forge.adventure.data.AdventureQuestData();
            earlier.stages = new forge.adventure.data.AdventureQuestStage[]{catchup};
            earlier.activateNextStages();
            Assert.assertTrue(earlier.completed);
            stage.creditPreviousVictory = false;
            Assert.assertFalse(stage.hasPreviousVictory());
            Assert.assertFalse(quest.completed);
            event.winner = true;
            quest.updateStages(event);
            Assert.assertTrue(quest.completed);
        }
    }

    @Test public void dialogueRecognizesLegacyWinsAndRootLocationHistory() {
        var player = mock(forge.adventure.player.AdventurePlayer.class);
        var history = new PlayerStatistic();
        when(player.getStatistic()).thenReturn(history);
        var enemy = new forge.adventure.data.EnemyData();
        enemy.name = "Canonical ID";
        enemy.nameOverride = "Legacy display";
        var scene = mock(forge.adventure.scene.TileMapScene.class);
        var root = mock(forge.adventure.pointofintrest.PointOfInterest.class);
        var save = mock(forge.adventure.world.WorldSave.class);
        var changes = new PointOfInterestChanges();
        when(root.getID()).thenReturn("root-location");
        when(save.getPointOfInterestChanges("root-location")).thenReturn(changes);
        try (var current = mockStatic(Current.class);
             var world = mockStatic(forge.adventure.data.WorldData.class);
             var scenes = mockStatic(forge.adventure.scene.TileMapScene.class);
             var saves = mockStatic(forge.adventure.world.WorldSave.class)) {
            current.when(Current::player).thenReturn(player);
            world.when(() -> forge.adventure.data.WorldData.getEnemy("Canonical ID")).thenReturn(enemy);
            scenes.when(forge.adventure.scene.TileMapScene::instance).thenReturn(scene);
            saves.when(forge.adventure.world.WorldSave::getCurrentSave).thenReturn(save);
            var dialog = new MapDialog("[]", null, -1);
            var condition = new forge.adventure.data.DialogData.ConditionData();
            condition.defeatedEnemy = "Canonical ID";
            var conditions = new forge.adventure.data.DialogData.ConditionData[]{condition};
            Assert.assertFalse(dialog.isConditionOk(conditions));
            history.setResult("Legacy display", false);
            Assert.assertFalse(dialog.isConditionOk(conditions));
            history.setResult("Legacy display", true);
            Assert.assertTrue(dialog.isConditionOk(conditions));
            condition.not = true;
            Assert.assertFalse(dialog.isConditionOk(conditions));
            history.getWinLossRecord().clear();
            scene.rootPoint = root;
            changes.recordEnemyDefeat("Canonical ID");
            condition.not = false;
            Assert.assertTrue(dialog.isConditionOk(conditions));
        }
    }

    @Test public void ordinaryEntriesStayOpenAndSecretEntriesRequireTheirOwnFlag() {
        var player = mock(forge.adventure.player.AdventurePlayer.class);
        try (var current = mockStatic(Current.class)) {
            current.when(Current::player).thenReturn(player);
            var entry = new forge.adventure.character.EntryActor(null, 1, "destination", 0, 0, 16, 16, "up", "source", 2);
            Assert.assertTrue(entry.isUnlocked());
            entry.requireQuestFlag("secret-found", "A concealed doorway.");
            Assert.assertFalse(entry.isUnlocked());
            when(player.checkQuestFlag("unrelated")).thenReturn(true);
            Assert.assertFalse(entry.isUnlocked());
            when(player.checkQuestFlag("secret-found")).thenReturn(true);
            Assert.assertTrue(entry.isUnlocked());
        }
    }

    @Test public void savedQuestFlagsCountOnlyForStagesThatOptIn() {
        var player = mock(forge.adventure.player.AdventurePlayer.class);
        try (var current = mockStatic(Current.class)) {
            current.when(Current::player).thenReturn(player);
            var stage = new forge.adventure.data.AdventureQuestStage();
            stage.objective = AdventureQuestController.ObjectiveTypes.QuestFlag;
            stage.mapFlag = "clue";
            stage.mapFlagValue = 2;
            when(player.checkQuestFlag("clue")).thenReturn(true);
            when(player.getQuestFlag("clue")).thenReturn(2);
            Assert.assertFalse(stage.hasPreviousProgress());
            stage.creditPreviousQuestFlag = true;
            var copied = new forge.adventure.data.AdventureQuestStage(stage);
            Assert.assertEquals(copied.handleEvent(new AdventureQuestEvent()), AdventureQuestController.QuestStatus.COMPLETE);
            when(player.getQuestFlag("clue")).thenReturn(1);
            Assert.assertFalse(stage.hasPreviousProgress());
        }
    }

    @Test public void dialogueTreatsAbandonedAndCompletedQuestsAsInactive() {
        var player = mock(forge.adventure.player.AdventurePlayer.class);
        try (var current = mockStatic(Current.class)) {
            current.when(Current::player).thenReturn(player);
            var quest = new com.badlogic.gdx.utils.Json().fromJson(forge.adventure.data.AdventureQuestData.class, "{id:9001}");
            when(player.getQuests()).thenReturn(java.util.List.of(quest));
            var dialog = new MapDialog("[]", null, -1);
            var condition = new forge.adventure.data.DialogData.ConditionData();
            condition.activeQuest = 9001;
            condition.not = true;
            var conditions = new forge.adventure.data.DialogData.ConditionData[]{condition};
            Assert.assertFalse(dialog.isConditionOk(conditions));
            quest.completed = true;
            Assert.assertTrue(dialog.isConditionOk(conditions));
            quest.completed = false;
            quest.fail();
            Assert.assertTrue(dialog.isConditionOk(conditions));
        }
    }

    @Test public void previousWinsSurviveSaveAndLossesDoNotCountAsVictories() {
        var history = new PlayerStatistic();
        history.setResult("Old display name", false);
        Assert.assertFalse(history.hasWonAgainst("Enemy ID", "Old display name"));
        history.setResult("Old display name", true);
        Assert.assertTrue(history.hasWonAgainst("Enemy ID", "Old display name"));
        Assert.assertFalse(history.hasWonAgainst("Different enemy"));
        var restored = new PlayerStatistic();
        restored.load(history.save());
        Assert.assertTrue(restored.hasWonAgainst("Old display name"));
        var location = new PointOfInterestChanges();
        location.recordEnemyDefeat("Enemy ID");
        var restoredLocation = new PointOfInterestChanges();
        restoredLocation.load(location.save());
        Assert.assertTrue(restoredLocation.isEnemyDefeated("Enemy ID"));
        Assert.assertFalse(restoredLocation.isEnemyDefeated("Different enemy"));
    }
}
