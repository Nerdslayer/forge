package forge.adventure.data;

import forge.adventure.util.AdventureContentPacks;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;

import java.io.Serializable;
import java.util.Set;

/**
 * Data class that will be used to read Json configuration files
 * BiomeData
 * contains the information for the point of interests like towns and dungeons
 */
public class PointOfInterestData implements Serializable {
    private static final long serialVersionUID = -986253670258331930L;
    public String name;
    public String type;
    public int count;
    public String spriteAtlas;
    public String sprite;
    public int minimapIconSize; // Positive: override the ordinary marker at this pixel size.
    public String minimapIcon; // Optional dedicated PNG; otherwise use the overworld sprite.
    public String map;
    public String battleBackground;
    public float radiusFactor;
    public float offsetX=0f;
    public float offsetY=0f;
    public boolean active = true;
    public String[] questTags = new String[0];
    public DialogData.ActionData.QuestFlag[] questFlagsToActivate = new DialogData.ActionData.QuestFlag[0];
    public String displayName;




    private static Array<PointOfInterestData> pointOfInterestList;
    private static String pointSource;
    private static AdventureContentPacks pointContent;
    public static Array<PointOfInterestData> getAllPointOfInterest() {
        String source = Config.instance().getFile(Paths.POINTS_OF_INTEREST).path();
        if (pointOfInterestList == null || !source.equals(pointSource) || pointContent != AdventureContentPacks.get()) {
            pointContent = AdventureContentPacks.get();
            pointSource = source;
            Json json = new Json();
            FileHandle handle = Config.instance().getFile(Paths.POINTS_OF_INTEREST);
            if (handle.exists()) {
                pointOfInterestList = json.fromJson(Array.class, PointOfInterestData.class, handle);
            }

        }
        return AdventureContentPacks.get().mergePoints(pointOfInterestList);
    }
    public static PointOfInterestData getPointOfInterest(String name) {
        PointOfInterestData owned = AdventureContentPacks.get().findPoint(name);
        if (owned != null) return owned;
        for(PointOfInterestData data: new Array.ArrayIterator<>(getAllPointOfInterest())){
            if(data.name.equals(name)) return data;
        }
        return null;
    }
    public PointOfInterestData()
    {

    }
    public PointOfInterestData(PointOfInterestData other)
    {
        name=other.name;
        type=other.type;
        count=other.count;
        spriteAtlas=other.spriteAtlas;
        sprite=other.sprite;
        minimapIconSize=other.minimapIconSize;
        minimapIcon=other.minimapIcon;
        map=other.map;
        battleBackground=other.battleBackground;
        radiusFactor=other.radiusFactor;
        offsetX=other.offsetX;
        offsetY=other.offsetY;
        active=other.active;
        questTags = other.questTags.clone();
        displayName= other.displayName;
        questFlagsToActivate = other.questFlagsToActivate;
    }

    public String getDisplayName() {
        if (displayName == null || displayName.isEmpty()) {
            return name!=null?name:"";
        }
        return displayName;
    }

    private static final Set<String> DUNGEON_TYPES = Set.of("cave", "dungeon", "sidebosseasy", "sidebossmoderate", "sidebosshard");
    public boolean isDungeon() {
        return type != null && DUNGEON_TYPES.contains(type.toLowerCase());
    }
}
