package forge.adventure.util;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;

@Test(groups = {"UnitTest", "fast"})
public class AdventureContentSaveCompatibilityTest {
    @Test public void dormantLocationPreservesItsSavedIdentity() throws IOException {
        var fixture = new AdventureContentPackTest.Fixture(); fixture.cards.clear();
        var packs = fixture.load();
        var world = new SaveFileData(); var locations = new SaveFileData(); var place = new SaveFileData();
        place.store("name", "Optional Place"); place.store("active", true);
        locations.store("mapObjects[0][0][0]", place); world.store("mapPoiIds", locations);
        var before = new HashMap<>(world);
        Assert.assertNull(packs.missingSavedLocation(world, name -> packs.findPoint(name) != null));
        Assert.assertEquals(packs.missingSavedLocation(world, name -> false), "Optional Place");
        Assert.assertTrue(Arrays.equals(before.get("mapPoiIds"), world.get("mapPoiIds")));
        Assert.assertTrue(world.readSubData("mapPoiIds").readSubData("mapObjects[0][0][0]").readBool("active"));
        Assert.assertFalse(packs.available(packs.findPoint("Optional Place")));
        fixture.cards.add("Optional Wizard");
        Assert.assertTrue(fixture.load().available(packs.findPoint("Optional Place")));
    }

    @Test public void everySavedDeckSectionIsCheckedWithoutMutatingItsBytes() throws IOException {
        var fixture = new AdventureContentPackTest.Fixture(); fixture.cards.clear();
        var packs = fixture.load();
        for (String key : new String[]{"cards", "deckCards", "sideBoardCards", "attractionDeckCards", "contraptionDeckCards", "commanderCards", "deck_0", "sideBoardCards_1", "commanderCards_2"}) {
            var player = new SaveFileData();
            player.storeObject(key, new String[]{"2 Optional Wizard|HP|1"});
            byte[] before = player.get(key).clone();
            Assert.assertEquals(packs.missingSavedCards(player), "Optional Wizard", key);
            Assert.assertTrue(Arrays.equals(before, player.get(key)), key);
        }
    }

    @Test public void explicitCustomEditionIsCheckedEvenOutsideCurrentEnemyDecks() throws IOException {
        var packs = new AdventureContentPackTest.Fixture().load(); var player = new SaveFileData();
        player.storeObject("cards", new String[]{"1 Unused Custom Card|HP|3"});
        Assert.assertEquals(packs.missingSavedCards(player), "Unused Custom Card");
        player.storeObject("cards", new String[]{"1 Plains"});
        Assert.assertNull(packs.missingSavedCards(player));
    }
}
