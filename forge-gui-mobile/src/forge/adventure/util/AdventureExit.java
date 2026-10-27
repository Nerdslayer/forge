package forge.adventure.util;

import forge.Forge;
import forge.adventure.scene.AdventureExitDialog;
import forge.adventure.scene.GameScene;
import forge.adventure.scene.SaveLoadScene;
import forge.adventure.scene.StartScene;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.WorldStage;
import forge.adventure.world.WorldSave;

/** Shared by window close, Exit, restart, and actions that replace the active run. */
public final class AdventureExit {
    public static final AdventureProgress PROGRESS = new AdventureProgress();
    private static final AdventureExitRequest REQUEST = new AdventureExitRequest();

    private AdventureExit() { }

    public static boolean requestIfNeeded(Runnable continuation) {
        if (REQUEST.isPending()) return true;
        // Check the independent tracker first: do not create a WorldSave merely to query it.
        if (!Forge.isMobileAdventureMode || !PROGRESS.isActive()) return false;
        if (Forge.getTransitionScreen() != null) return true; // finish the transition, then retry

        boolean inMap = MapStage.getInstance().isInMap();
        boolean canSave = !inMap && (Forge.getCurrentScene() instanceof GameScene
                || Forge.getCurrentScene() instanceof StartScene || Forge.getCurrentScene() instanceof SaveLoadScene);
        if (canSave) WorldStage.getInstance().getPlayerSprite().storePos();
        if (inMap) PROGRESS.markChanged();
        try {
            if (!PROGRESS.hasChanges(WorldSave.getCurrentSave().captureProgress())) return false;
        } catch (RuntimeException e) {
            // A failed comparison must never authorize discarding a live session.
            PROGRESS.markChanged();
            e.printStackTrace();
        }

        REQUEST.begin(continuation);
        long savedAt = PROGRESS.getSavedAt();
        String message = savedAt == 0 ? text("lblAdventureNeverSaved")
                : Forge.getLocalizer().getMessage("lblAdventureLastSavedMinutes",
                    Math.max(0, (System.currentTimeMillis() - savedAt) / 60000));
        if (savedAt > 0 && System.currentTimeMillis() - savedAt >= 300000)
            message += "\n\n" + text("lblAdventureSaveOverdue");
        message += "\n\n" + text("lblAdventureProgressWillBeLost");
        if (!canSave) message += "\n\n" + text(inMap ? "lblAdventureSaveOnWorldMap" : "lblAdventureFinishBeforeSaving");

        AdventureExitDialog.show(message, canSave, REQUEST::cancel, () -> {
            WorldStage.getInstance().getPlayerSprite().storePos();
            WorldSave.requestPreview();
            SaveLoadScene.instance().saveBeforeContinuing(() -> REQUEST.saved(true), REQUEST::cancel);
        }, REQUEST::proceed);
        return true;
    }

    private static String text(String key) { return Forge.getLocalizer().getMessage(key); }
}
