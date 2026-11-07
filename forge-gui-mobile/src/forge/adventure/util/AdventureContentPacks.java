package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.adventure.data.EnemyData;
import forge.adventure.data.AdventureQuestData;
import forge.adventure.data.PointOfInterestData;
import forge.StaticData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dependency-checked, plane-scoped Adventure additions. Never writes base resources. */
public final class AdventureContentPacks {
    private static AdventureContentPacks current;
    interface Dependencies {
        Path resource(String name) throws IOException;
        boolean card(String name, String edition, boolean custom);
        boolean token(String name);
        default int resolvedMainCount(Path path) { return -1; }
    }

    private final Dependencies dependencies;
    private final Path resourceRoot;
    private final Path packRoot;
    private final String plane;
    private final Map<String, EnemyData> enemies = new java.util.LinkedHashMap<>();
    private final Map<String, PointOfInterestData> points = new java.util.LinkedHashMap<>();
    private final Set<String> enabledEnemies = new java.util.LinkedHashSet<>();
    private final Set<String> enabledPoints = new java.util.LinkedHashSet<>();
    private final Map<String, String> decks = new HashMap<>();
    private final List<JsonObject> biomes = new ArrayList<>();
    private final Map<Integer, AdventureQuestData> quests = new java.util.LinkedHashMap<>();
    private final Map<String, String> customEditions = new HashMap<>();
    private final Set<String> requiredEditions = new HashSet<>();
    private final Set<String> ownedDecks = new HashSet<>();
    private final Set<String> enabledDecks = new HashSet<>();
    private final Set<String> ownedMaps = new HashSet<>();
    private final Set<String> enabledMaps = new HashSet<>();

    AdventureContentPacks(Path resourceRoot, String plane, Dependencies dependencies) {
        this.resourceRoot = resourceRoot.toAbsolutePath().normalize();
        this.packRoot = this.resourceRoot.resolve("optional");
        this.plane = plane;
        this.dependencies = dependencies;
        load();
    }

    public static AdventureContentPacks get() {
        if (current == null) {
            Config config = Config.instance();
            current = new AdventureContentPacks(Path.of(config.getCommonFilePath("..")), config.getPlane(), new Dependencies() {
                @Override public Path resource(String name) throws IOException {
                    var handle = config.getFile(name);
                    if (handle == null) throw new IOException("Missing resource: " + name);
                    return handle.file().toPath();
                }
                @Override public boolean card(String name, String edition, boolean custom) {
                    var db = StaticData.instance().getCommonCards();
                    var card = edition == null ? db.getCard(name) : db.getCard(name, edition);
                    return card != null && (!custom || card.getRules().isCustom());
                }
                @Override public boolean token(String name) {
                    return StaticData.instance().getAllTokens().getToken(name) != null;
                }
                @Override public int resolvedMainCount(Path path) {
                    var deck = forge.deck.io.DeckSerializer.fromFile(path.toFile());
                    return deck == null ? 0 : deck.getMain().countAll();
                }
            });
        }
        return current;
    }

    public static void reset() {
        current = null;
    }

    private Path resource(String name) throws IOException {
        Path path = dependencies.resource(name).toRealPath();
        if (!path.startsWith(resourceRoot.toRealPath())) throw new IOException("Resource escapes Adventure root: " + name);
        if (!Files.isRegularFile(path)) throw new IOException("Resource is not a file: " + name);
        return path;
    }

    private static JsonArray array(JsonObject value, String name) {
        return value.has(name) ? value.getAsJsonArray(name) : new JsonArray();
    }

    private void validate(JsonObject group) throws IOException {
        for (var item : array(group, "requiredResources")) resource(item.getAsString());
        for (var item : array(group, "requiredCards")) {
            var request = item.getAsJsonObject();
            if (!dependencies.card(request.get("name").getAsString(), request.get("edition").getAsString(), request.get("custom").getAsBoolean())) throw new IOException("Missing custom card: " + request.get("name"));
        }
        for (var token : array(group, "requiredTokens")) {
            if (!dependencies.token(token.getAsString())) throw new IOException("Missing token: " + token);
        }
        for (var item : array(group, "requiredDecks")) {
            var request = item.getAsJsonObject();
            Path deckPath = resource(request.get("path").getAsString());
            boolean main = false;
            int count = 0;
            for (String line : Files.readAllLines(deckPath)) {
                line = line.trim();
                if (line.startsWith("[")) {
                    main = line.equalsIgnoreCase("[main]");
                    continue;
                }
                if (!line.matches("\\d+\\s+.*")) continue;
                String[] pair = line.split("\\s+", 2);
                String[] name = pair[1].split("\\|");
                if (!dependencies.card(name[0], name.length > 1 ? name[1] : null, false)) throw new IOException("Unresolved deck card: " + pair[1]);
                if (main) count = Math.addExact(count, Integer.parseInt(pair[0]));
            }
            if (count != request.get("mainCount").getAsInt()) throw new IOException("Incomplete deck: " + request.get("path"));
            int resolved = dependencies.resolvedMainCount(deckPath);
            if (resolved >= 0 && resolved != count) throw new IOException("Deck loader omitted cards: " + request.get("path"));
        }
    }

    private void load() {
        Path root = packRoot;
        if (!Files.isDirectory(root)) return;
        try (var folders = Files.list(root)) {
            for (Path folder : folders.sorted().toList()) {
                if (!folder.toRealPath().startsWith(resourceRoot.toRealPath())) continue;
                Path manifest = folder.resolve("manifest.json");
                if (!Files.isRegularFile(manifest)) continue;
                try {
                    var data = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
                    if (data.get("schemaVersion").getAsInt() != 1) throw new IOException("Unsupported content schema");
                    boolean supportedPlane = false;
                    for (var name : array(data, "planes")) supportedPlane |= name.getAsString().equals(this.plane);
                    if (!supportedPlane) continue;
                    Set<String> groups = new HashSet<>();
                    for (var entry : array(data, "groups")) {
                        var group = entry.getAsJsonObject();
                        String id = group.get("id").getAsString();
                        if (!groups.add(id)) throw new IOException("Duplicate group: " + id);
                        try {
                            loadGroup(folder, group);
                        } catch (Exception error) {
                            System.err.println("Optional Adventure group " + id + " disabled: " + error.getMessage());
                        }
                    }
                } catch (Exception error) {
                    System.err.println("Optional Adventure content " + manifest + ": " + error.getMessage());
                }
            }
        } catch (IOException error) {
            System.err.println("Optional Adventure content: " + error.getMessage());
        }
    }

    private String definition(Path folder, JsonElement name) throws IOException {
        Path path = folder.resolve(name.getAsString()).normalize().toRealPath();
        if (!path.startsWith(folder.toRealPath())) throw new IOException("Definition escapes pack directory");
        return Files.readString(path);
    }

    private void loadGroup(Path folder, JsonObject group) throws IOException {
        var json = new Json();
        EnemyData[] ownedEnemies = group.has("enemyDefinitions") ? json.fromJson(EnemyData[].class, definition(folder, group.get("enemyDefinitions"))) : new EnemyData[0];
        PointOfInterestData[] ownedPoints = group.has("poiDefinitions") ? json.fromJson(PointOfInterestData[].class, definition(folder, group.get("poiDefinitions"))) : new PointOfInterestData[0];
        String questDefinition = group.has("questDefinitions") ? definition(folder, group.get("questDefinitions")) : "[]";
        var questRecords = JsonParser.parseString(questDefinition).getAsJsonArray();
        Set<Integer> questIds = new HashSet<>(quests.keySet());
        if (!questRecords.isEmpty()) {
            Path baseQuests = dependencies.resource("world/quests.json");
            if (Files.isRegularFile(baseQuests)) {
                for (var quest : JsonParser.parseString(Files.readString(baseQuests)).getAsJsonArray()) {
                    questIds.add(quest.getAsJsonObject().get("id").getAsInt());
                }
            }
        }
        // Validate explicit IDs before getID() can allocate an ID through the quest controller.
        for (var record : questRecords) {
            var quest = record.getAsJsonObject();
            if (!quest.has("id") || quest.get("id").getAsInt() <= 0 || !questIds.add(quest.get("id").getAsInt())) {
                throw new IOException("Duplicate or missing optional quest ID");
            }
        }
        AdventureQuestData[] ownedQuests = json.fromJson(AdventureQuestData[].class, questDefinition);
        for (var quest : ownedQuests) {
            if (!quest.isTemplate || quest.stages.length == 0) {
                throw new IOException("Duplicate or incomplete optional quest: " + quest.getID());
            }
        }
        Set<String> names = new HashSet<>();
        for (var enemy : ownedEnemies) if (enemy.name == null || enemies.containsKey(enemy.name) || !names.add(enemy.name)) throw new IOException("Duplicate or unnamed enemy");
        Set<String> baseEnemies = baseNames("world/enemies.json");
        Set<String> labels = new HashSet<>();
        for (var existing : enemies.values()) {
            labels.add(existing.name);
            labels.add(existing.getName());
        }
        for (var enemy : ownedEnemies) {
            if (enemy.sprite == null || enemy.sprite.isBlank() || enemy.deck == null || enemy.deck.length == 0 || enemy.life <= 0 || !Float.isFinite(enemy.spawnRate) || enemy.spawnRate < 0) throw new IOException("Incomplete enemy definition: " + enemy.name);
            if (baseEnemies.contains(enemy.name) || baseEnemies.contains(enemy.getName()) || labels.contains(enemy.name) || labels.contains(enemy.getName())) throw new IOException("Enemy ID/display collision: " + enemy.name);
            labels.add(enemy.name);
            labels.add(enemy.getName());
        }
        names.clear();
        for (var point : ownedPoints) if (point.name == null || points.containsKey(point.name) || !names.add(point.name)) throw new IOException("Duplicate or unnamed location");
        Set<String> basePoints = baseNames("world/points_of_interest.json");
        for (var point : ownedPoints) {
            if (point.map == null || point.map.isBlank() || point.type == null || point.type.isBlank() || point.spriteAtlas == null || point.sprite == null || point.count < 0) throw new IOException("Incomplete location definition: " + point.name);
            if (basePoints.contains(point.name)) throw new IOException("Location ID collision: " + point.name);
        }
        List<JsonObject> additions = new ArrayList<>();
        if (group.has("biomeAdditions")) {
            for (var biome : JsonParser.parseString(definition(folder, group.get("biomeAdditions"))).getAsJsonArray()) additions.add(biome.getAsJsonObject());
        }
        Map<String, String> overrides = new HashMap<>();
        for (var override : array(group, "deckOverrides")) {
            var value = override.getAsJsonObject();
            String path = value.get("path").getAsString();
            if (decks.containsKey(path) || overrides.putIfAbsent(path, value.get("replacement").getAsString()) != null) throw new IOException("Duplicate deck override: " + path);
        }
        // Keep dormant identities even when dependencies are absent; publish effects atomically below.
        for (var enemy : ownedEnemies) enemies.put(enemy.name, enemy);
        for (var point : ownedPoints) points.put(point.name, point);
        for (var card : array(group, "requiredCards")) {
            var request = card.getAsJsonObject();
            if (!request.get("custom").getAsBoolean()) continue;
            String edition = request.get("edition").getAsString();
            customEditions.put(request.get("name").getAsString(), edition);
            requiredEditions.add(edition);
        }
        Set<String> groupDecks = new HashSet<>();
        for (var deck : array(group, "requiredDecks")) groupDecks.add(deck.getAsJsonObject().get("path").getAsString());
        ownedDecks.addAll(groupDecks);
        Set<String> groupMaps = new HashSet<>();
        for (var entry : array(group, "requiredResources")) {
            if (entry.getAsString().endsWith(".tmx")) groupMaps.add(mapKey(entry.getAsString()));
        }
        for (var point : ownedPoints) groupMaps.add(mapKey(point.map));
        ownedMaps.addAll(groupMaps);
        try {
            validate(group);
            for (var enemy : ownedEnemies) {
                resource(enemy.sprite);
                for (String deck : enemy.deck) if (!groupDecks.contains(deck)) throw new IOException("Enemy deck lacks validation: " + deck);
            }
            for (var point : ownedPoints) {
                resource(point.map);
                resource(point.spriteAtlas);
            }
            for (var biome : additions) {
                resource(biome.get("path").getAsString());
                for (String field : new String[]{"enemies", "pointsOfInterest"}) {
                    for (var name : array(biome, field)) name.getAsString();
                }
            }
            for (var replacement : overrides.values()) {
                if (!groupDecks.contains(replacement)) throw new IOException("Override lacks a validated deck: " + replacement);
            }
        } catch (Exception error) {
            System.err.println("Optional Adventure group " + group.get("id") + " disabled: " + error.getMessage());
            return;
        }
        for (var enemy : ownedEnemies) enabledEnemies.add(enemy.name);
        for (var point : ownedPoints) enabledPoints.add(point.name);
        decks.putAll(overrides);
        enabledDecks.addAll(groupDecks);
        enabledMaps.addAll(groupMaps);
        biomes.addAll(additions);
        for (var quest : ownedQuests) quests.put(quest.getID(), quest);
    }

    private Set<String> baseNames(String logicalPath) throws IOException {
        Path path;
        try {
            path = dependencies.resource(logicalPath);
        } catch (IOException missing) {
            return Set.of();
        }
        if (!Files.isRegularFile(path)) return Set.of();
        var result = new HashSet<String>();
        for (var record : JsonParser.parseString(Files.readString(resource(logicalPath))).getAsJsonArray()) {
            var object = record.getAsJsonObject();
            for (String key : new String[]{"name", "nameOverride"}) {
                if (object.has(key) && !object.get(key).isJsonNull()) result.add(object.get(key).getAsString());
            }
        }
        return result;
    }

    public Array<EnemyData> mergeEnemies(Array<EnemyData> base) {
        var result = new Array<EnemyData>();
        for (var enemy : base) if (!enemies.containsKey(enemy.name)) result.add(enemy);
        for (String id : enabledEnemies) result.add(enemies.get(id));
        return result;
    }

    public Array<AdventureQuestData> mergeQuests(Array<AdventureQuestData> base) {
        var result = new Array<AdventureQuestData>();
        for (var quest : base) if (!quests.containsKey(quest.getID())) result.add(quest);
        for (var quest : quests.values()) result.add(quest);
        return result;
    }

    public Array<PointOfInterestData> mergePoints(Array<PointOfInterestData> base) {
        var result = new Array<PointOfInterestData>();
        for (var point : base) if (!points.containsKey(point.name)) result.add(point);
        for (String id : enabledPoints) result.add(points.get(id));
        return result;
    }

    public EnemyData findEnemy(String name) {
        for (var enemy : enemies.values()) if (enemy.name.equals(name) || enemy.getName().equals(name)) return enemy;
        return null;
    }

    public PointOfInterestData findPoint(String name) { return points.get(name); }
    public boolean available(EnemyData enemy) { return enemy != null && (!enemies.containsKey(enemy.name) || enabledEnemies.contains(enemy.name)); }
    public boolean available(PointOfInterestData point) { return point != null && (!points.containsKey(point.name) || enabledPoints.contains(point.name)); }
    public boolean selectable(EnemyData enemy) { return available(enemy) && !enemies.containsKey(enemy.name); }
    public String resolveDeck(String name) {
        if (ownedDecks.contains(name) && !enabledDecks.contains(name)) throw new IllegalStateException("Optional deck unavailable: " + name);
        return decks.getOrDefault(name, name);
    }

    private static String mapKey(String path) {
        String key = path.replace('\\', '/');
        while (key.startsWith("../common/")) key = key.substring("../common/".length());
        return Path.of(key).normalize().toString().replace('\\', '/');
    }

    public boolean availableMap(String path) {
        String key = mapKey(path);
        return !ownedMaps.contains(key) || enabledMaps.contains(key);
    }

    public boolean inRegion(EnemyData enemy, String[] regionEnemies) {
        if (!enemies.containsKey(enemy.name)) return true;
        EnemyData definition = enemies.get(enemy.name);
        if (!available(enemy) || definition.spawnRate <= 0 || enemy.spawnRate <= 0 || regionEnemies == null) return false;
        return java.util.Arrays.asList(regionEnemies).contains(definition.getName());
    }

    public boolean eligible(EnemyData enemy, String[] regionEnemies, float rank) {
        return enemy != null && (!enemies.containsKey(enemy.name) || inRegion(enemy, regionEnemies) && enemies.get(enemy.name).difficulty <= rank);
    }

    public boolean worldEligible(EnemyData enemy) {
        if (enemy == null) return false;
        if (!enemies.containsKey(enemy.name)) return true;
        var player = forge.adventure.stage.WorldStage.getInstance().getPlayerSprite();
        var world = Current.world();
        int biome = forge.adventure.world.World.highestBiome(world.getBiome((int) (player.getX() / world.getTileSize()), (int) (player.getY() / world.getTileSize())));
        var regions = world.getData().GetBiomes();
        return biome >= 0 && biome < regions.size() && eligible(enemy, regions.get(biome).enemies, Current.player().getStatistic().rank());
    }

    public boolean restorable(EnemyData enemy) {
        if (enemy == null) return false;
        if (!enemies.containsKey(enemy.name)) return true;
        var world = Current.world();
        var player = Current.player();
        int biome = forge.adventure.world.World.highestBiome(world.getBiome((int) (player.getWorldPosX() / world.getTileSize()), (int) (player.getWorldPosY() / world.getTileSize())));
        var regions = world.getData().GetBiomes();
        return biome >= 0 && biome < regions.size() && eligible(enemy, regions.get(biome).enemies, player.getStatistic().rank());
    }

    /** Prevent an unresolved saved POI from reaching World's regeneration fallback. */
    public String missingSavedLocation(SaveFileData world) {
        return missingSavedLocation(world, name -> forge.adventure.data.PointOfInterestData.getPointOfInterest(name) != null);
    }

    String missingSavedLocation(SaveFileData world, java.util.function.Predicate<String> resolvable) {
        var locations = world.readSubData("mapPoiIds");
        if (locations == null) return null;
        for (String key : locations.keySet()) {
            if (!key.matches("mapObjects\\[\\d+\\]\\[\\d+\\]\\[\\d+\\]")) continue;
            String name = locations.readSubData(key).readString("name");
            if (!resolvable.test(name)) return name;
        }
        return null;
    }

    public PointOfInterestData presentation(PointOfInterestData point) {
        if (available(point)) return point;
        var fallback = new PointOfInterestData(point);
        fallback.spriteAtlas = "../common/maps/tileset/buildings.atlas";
        fallback.sprite = "ForestTown";
        return fallback;
    }

    /** Check names before AdventurePlayer resolves card strings and can discard unavailable rules. */
    public String missingSavedCards(SaveFileData player) {
        for (String key : player.keySet()) {
            if (key.matches("newCards|noSellCards|autoSellCards|favoriteCards|unsupportedCards|boosters")) {
                Object value = player.readObject(key);
                if (value instanceof Object[] entries) for (Object entry : entries) {
                    if (entry instanceof forge.item.PaperCard card) {
                        if (unresolved(card.getName(), card.getEdition())) return card.getName();
                    } else if (entry instanceof forge.deck.Deck deck) {
                        for (var card : deck.getAllCardsInASinglePool(true, true)) {
                            if (unresolved(card.getKey().getName(), card.getKey().getEdition())) return card.getKey().getName();
                        }
                    }
                }
                continue;
            }
            if (!key.matches("cards|deckCards|sideBoardCards(_\\d+)?|attractionDeckCards(_\\d+)?|contraptionDeckCards(_\\d+)?|commanderCards(_\\d+)?|deck_\\d+")) continue;
            Object value = player.readObject(key);
            if (!(value instanceof String[] entries)) continue;
            for (String entry : entries) {
                String[] request = entry.replaceFirst("^\\d+\\s+", "").split("\\|");
                String name = request[0].trim();
                if (unresolved(name, request.length > 1 ? request[1] : null)) return name;
            }
        }
        return null;
    }

    private boolean unresolved(String name, String edition) {
        if (edition == null) edition = customEditions.get(name);
        return edition != null && requiredEditions.contains(edition) && !dependencies.card(name, edition, true);
    }

    public String[] additions(String path, String field, String[] original) {
        var names = new java.util.LinkedHashSet<String>();
        if (original != null) java.util.Collections.addAll(names, original);
        for (var biome : biomes) if (biome.get("path").getAsString().equals(path)) {
            for (var name : array(biome, field)) names.add(name.getAsString());
        }
        return names.toArray(new String[0]);
    }

}
