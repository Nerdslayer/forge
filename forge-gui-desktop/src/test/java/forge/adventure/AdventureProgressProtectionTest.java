package forge.adventure;

import forge.Forge;
import forge.adventure.scene.AdventureExitDialog;
import forge.adventure.scene.DuelScene;
import forge.adventure.stage.MapStage;
import forge.adventure.util.AdventureExit;
import forge.adventure.util.AdventureExitRequest;
import forge.adventure.util.AdventureProgress;
import forge.adventure.util.AdventureSaveFile;
import forge.adventure.world.WorldSave;
import forge.util.Localizer;
import org.mockito.MockedStatic;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Test(groups = { "UnitTest", "fast" })
public class AdventureProgressProtectionTest {
    @Test
    public void overworldDuelWarnsAfterPreDuelAutosaveWithUnchangedPlayerData() {
        boolean previousAdventureMode = Forge.isMobileAdventureMode;
        Map<String, byte[]> savedState = Map.of("player/cards", new byte[]{1});
        AtomicInteger exits = new AtomicInteger();
        AtomicReference<Runnable> keepPlaying = new AtomicReference<>();
        try (MockedStatic<Forge> forge = mockStatic(Forge.class);
             MockedStatic<MapStage> maps = mockStatic(MapStage.class);
             MockedStatic<AdventureExitDialog> dialogs = mockStatic(AdventureExitDialog.class)) {
            Forge.isMobileAdventureMode = true;
            forge.when(Forge::getCurrentScene).thenReturn(mock(DuelScene.class));
            Localizer localizer = mock(Localizer.class, invocation -> "warning");
            forge.when(Forge::getLocalizer).thenReturn(localizer);
            maps.when(MapStage::getInstance).thenReturn(mock(MapStage.class)); // An overworld encounter, not a dungeon.
            dialogs.when(() -> AdventureExitDialog.show(anyString(), eq(false), any(), any(), any()))
                    .thenAnswer(invocation -> {
                        keepPlaying.set(invocation.getArgument(2));
                        return null;
                    });

            // The encounter autosave cleared earlier movement. Combat changes the match,
            // but the persistent Adventure player is not updated until GameEnd().
            AdventureExit.PROGRESS.checkpoint(System.currentTimeMillis(), savedState);
            Assert.assertFalse(AdventureExit.PROGRESS.hasChanges(savedState));
            try (MockedStatic<WorldSave> saves = mockStatic(WorldSave.class)) {
                WorldSave save = mock(WorldSave.class);
                saves.when(WorldSave::getCurrentSave).thenReturn(save);
                when(save.captureProgress()).thenReturn(savedState);
                Assert.assertTrue(AdventureExit.requestIfNeeded(exits::incrementAndGet));
            }
            dialogs.verify(() -> AdventureExitDialog.show(anyString(), eq(false), any(), any(), any()));
            Assert.assertNotNull(keepPlaying.get());
            keepPlaying.get().run();
            Assert.assertEquals(exits.get(), 0);
        } finally {
            if (keepPlaying.get() != null) keepPlaying.get().run();
            AdventureExit.PROGRESS.checkpoint(System.currentTimeMillis(), Map.of());
            Forge.isMobileAdventureMode = previousAdventureMode;
        }
    }

    @Test
    public void oldSaveIsCleanUntilProgressChanges() {
        AdventureProgress progress = new AdventureProgress();
        Map<String, byte[]> state = new HashMap<>();
        state.put("cards", new byte[]{1});
        Assert.assertFalse(progress.isActive());
        Assert.assertFalse(progress.hasChanges(state));
        progress.checkpoint(1L, state);
        Assert.assertTrue(progress.isActive());
        Assert.assertEquals(progress.getSavedAt(), 1L);
        Assert.assertFalse(progress.hasChanges(state));
        // A newly acquired card is protected even one instant after the checkpoint.
        state.get("cards")[0] = 2;
        Assert.assertTrue(progress.hasChanges(state));
        progress.checkpoint(2L, state);
        Assert.assertFalse(progress.hasChanges(state));
    }

    @Test
    public void comparesAddedRemovedAndChangedPersistentFields() {
        AdventureProgress progress = new AdventureProgress();
        progress.checkpoint(10L, Map.of("deck", new byte[]{1}, "quest", new byte[]{2}));
        Assert.assertFalse(progress.hasChanges(Map.of("quest", new byte[]{2}, "deck", new byte[]{1})));
        Assert.assertTrue(progress.hasChanges(Map.of("deck", new byte[]{1})));
        Assert.assertTrue(progress.hasChanges(Map.of("deck", new byte[]{3}, "quest", new byte[]{2})));
        Assert.assertTrue(progress.hasChanges(Map.of("deck", new byte[]{1}, "quest", new byte[]{2}, "gold", new byte[]{5})));
    }

    @Test
    public void explorationAndNewGameAreProtectedWithoutSerializingWorldEveryFrame() {
        AdventureProgress progress = new AdventureProgress();
        progress.markChanged();
        Assert.assertFalse(progress.isActive());
        progress.startNew();
        Assert.assertEquals(progress.getSavedAt(), 0L);
        Assert.assertTrue(progress.hasChanges(Map.of()));
        progress.checkpoint(10L, Map.of());
        Assert.assertFalse(progress.hasChanges(Map.of()));
        progress.markChanged();
        Assert.assertTrue(progress.hasChanges(Map.of()));
        progress.checkpoint(20L, Map.of());
        Assert.assertFalse(progress.hasChanges(Map.of()));
    }

    @Test
    public void closeRequestsAreDeduplicatedAndFailedSaveDoesNotExit() {
        AdventureExitRequest request = new AdventureExitRequest();
        AtomicInteger exits = new AtomicInteger();
        Assert.assertTrue(request.begin(exits::incrementAndGet));
        Assert.assertFalse(request.begin(() -> exits.addAndGet(100)));
        request.saved(false);
        Assert.assertTrue(request.isPending());
        Assert.assertEquals(exits.get(), 0);
        request.saved(true);
        Assert.assertFalse(request.isPending());
        Assert.assertEquals(exits.get(), 1);
        request.proceed();
        Assert.assertEquals(exits.get(), 1);
    }

    @Test
    public void keepPlayingCancelsTheContinuationAndDiscardAllowsExactlyOneAction() {
        AdventureExitRequest request = new AdventureExitRequest();
        AtomicInteger exits = new AtomicInteger();
        request.begin(exits::incrementAndGet);
        request.cancel();
        request.saved(true);
        Assert.assertEquals(exits.get(), 0);
        Assert.assertTrue(request.begin(exits::incrementAndGet));
        request.proceed();
        request.proceed();
        Assert.assertEquals(exits.get(), 1);
    }

    @Test
    public void failedSerializationPreservesOriginalSaveAndWarningState() throws Exception {
        Path directory = Files.createTempDirectory("forge-adventure-save-test");
        Path save = directory.resolve("1_save_slot.sav");
        byte[] original = {1, 2, 3};
        Files.write(save, original);
        AdventureProgress progress = new AdventureProgress();
        progress.checkpoint(1L, Map.of());
        progress.markChanged();
        try {
            Assert.expectThrows(IOException.class, () -> AdventureSaveFile.write(save, stream -> {
                stream.write(new byte[]{4, 5});
                throw new IOException("Simulated failure after writing part of the replacement");
            }));
            Assert.assertEquals(Files.readAllBytes(save), original);
            Assert.assertTrue(progress.hasChanges(Map.of()));
            Assert.assertEquals(progress.getSavedAt(), 1L);
            try (var files = Files.list(directory)) {
                Assert.assertEquals(files.count(), 1L);
            }
            AdventureSaveFile.write(save, stream -> stream.write(new byte[]{6, 7}));
            Assert.assertEquals(Files.readAllBytes(save), new byte[]{6, 7});
        } finally {
            Files.deleteIfExists(save);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void failedFirstSaveLeavesNoUnusableSlotBehind() throws Exception {
        Path directory = Files.createTempDirectory("forge-adventure-new-save-test");
        Path save = directory.resolve("1_save_slot.sav");
        try {
            Assert.expectThrows(IOException.class, () -> AdventureSaveFile.write(save, stream -> {
                stream.write(1);
                throw new IOException("Simulated first save failure");
            }));
            Assert.assertFalse(Files.exists(save));
            try (var files = Files.list(directory)) {
                Assert.assertEquals(files.count(), 0L);
            }
        } finally {
            Files.deleteIfExists(save);
            Files.deleteIfExists(directory);
        }
    }
}
