package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import forge.adventure.data.EnemyData;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;

@Test(groups = {"UnitTest", "fast"})
public class AdventureContentAvailabilityTest {
    @Test public void savedQuestSnapshotCannotBypassCurrentUnlockOrRegionLabel() throws IOException {
        var packs = new AdventureContentPackTest.Fixture().load();
        var snapshot = new EnemyData(packs.findEnemy("Optional Enemy"));
        snapshot.difficulty = 0.1f;
        snapshot.nameOverride = "Old display name";
        Assert.assertFalse(packs.eligible(snapshot, new String[]{"Optional Display"}, 1));
        Assert.assertTrue(packs.eligible(snapshot, new String[]{"Optional Display"}, 2));
        snapshot.spawnRate = 0;
        Assert.assertFalse(packs.eligible(snapshot, new String[]{"Optional Display"}, 10));
    }
    @Test public void enemyIdCannotAliasAnotherGroupsDisplayName() throws IOException {
        var fixture = new AdventureContentPackTest.Fixture();
        fixture.write("optional/sample/second.json", "[{name:'Optional Display',nameOverride:'Other',sprite:'sprites/fixture.atlas',life:20,deck:['decks/optional.dck']}]");
        fixture.manifest("Shandalar", ",{id:'other',enemyDefinitions:'second.json',requiredDecks:[{path:'decks/optional.dck',mainCount:2}]}");
        var packs = fixture.load();
        Assert.assertEquals(packs.mergeEnemies(new Array<EnemyData>()).size, 1);
        Assert.assertNull(packs.findEnemy("Other"));
    }

    @Test public void requiredDirectoryCannotStandInForMissingFile() throws IOException {
        var fixture = new AdventureContentPackTest.Fixture();
        var path = fixture.root.resolve("optional/sample/manifest.json");
        Files.writeString(path, Files.readString(path).replace("requiredCards:", "requiredResources:['maps'],requiredCards:"));
        Assert.assertEquals(fixture.load().mergeEnemies(new Array<EnemyData>()).size, 0);
    }
    @Test public void malformedLocationDoesNotPublishUnsafeDormantMetadata() throws IOException {
        var fixture = new AdventureContentPackTest.Fixture();
        fixture.write("optional/sample/points.json", "[{name:'Optional Place',count:1}]");
        var packs = fixture.load();
        Assert.assertNull(packs.findPoint("Optional Place"));
        Assert.assertEquals(packs.mergeEnemies(new Array<EnemyData>()).size, 0);
    }
    @Test public void optionalDefinitionsCannotReplaceNativeIdsOrDisplayNames() throws IOException {
        var fixture = new AdventureContentPackTest.Fixture();
        fixture.write("common/world/enemies.json", "[{name:'Native Enemy',nameOverride:'Optional Display'}]");
        var packs = fixture.load();
        var base = new Array<EnemyData>(); var nativeEnemy = new EnemyData();
        nativeEnemy.name = "Native Enemy"; base.add(nativeEnemy);
        Assert.assertEquals(packs.mergeEnemies(base).size, 1);
        Assert.assertSame(packs.mergeEnemies(base).first(), nativeEnemy);
        Assert.assertNull(packs.findEnemy("Optional Enemy"));
    }

    @Test public void malformedBiomeCannotPublishEarlierEffects() throws IOException {
        var fixture = new AdventureContentPackTest.Fixture();
        fixture.write("optional/sample/biomes.json", "[{enemies:['Optional Display']}]");
        var packs = fixture.load();
        Assert.assertEquals(packs.mergeEnemies(new Array<EnemyData>()).size, 0);
        Assert.assertEquals(packs.resolveDeck("decks/base.dck"), "decks/base.dck");
    }
    @Test public void nativeEnemiesKeepTheirExistingSelectionRules() throws IOException {
        var fixture = new AdventureContentPackTest.Fixture(); fixture.cards.clear();
        var packs = fixture.load(); var nativeEnemy = new EnemyData(); nativeEnemy.name = "Native";
        Assert.assertTrue(packs.available(nativeEnemy));
        Assert.assertTrue(packs.selectable(nativeEnemy));
        Assert.assertTrue(packs.eligible(nativeEnemy, new String[0], 0));
        Assert.assertFalse(packs.available((EnemyData) null));
    }

    @Test public void directMapAndDeckRequestsCannotBypassMissingDependencies() throws IOException {
        var fixture = new AdventureContentPackTest.Fixture(); fixture.cards.clear();
        var packs = fixture.load();
        Assert.assertFalse(packs.availableMap("maps/optional.tmx"));
        Assert.assertFalse(packs.availableMap("../common/maps/optional.tmx"));
        Assert.assertTrue(packs.availableMap("maps/native.tmx"));
        Assert.expectThrows(IllegalStateException.class, () -> packs.resolveDeck("decks/optional.dck"));
        fixture.cards.add("Optional Wizard");
        Assert.assertTrue(fixture.load().availableMap("maps/optional.tmx"));
    }

    @Test public void requiredResourceMustStayInsideAdventureRoot() throws IOException {
        var fixture = new AdventureContentPackTest.Fixture();
        var outside = Files.createTempFile(fixture.root.getParent(), "outside-pack-", ".txt");
        try {
            fixture.write("optional/sample/manifest.json", "{schemaVersion:1,planes:[Shandalar],groups:[{id:escape,requiredResources:['../../" + outside.getFileName() + "'],deckOverrides:[{path:'decks/base.dck',replacement:'decks/optional.dck'}]}]}");
            Assert.assertEquals(fixture.load().resolveDeck("decks/base.dck"), "decks/base.dck");
        } finally {
            Files.delete(outside);
        }
    }

    @Test public void reverseFaceDependencyIsNotSatisfiedByFrontAlone() throws IOException {
        var fixture = new AdventureContentPackTest.Fixture();
        var manifest = fixture.root.resolve("optional/sample/manifest.json");
        String text = Files.readString(manifest).replace("name: \"Optional Wizard\", edition", "name: \"Missing Face\", edition");
        Files.writeString(manifest, text);
        Assert.assertEquals(fixture.load().mergeEnemies(new Array<EnemyData>()).size, 0);
        fixture.cards.add("Missing Face");
        Assert.assertEquals(fixture.load().mergeEnemies(new Array<EnemyData>()).size, 1);
    }

    @Test public void unknownSchemaAndDuplicateOwnershipFailClosed() throws IOException {
        var fixture = new AdventureContentPackTest.Fixture();
        fixture.manifest("Shandalar", ",{id:'duplicate',enemyDefinitions:'enemies.json'}");
        Assert.assertEquals(fixture.load().mergeEnemies(new Array<EnemyData>()).size, 1);
        var manifest = fixture.root.resolve("optional/sample/manifest.json");
        Files.writeString(manifest, Files.readString(manifest).replace("schemaVersion: 1", "schemaVersion: 999"));
        Assert.assertEquals(fixture.load().mergeEnemies(new Array<EnemyData>()).size, 0);
    }
}
