package forge.adventure.world;

import com.badlogic.gdx.utils.TimeUtils;
import forge.Forge;
import com.badlogic.gdx.Gdx;
import forge.OverlayText;
import forge.adventure.data.DifficultyData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.MapViewScene;
import forge.adventure.scene.SaveLoadScene;
import forge.adventure.shop.ShopCatalog;
import forge.adventure.stage.PointOfInterestMapSprite;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.*;
import forge.card.CardEdition;
import forge.card.ColorSet;
import forge.deck.Deck;
import forge.localinstance.properties.ForgeConstants;
import forge.player.GamePlayerUtil;

import java.io.*;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Paths;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Represents everything that will be saved, like the player and the world.
 */
public class WorldSave {

    static final public int AUTO_SAVE_SLOT = -1;
    static final public int QUICK_SAVE_SLOT = -2;
    static final public int INVALID_SAVE_SLOT = -3;
    static final WorldSave currentSave = new WorldSave();
    public WorldSaveHeader header = new WorldSaveHeader();
    private final AdventurePlayer player = new AdventurePlayer();
    private final World world = new World();
    private final PointOfInterestChanges.Map pointOfInterestChanges = new PointOfInterestChanges.Map();
    private final ShopCatalog shopCatalog = new ShopCatalog();


    private final SignalList onLoadList = new SignalList();
    private static long lastPreviewTimestamp = 0L;
    private static final long COOLDOWN_WINDOW_MS = 800L;
    private static boolean firstCapture = true;

    public final World getWorld() {
        return world;
    }

    public AdventurePlayer getPlayer() {
        return player;
    }

    public ShopCatalog getShopCatalog() {
        return shopCatalog;
    }

    public void onLoad(Runnable run) {
        onLoadList.add(run);
    }

    public PointOfInterestChanges getPointOfInterestChanges(String id) {
        if (id == null) { // fallback
            return new PointOfInterestChanges();
        }

        PointOfInterestChanges changes = pointOfInterestChanges.get(id);
        if (changes == null) {
            changes = new PointOfInterestChanges();
            pointOfInterestChanges.put(id, changes);
        }

        return changes;
    }

    static public boolean load(int currentSlot) {
        JSONStringLoader.clearCache();
        CardUtil.clearPriceCache();
        Forge.getLocalizer().loadAdventureBundle(Config.instance().getPlanePath(Config.instance().getSettingData().plane) + "languages/");

        Forge.invokeWorldSave = true; // This is for dispose method check
        String fileName = WorldSave.getSaveFile(currentSlot);
        if (!new File(fileName).exists())
            return false;
        new File(getSaveDir()).mkdirs();
        try {
            try (FileInputStream fos = new FileInputStream(fileName);
                 InflaterInputStream inf = new InflaterInputStream(fos);
                 ObjectInputStream oos = new ObjectInputStream(inf)) {
                currentSave.header = (WorldSaveHeader) oos.readObject();
                SaveFileData mainData = (SaveFileData) oos.readObject();
                currentSave.player.load(mainData.readSubData("player"));
                GamePlayerUtil.getGuiPlayer().setName(currentSave.player.getName());
                try {
                    currentSave.world.load(mainData.readSubData("world"));
                    currentSave.pointOfInterestChanges.load(mainData.readSubData("pointOfInterestChanges"));
                    currentSave.shopCatalog.load(mainData.readSubData("shopCatalog"));
                    WorldStage.getInstance().load(mainData.readSubData("worldStage"));

                } catch (Exception e) {
                    System.err.println("Generating New World");
                    if (!currentSave.world.generateNew(0))
                        return false;
                }

                currentSave.onLoadList.emit();

            }
        } catch (ClassNotFoundException | IOException e) {
            e.printStackTrace();
            return false;
        }
        try {
            AdventureExit.PROGRESS.checkpoint(currentSave.header.saveDate.getTime(), currentSave.captureProgress());
        } catch (RuntimeException e) {
            // Comparison failures should warn conservatively, without preventing a usable save from loading.
            e.printStackTrace();
            AdventureExit.PROGRESS.checkpoint(currentSave.header.saveDate.getTime(), new HashMap<>());
            AdventureExit.PROGRESS.markChanged();
        }
        return true;
    }

    public static boolean isSafeFile(String name) {
        return filenameToSlot(name) != INVALID_SAVE_SLOT;
    }

    static public int filenameToSlot(String name) {
        if (name.equals("auto_save.sav"))
            return AUTO_SAVE_SLOT;
        if (name.equals("quick_save.sav"))
            return QUICK_SAVE_SLOT;
        if (!name.contains("_") || !name.endsWith(".sav"))
            return INVALID_SAVE_SLOT;
        return Integer.parseInt(name.split("_")[0]);
    }

    static public String filename(int slot) {
        if (slot == AUTO_SAVE_SLOT)
            return "auto_save.sav";
        if (slot == QUICK_SAVE_SLOT)
            return "quick_save.sav";
        return slot + "_save_slot.sav";
    }

    public static String getSaveDir() {
        return ForgeConstants.USER_ADVENTURE_DIR + Config.instance().getPlane();
    }

    public static String getSaveFile(int slot) {
        return ForgeConstants.USER_ADVENTURE_DIR + Config.instance().getPlane() + File.separator + filename(slot);
    }

    public static WorldSave getCurrentSave() {
        return currentSave;
    }

    public static WorldSave generateNewWorld(String name, boolean male, int race, int avatarIndex, ColorSet startingColorIdentity, DifficultyData diff, AdventureModes mode, int customDeckIndex, CardEdition starterEdition, long seed) {
        Forge.getLocalizer().loadAdventureBundle(Config.instance().getPlanePath(Config.instance().getSettingData().plane) + "languages/");
        currentSave.world.generateNew(seed);
        currentSave.pointOfInterestChanges.clear();
        currentSave.shopCatalog.clear();
        boolean chaos = mode == AdventureModes.Chaos;
        boolean custom = mode == AdventureModes.Custom;

        Deck starterDeck = Config.instance().starterDeck(startingColorIdentity, diff, mode, customDeckIndex, starterEdition);
        currentSave.player.create(name, starterDeck, male, race, avatarIndex, chaos, custom, diff, mode);

        currentSave.player.setWorldPosY((int) (currentSave.world.getData().playerStartPosY * currentSave.world.getData().height * currentSave.world.getTileSize()));
        currentSave.player.setWorldPosX((int) (currentSave.world.getData().playerStartPosX * currentSave.world.getData().width * currentSave.world.getTileSize()));
        currentSave.onLoadList.emit();
        AdventureExit.PROGRESS.startNew();
        return currentSave;
    }

    public boolean autoSave() {
        return save("auto save" + SaveLoadScene.instance().getSaveFileSuffix(), AUTO_SAVE_SLOT);
    }

    public boolean quickSave() {
        return save("quick save" + SaveLoadScene.instance().getSaveFileSuffix(), QUICK_SAVE_SLOT);
    }

    public boolean quickLoad() {
        return load(QUICK_SAVE_SLOT);
    }

    public boolean save(String text, int currentSlot) {
        String oldName = header.name;
        Date oldDate = header.saveDate;
        Map<String, byte[]> savedProgress;
        header.name = text;
        try {
            SaveFileData player = currentSave.player.save();
            SaveFileData world = currentSave.world.save();
            SaveFileData worldStage = WorldStage.getInstance().save();
            SaveFileData poiChanges = currentSave.pointOfInterestChanges.save();
            SaveFileData shopCatalog = currentSave.shopCatalog.save();
            String message = getExceptionMessage(player, world, worldStage, poiChanges, shopCatalog);
            if (!message.isEmpty()) throw new IOException(message);

            SaveFileData mainData = new SaveFileData();
            mainData.store("player", player);
            mainData.store("world", world);
            mainData.store("worldStage", worldStage);
            mainData.store("pointOfInterestChanges", poiChanges);
            mainData.store("shopCatalog", shopCatalog);
            message = getExceptionMessage(mainData);
            if (!message.isEmpty()) throw new IOException(message);
            savedProgress = progressData(player, poiChanges, shopCatalog);

            header.saveDate = new Date();
            AdventureSaveFile.write(Paths.get(getSaveFile(currentSlot)), stream -> {
                try (ObjectOutputStream oos = new ObjectOutputStream(new DeflaterOutputStream(stream))) {
                    oos.writeObject(header);
                    oos.writeObject(mainData);
                }
            });
        } catch (IOException | RuntimeException e) {
            header.name = oldName;
            header.saveDate = oldDate;
            AdventureExit.PROGRESS.markChanged();
            e.printStackTrace();
            finish(Forge.getLocalizer().getMessage("lblAdventureSaveFailed"));
            return false;
        }

        AdventureExit.PROGRESS.checkpoint(header.saveDate.getTime(), savedProgress);
        Config.instance().getSettingData().lastActiveSave = WorldSave.filename(currentSlot);
        Config.instance().saveSettings();
        finish(null);
        return true;
    }

    /** Compare persistent player/location data only on save/load or an attempted departure, never per frame. */
    public Map<String, byte[]> captureProgress() {
        SaveFileData playerData = player.save();
        SaveFileData changes = pointOfInterestChanges.save();
        SaveFileData shops = shopCatalog.save();
        if (!getExceptionMessage(playerData, changes, shops).isEmpty())
            throw new IllegalStateException("Could not compare Adventure progress");
        return progressData(playerData, changes, shops);
    }

    private static Map<String, byte[]> progressData(SaveFileData playerData, SaveFileData changes, SaveFileData shops) {
        Map<String, byte[]> state = new HashMap<>();
        playerData.forEach((key, bytes) -> state.put("player/" + key, bytes));
        changes.forEach((key, bytes) -> state.put("locations/" + key, bytes));
        shops.forEach((key, bytes) -> state.put("shops/" + key, bytes));
        return state;
    }

    private void finish(String errors) {
        if (errors != null)
            announceError(errors);
        Gdx.app.postRunnable(() -> {
            OverlayText.getInstance().update("");
        });
    }

    public String getExceptionMessage(SaveFileData... datas) {
        StringBuilder message = new StringBuilder();

        for (SaveFileData data : datas) {
          String s = data.readString("IOException");
          if (s != null)
              message.append(s).append("\n");
        }

        return message.toString();
    }

    private void announceError(String message) {
        currentSave.player.getCurrentGameStage().setExtraAnnouncement("Error Saving File!\n" + message);
    }

    public void clearChanges() {
        pointOfInterestChanges.clear();
        shopCatalog.clear();
    }

    public void clearBookmarks() {
        for (PointOfInterest poi : currentSave.world.getAllPointOfInterest()) {
            if (poi == null)
                continue;
            PointOfInterestMapSprite mapSprite = WorldStage.getInstance().getMapSprite(poi);
            if (mapSprite != null)
                mapSprite.setBookmarked(false, poi);
            PointOfInterestChanges p = pointOfInterestChanges.get(poi.getID());
            if (p == null)
                continue;
            p.setIsBookmarked(false);
            p.save();
        }
        MapViewScene.instance().clearBookMarks();
    }

    // prevent spam of preview if user repeatedly/accidentally reopen scene that request previews
    public static void requestPreview() {
        final long currentTimestamp = TimeUtils.millis();

        if (firstCapture) {
            firstCapture = false;
            // init once
            currentSave.header.createPreview();
            return;
        }

        // If 800ms has not passed since the last successful generation, skip
        if (currentTimestamp - lastPreviewTimestamp < COOLDOWN_WINDOW_MS) {
            return;
        }

        // Update the timestamp immediately to lock out parallel thread spam on the spot
        lastPreviewTimestamp = currentTimestamp;
        Gdx.app.postRunnable(new Runnable() {
            @Override
            public void run() {
                currentSave.header.createPreview();
            }
        });
    }

    public static void dispose() {
        Forge.safeDispose(currentSave.world);
    }
}
