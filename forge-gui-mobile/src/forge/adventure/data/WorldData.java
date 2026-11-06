package forge.adventure.data;

import forge.adventure.util.AdventureContentPacks;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.SerializationException;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;
import forge.adventure.world.BiomeSprites;
import forge.util.FileUtil;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
/**
 * Data class that will be used to read Json configuration files
 * UIData
 * contains the definition of the world
 */
public class WorldData implements Serializable {

    public int width;
    public int height;
    public float playerStartPosX;
    public float playerStartPosY;
    public float noiseZoomBiome;
    public int tileSize;
    public int miniMapTileSize;
    public BiomeData roadTileset;
    public String biomesSprites;
    public float maxRoadDistance;
    public String[] biomesNames;


    private BiomeSprites sprites;
    private List<BiomeData> biomes;

    private static Array<EnemyData> allEnemies;
    private static String enemySource;
    private static AdventureContentPacks enemyContent;
    private transient AdventureContentPacks biomeContent;
    private static Array<ShopData> shopList;


    public static Array<ShopData> getShopList() {
        if (shopList == null) {
            shopList = new Array<>();
            Json json = new Json();
            FileHandle handle = Config.instance().getFile(Paths.SHOPS);
            if (handle.exists())
            {

                Array readList = json.fromJson(Array.class, ShopData.class, handle);
                shopList = readList;
            }
        }
        return shopList;
    }
    static public Array<EnemyData> getAllEnemies() {
        String source = Config.instance().getFile(Paths.ENEMIES).path();
        if (allEnemies == null || !source.equals(enemySource) || enemyContent != AdventureContentPacks.get()) {
            enemyContent = AdventureContentPacks.get();
            enemySource = source;
            Json json = new Json();
            FileHandle handle = Config.instance().getFile(Paths.ENEMIES);
            if (handle.exists()) {
                Array readList =  json.fromJson(Array.class, EnemyData.class, handle);
                allEnemies = readList;
            }
        }
        return AdventureContentPacks.get().mergeEnemies(allEnemies);
    }

    public static EnemyData getEnemy(String enemy) {
        EnemyData owned = AdventureContentPacks.get().findEnemy(enemy);
        if (owned != null) return owned;
        for(EnemyData data: new Array.ArrayIterator<>(getAllEnemies())) {
            if(data.name.equals(enemy))
                return data;
        }
        for(EnemyData data: new Array.ArrayIterator<>(getAllEnemies())) {
            if(data.getName() != null && data.getName().equals(enemy))
                return data;
        }
        return null;
    }

    public static ArrayList<String> getTownNames(String name) {
        return new ArrayList<String>(FileUtil.readFile(Config.instance().getFilePath("world/town_names_" + name + ".txt")));
    }

    public BiomeSprites GetBiomeSprites() {
        if (sprites == null) {
            Json json = new Json();
            sprites = (json.fromJson(BiomeSprites.class, Config.instance().getFile(biomesSprites)));
        }
        return sprites;
    }

    public List<BiomeData> GetBiomes() {
        if (biomes == null || biomeContent != AdventureContentPacks.get()) {
            biomeContent = AdventureContentPacks.get();
            try
            {
                biomes = new ArrayList<BiomeData>();
                Json json = new Json();
                for (String name : biomesNames) {
                    BiomeData biome = json.fromJson(BiomeData.class, Config.instance().getFile(name));
                    biome.enemies = AdventureContentPacks.get().additions(name, "enemies", biome.enemies);
                    biome.pointsOfInterest = AdventureContentPacks.get().additions(name, "pointsOfInterest", biome.pointsOfInterest);
                    biomes.add(biome);
                }
            }
            catch (SerializationException ex) {
                ex.printStackTrace();
            }
        }
        return biomes;
    }

}