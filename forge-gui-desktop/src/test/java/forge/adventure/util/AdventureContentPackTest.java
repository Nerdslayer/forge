package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import forge.adventure.data.EnemyData;
import forge.adventure.data.PointOfInterestData;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

@Test(groups = {"UnitTest", "fast"})
public class AdventureContentPackTest {
    static final class Fixture implements AdventureContentPacks.Dependencies {
        final Path root = Files.createTempDirectory("adventure-pack-test-");
        final Set<String> cards = new HashSet<>(Set.of("Optional Wizard"));
        boolean tokens = true;
        int resolvedCount = 2;

        Fixture() throws IOException {
            write("common/world/biomes/white.json", "{}");
            write("common/sprites/fixture.atlas", "fixture");
            write("common/maps/optional.tmx", "fixture");
            write("common/decks/optional.dck", "[Main]\n2 Optional Wizard|HP\n");
            write("common/decks/base.dck", "[Main]\n2 Plains\n");
            write("optional/sample/enemies.json", "[{name: 'Optional Enemy', nameOverride: 'Optional Display', sprite:'sprites/fixture.atlas', spawnRate: 0.25, difficulty: 2, life: 20, deck: ['decks/optional.dck']}]".replace('\'', '"'));
            write("optional/sample/points.json", "[{name: 'Optional Place', map: 'maps/optional.tmx', type:'town',spriteAtlas:'sprites/fixture.atlas',sprite:'fixture',count: 1} ]".replace('\'', '"'));
            write("optional/sample/biomes.json", "[{path: 'world/biomes/white.json', enemies: ['Optional Display'], pointsOfInterest: ['Optional Place']}]".replace('\'', '"'));
            manifest("Shandalar", "");
        }

        void write(String name, String value) throws IOException {
            Path path = root.resolve(name);
            Files.createDirectories(path.getParent());
            Files.writeString(path, value);
            path.toFile().deleteOnExit();
        }

        void manifest(String plane, String extra) throws IOException {
            write("optional/sample/manifest.json", ("{schemaVersion: 1, id: 'sample', planes: ['" + plane + "'], groups: [{id: 'core', requiredCards: [{name: 'Optional Wizard', edition: 'HP', custom: true}], requiredTokens: ['optional_token'], requiredDecks: [{path: 'decks/optional.dck', mainCount: 2}], enemyDefinitions: 'enemies.json', poiDefinitions: 'points.json', biomeAdditions: 'biomes.json', deckOverrides: [{path: 'decks/base.dck', replacement: 'decks/optional.dck'}]}" + extra + "]}").replace('\'', '"'));
        }

        AdventureContentPacks load() {
            return new AdventureContentPacks(root, "Shandalar", this);
        }

        @Override public Path resource(String name) { return root.resolve("common").resolve(name); }
        @Override public boolean card(String name, String edition, boolean custom) { return cards.contains(name) || !custom && name.equals("Plains"); }
        @Override public boolean token(String name) { return tokens; }
        @Override public int resolvedMainCount(Path path) { return resolvedCount; }
    }

    @Test public void completePackAddsContentWithoutMutatingBase() throws IOException {
        var fixture = new Fixture();
        var packs = fixture.load();
        var base = new Array<EnemyData>();
        var nativeEnemy = new EnemyData(); nativeEnemy.name = "Native"; base.add(nativeEnemy);
        var merged = packs.mergeEnemies(base);
        Assert.assertEquals(base.size, 1);
        Assert.assertEquals(merged.size, 2);
        Assert.assertEquals(packs.mergeEnemies(merged).size, 2);
        Assert.assertSame(merged.first(), nativeEnemy);
        Assert.assertEquals(packs.mergePoints(new Array<PointOfInterestData>()).size, 1);
        Assert.assertEquals(packs.resolveDeck("decks/base.dck"), "decks/optional.dck");
        Assert.assertEquals(packs.resolveDeck("decks/unrelated.dck"), "decks/unrelated.dck");
    }

    @Test public void missingCardDisablesGroupButKeepsDormantIdentities() throws IOException {
        var fixture = new Fixture(); fixture.cards.clear();
        var packs = fixture.load();
        Assert.assertEquals(packs.mergeEnemies(new Array<EnemyData>()).size, 0);
        Assert.assertEquals(packs.mergePoints(new Array<PointOfInterestData>()).size, 0);
        Assert.assertNotNull(packs.findEnemy("Optional Display"));
        Assert.assertFalse(packs.available(packs.findPoint("Optional Place")));
        Assert.assertEquals(packs.resolveDeck("decks/base.dck"), "decks/base.dck");
        Assert.expectThrows(IllegalStateException.class, () -> packs.resolveDeck("decks/optional.dck"));
        Assert.assertEquals(packs.presentation(packs.findPoint("Optional Place")).sprite, "ForestTown");
        Assert.assertEquals(packs.findPoint("Optional Place").map, "maps/optional.tmx");
    }

    @Test public void missingTokenOrShortenedDeckCannotPartiallyActivate() throws IOException {
        var fixture = new Fixture(); fixture.tokens = false;
        Assert.assertEquals(fixture.load().mergeEnemies(new Array<EnemyData>()).size, 0);
        fixture.tokens = true;
        fixture.write("common/decks/optional.dck", "[Main]\n1 Optional Wizard|HP\n");
        Assert.assertEquals(fixture.load().mergeEnemies(new Array<EnemyData>()).size, 0);
        Files.delete(fixture.root.resolve("common/decks/optional.dck"));
        Assert.assertEquals(fixture.load().mergeEnemies(new Array<EnemyData>()).size, 0);
    }

    @Test public void permissiveDeckLoaderCannotSilentlyDropRequests() throws IOException {
        var fixture = new Fixture(); fixture.resolvedCount = 1;
        Assert.assertEquals(fixture.load().mergeEnemies(new Array<EnemyData>()).size, 0);
    }

    @Test public void planeAndRegionAndRankAreIndependent() throws IOException {
        var fixture = new Fixture();
        fixture.manifest("Other Plane", "");
        Assert.assertEquals(fixture.load().mergeEnemies(new Array<EnemyData>()).size, 0);
        fixture.manifest("Shandalar", "");
        var packs = fixture.load(); var enemy = packs.findEnemy("Optional Enemy");
        Assert.assertFalse(packs.eligible(enemy, new String[]{"Optional Display"}, 1));
        Assert.assertTrue(packs.eligible(enemy, new String[]{"Optional Display"}, 2));
        Assert.assertFalse(packs.eligible(enemy, new String[]{"Other"}, 10));
        Assert.assertFalse(packs.selectable(enemy));
        enemy.spawnRate = 0;
        Assert.assertFalse(packs.eligible(enemy, new String[]{"Optional Display"}, 10));
    }

    @Test public void additionsAreIdempotentAndPreserveNativeMembership() throws IOException {
        var packs = new Fixture().load();
        var first = packs.additions("world/biomes/white.json", "enemies", new String[]{"Native"});
        Assert.assertEquals(first, new String[]{"Native", "Optional Display"});
        Assert.assertEquals(packs.additions("world/biomes/white.json", "enemies", first), first);
        Assert.assertEquals(packs.additions("world/biomes/green.json", "enemies", new String[]{"Native"}), new String[]{"Native"});
    }

    @Test public void malformedGroupDoesNotPreventIndependentValidGroup() throws IOException {
        var fixture = new Fixture();
        fixture.manifest("Shandalar", ",{id: 'bad', enemyDefinitions: 'missing.json'}");
        Assert.assertEquals(fixture.load().mergeEnemies(new Array<EnemyData>()).size, 1);
        fixture.write("optional/sample/biomes.json", "not JSON");
        Assert.assertEquals(fixture.load().mergeEnemies(new Array<EnemyData>()).size, 0);
    }

    @Test public void resourceTraversalCannotEnablePack() throws IOException {
        var fixture = new Fixture();
        fixture.write("optional/sample/manifest.json", "{schemaVersion:1,planes:[Shandalar],groups:[{id:bad,requiredResources:['../../outside']}]}".replace('\'', '"'));
        Assert.assertEquals(fixture.load().mergeEnemies(new Array<EnemyData>()).size, 0);
    }

    @Test public void optionalQuestsActivateAtomicallyAndPreserveNativeQuests() throws IOException {
        try (var forgeMock = org.mockito.Mockito.mockStatic(forge.Forge.class)) {
            forgeMock.when(forge.Forge::getLocalizer).thenReturn(org.mockito.Mockito.mock(forge.util.Localizer.class));
            var fixture = new Fixture();
            fixture.write("optional/sample/quests.json", "[{id:9001,isTemplate:true,oneTime:true,requiredPOI:\"Optional Place\",stages:[{id:1,objective:QuestFlag,mapFlag:done,mapFlagValue:1}]}]");
            var manifest = fixture.root.resolve("optional/sample/manifest.json");
            Files.writeString(manifest, Files.readString(manifest).replace("enemyDefinitions:", "questDefinitions:\"quests.json\",enemyDefinitions:"));
            var nativeQuests = new Array<forge.adventure.data.AdventureQuestData>();
            var nativeQuest = new forge.adventure.data.AdventureQuestData();
            nativeQuests.add(nativeQuest);
            var packs = fixture.load();
            var merged = packs.mergeQuests(nativeQuests);
            Assert.assertEquals(merged.size, 2);
            Assert.assertEquals(nativeQuests.size, 1);
            Assert.assertSame(merged.first(), nativeQuest);
            Assert.assertEquals(packs.mergeQuests(merged).size, 2);
            Assert.assertEquals(merged.peek().requiredPOI, "Optional Place");
            fixture.cards.clear();
            Assert.assertEquals(fixture.load().mergeQuests(nativeQuests).size, 1);
            fixture.cards.add("Optional Wizard");
            fixture.write("common/world/quests.json", "[{id:9001}]");
            Assert.assertEquals(fixture.load().mergeQuests(nativeQuests).size, 1);
            Assert.assertEquals(fixture.load().mergeEnemies(new Array<EnemyData>()).size, 0);
            fixture.write("common/world/quests.json", "[]");
            fixture.write("optional/sample/quests.json", "[{id:0,isTemplate:true,stages:[{id:1,objective:QuestFlag}]}]");
            Assert.assertEquals(fixture.load().mergeEnemies(new Array<EnemyData>()).size, 0);
        }
    }

    @Test public void missingOwnedCardsAreDetectedBeforePoolLoading() throws IOException {
        var fixture = new Fixture(); fixture.cards.clear();
        var packs = fixture.load();
        var player = new SaveFileData(); player.storeObject("cards", new String[]{"2 Optional Wizard|HP|1"});
        Assert.assertEquals(packs.missingSavedCards(player), "Optional Wizard");
        player.storeObject("cards", new String[]{"2 Plains"});
        Assert.assertNull(packs.missingSavedCards(player));
        player.storeObject("deck_2", new String[]{"1 Optional Wizard|HP|1"});
        Assert.assertEquals(packs.missingSavedCards(player), "Optional Wizard");
    }
}
