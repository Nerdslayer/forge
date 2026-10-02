package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.combat.PublicCombatSnapshot;
import forge.ai.combat.PublicCombatReadiness;
import forge.ai.combat.CombatSearchBudget;
import forge.ai.combat.CombatBlockPlanner;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.combat.Combat;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import forge.item.PaperCard;

public class CombatStaticProjectionPreparationTest extends AITest {
    private record Fixture(Game game, Player ai, Player opponent) { }

    @Test
    public void unprojectedInteractingStaticLayersFreezeCurrentCharacteristicsRatherThanVetoCombat() {
        final var f = fixture();
        final Card source = creature(f.opponent(), 4, 4, List.of(
                "S:Mode$ Continuous | Affected$ Creature | AddPower$ 1 | AddToughness$ 1",
                "S:Mode$ Continuous | Affected$ Creature.YouCtrl | AddKeyword$ Flying"));
        final Card blocker = creature(f.ai(), 2, 2, List.of());
        f.game().getAction().checkStaticAbilities();
        final Combat combat = new Combat(f.opponent());
        combat.addAttackerForValidation(source, f.ai());
        final var snapshot = PublicCombatSnapshot.capture(f.ai(), combat);
        Assert.assertTrue(snapshot.unsupportedReasons().isEmpty(), snapshot.unsupportedReasons().toString());
        Assert.assertTrue(snapshot.staticWorlds().supported());
        Assert.assertTrue(snapshot.staticWorlds().providers().isEmpty(), "Never expose a partial provider-loss table");
        Assert.assertFalse(snapshot.ignoredEffects().isEmpty());
        Assert.assertEquals(snapshot.creatureAfterLosses(blocker.getId(), Set.of(source.getId())).toughness(), 3,
                "Frozen current characteristics are an explicit approximation, not a simulated layer removal");
        final var projection = forge.ai.combat.CombatOutcomePredictor.predict(snapshot,
                new forge.ai.combat.CombatAssignment(snapshot.attackersToDefenders(), java.util.Map.of()));
        Assert.assertTrue(projection.supported() && projection.available(), projection.reasons().toString());
        Assert.assertFalse(projection.reasons().isEmpty());
    }

    @Test
    public void snapshotCancellationIncludesCombinedWorldPreparationWithoutPublishingPartialState() {
        final var f = fixture();
        final Card source = creature(f.opponent(), 4, 4, List.of(
                "S:Mode$ Continuous | Affected$ Creature | AddPower$ 1 | AddToughness$ 1"));
        final Card blocker = creature(f.ai(), 2, 2, List.of());
        f.game().getAction().checkStaticAbilities();
        final Combat combat = new Combat(f.opponent());
        combat.addAttackerForValidation(source, f.ai());
        final AtomicInteger total = new AtomicInteger();
        final var expected = PublicCombatSnapshot.capture(f.ai(), combat, () -> { total.incrementAndGet(); return true; }).orElseThrow();
        Assert.assertTrue(expected.staticWorlds().supported());
        Assert.assertEquals(expected, PublicCombatSnapshot.capture(f.ai(), combat));
        final int sentinel = f.game().nextCardId();
        for (int stop = 1; stop <= total.get(); stop++) {
            final int limit = stop;
            final AtomicInteger calls = new AtomicInteger();
            Assert.assertTrue(PublicCombatSnapshot.capture(f.ai(), combat, () -> calls.incrementAndGet() < limit).isEmpty(),
                    "Cancelled capture checkpoint " + stop + " publishes no mechanical/static subtotal");
            Assert.assertEquals(blocker.getNetPower(), 3);
            Assert.assertTrue(combat.getAllBlockers().isEmpty());
        }
        final CombatSearchBudget budget = new CombatSearchBudget(total.get() - 1);
        final var plan = CombatBlockPlanner.plan(f.ai(), combat, budget);
        Assert.assertFalse(plan.applicable());
        Assert.assertTrue(plan.snapshot().isEmpty());
        Assert.assertTrue(plan.search().isEmpty());
        Assert.assertEquals(budget.remaining(), 0);
        Assert.assertTrue(combat.getAllBlockers().isEmpty());
        final AtomicInteger readinessCalls = new AtomicInteger();
        final var readiness = PublicCombatReadiness.capture(f.ai(), expected,
                () -> { readinessCalls.incrementAndGet(); return true; }).orElseThrow();
        Assert.assertEquals(readiness, PublicCombatReadiness.capture(f.ai(), expected));
        for (int stop = 1; stop <= readinessCalls.get(); stop++) {
            final int limit = stop;
            final AtomicInteger calls = new AtomicInteger();
            Assert.assertTrue(PublicCombatReadiness.capture(f.ai(), expected, () -> calls.incrementAndGet() < limit).isEmpty(),
                    "Cancelled readiness checkpoint " + stop + " must not publish partial future eligibility");
        }
        Assert.assertTrue(combat.getAllBlockers().isEmpty());
        Assert.assertEquals(f.game().nextCardId(), sentinel + 1);
    }

    @Test
    public void combinedPositiveAndNegativeProvidersAreRemovedTogetherOnDetachedCopies() {
        final var f = fixture();
        final Card positive = creature(f.ai(), 4, 4, List.of("S:Mode$ Continuous | Affected$ Creature | AddPower$ 1 | AddToughness$ 1"));
        final Card negative = creature(f.opponent(), 4, 4, List.of("S:Mode$ Continuous | Affected$ Creature | AddPower$ -1 | AddToughness$ -1"));
        final Card recipient = creature(f.ai(), 2, 2, List.of());
        f.game().getAction().checkStaticAbilities();
        final int sentinel = f.game().nextCardId();
        final int effectsBefore = effects(f.game());
        final var prepared = CombatStaticProjectionPreparation.prepare(f.ai(), () -> true).orElseThrow();
        Assert.assertTrue(prepared.supported(), prepared.reasons().toString());
        Assert.assertEquals(prepared.worlds().size(), 4);
        Assert.assertEquals(prepared.afterLosses(Set.of()).get(recipient.getId()).power(), 2);
        Assert.assertEquals(prepared.afterLosses(Set.of(positive.getId())).get(recipient.getId()).power(), 1);
        Assert.assertEquals(prepared.afterLosses(Set.of(negative.getId())).get(recipient.getId()).power(), 3);
        final var both = prepared.afterLosses(Set.of(positive.getId(), negative.getId(), 999999)).get(recipient.getId());
        Assert.assertEquals(both.power(), 2);
        Assert.assertEquals(both.toughness(), 2);
        Assert.assertEquals(both.bodyDelta(), 0, "Combined valuation returns to the original, not independent marginal deltas");
        Assert.assertEquals(recipient.getNetPower(), 2);
        Assert.assertEquals(recipient.getNetToughness(), 2);
        Assert.assertEquals(f.game().nextCardId(), sentinel + 1);
        Assert.assertEquals(effects(f.game()), effectsBefore);
        Assert.expectThrows(UnsupportedOperationException.class, () -> prepared.worlds().clear());
        Assert.expectThrows(UnsupportedOperationException.class, () -> prepared.afterLosses(Set.of()).clear());
    }

    @Test
    public void multipleAbilitiesOnOneSourceShareOneLossIdentityAndRetainZeroToughnessForSbaProjection() {
        final var f = fixture();
        final Card source = creature(f.ai(), 4, 4, List.of(
                "S:Mode$ Continuous | Affected$ Creature.YouCtrl+Other | AddPower$ 1",
                "S:Mode$ Continuous | Affected$ Creature.YouCtrl+Other | AddToughness$ 1"));
        final Card recipient = creature(f.ai(), 0, 0, List.of());
        f.game().getAction().checkStaticAbilities();
        Assert.assertEquals(recipient.getNetToughness(), 1);
        final var prepared = CombatStaticProjectionPreparation.prepare(f.ai(), () -> true).orElseThrow();
        Assert.assertTrue(prepared.supported());
        Assert.assertEquals(prepared.providers(), Set.of(source.getId()));
        Assert.assertEquals(prepared.worlds().size(), 2);
        final var after = prepared.afterLosses(Set.of(source.getId())).get(recipient.getId());
        Assert.assertEquals(after.power(), 0);
        Assert.assertEquals(after.toughness(), 0, "Do not clamp away a pending state-based death");
        Assert.assertTrue(after.bodyDelta() < 0);
        Assert.assertEquals(recipient.getNetToughness(), 1);
        Assert.assertTrue(recipient.isInPlay());
    }

    @DataProvider(name = "scopes")
    public Object[][] scopes() {
        return new Object[][] {{"Creature", 3, 3}, {"Creature.YouCtrl", 3, 2}, {"Creature.OppCtrl", 2, 3},
                {"Creature.YouCtrl+Other", 3, 2}, {"Card.Self", 2, 2},
                {"Creature.Human", 3, 3}, {"Creature.Human+YouCtrl+Other", 3, 2}, {"Creature.Elf", 2, 2}};
    }

    @Test(dataProvider = "scopes")
    public void onlyActuallyAffectedRecipientsLoseTrackedChanges(final String scope, final int ownPower, final int enemyPower) {
        final var f = fixture();
        final Card source = creature(f.ai(), 4, 4, List.of("S:Mode$ Continuous | Affected$ " + scope + " | AddPower$ 1"));
        final Card own = creature(f.ai(), 2, 2, List.of());
        final Card enemy = creature(f.opponent(), 2, 2, List.of());
        f.game().getAction().checkStaticAbilities();
        Assert.assertEquals(own.getNetPower(), ownPower);
        Assert.assertEquals(enemy.getNetPower(), enemyPower);
        final var prepared = CombatStaticProjectionPreparation.prepare(f.ai(), () -> true).orElseThrow();
        Assert.assertTrue(prepared.supported());
        final var after = prepared.afterLosses(Set.of(source.getId()));
        Assert.assertEquals(after.get(own.getId()).power(), 2);
        Assert.assertEquals(after.get(enemy.getId()).power(), 2);
        if (ownPower == 2) { Assert.assertEquals(after.get(own.getId()).bodyDelta(), 0); }
        if (enemyPower == 2) { Assert.assertEquals(after.get(enemy.getId()).bodyDelta(), 0); }
    }

    @Test
    public void everyPreparationCheckpointCancelsWithoutPublishingPartialWorlds() {
        final var f = fixture();
        creature(f.ai(), 4, 4, List.of("S:Mode$ Continuous | Affected$ Creature.YouCtrl | AddPower$ 1"));
        creature(f.ai(), 2, 2, List.of());
        f.game().getAction().checkStaticAbilities();
        final AtomicInteger total = new AtomicInteger();
        Assert.assertTrue(CombatStaticProjectionPreparation.prepare(f.ai(), () -> { total.incrementAndGet(); return true; })
                .orElseThrow().supported());
        for (int stop = 1; stop <= total.get(); stop++) {
            final int limit = stop;
            final AtomicInteger calls = new AtomicInteger();
            Assert.assertTrue(CombatStaticProjectionPreparation.prepare(f.ai(), () -> calls.incrementAndGet() < limit).isEmpty(),
                    "Checkpoint " + stop + " must not publish a supported subtotal");
        }
    }

    @Test
    public void missingTrackedEffectLookupIsReadOnlyAndDoesNotPretendTheLayerWasRemoved() {
        final var f = fixture();
        final Card source = creature(f.ai(), 4, 4, List.of("S:Mode$ Continuous | Affected$ Creature.YouCtrl | AddToughness$ 1"));
        final int count = effects(f.game());
        Assert.assertNull(f.game().getStaticEffects().findStaticEffect(source.getStaticAbilities().get(0)));
        final var prepared = CombatStaticProjectionPreparation.prepare(f.ai(), () -> true).orElseThrow();
        Assert.assertFalse(prepared.supported());
        Assert.assertEquals(effects(f.game()), count, "Analysis must not create a tracked StaticEffect");
    }

    @DataProvider(name = "unsupported")
    public Object[][] unsupported() {
        return new Object[][] {{"Affected$ Creature.powerGE4 | AddPower$ 1"}, {"Affected$ Creature.YouCtrl | AddPower$ X"},
                {"Affected$ Creature.YouCtrl | AddKeyword$ Flying"}, {"Affected$ Creature.nonSoldier | AddPower$ 1"},
                {"Affected$ Creature.YouCtrl | AddToughness$ 1 | AIEffectValue$ 10"}};
    }

    @Test(dataProvider = "unsupported")
    public void unprojectedPredicatesAndLayersRemainExplicitlyUnsupported(final String parameters) {
        final var f = fixture();
        creature(f.ai(), 4, 4, List.of("S:Mode$ Continuous | " + parameters));
        Assert.assertFalse(CombatStaticProjectionPreparation.prepare(f.ai(), () -> true).orElseThrow().supported());
    }

    @Test
    public void tooManyCombinedProvidersRequireFallbackRatherThanPartialVariants() {
        final var f = fixture();
        for (int index = 0; index < 5; index++) {
            creature(f.ai(), 4, 4, List.of("S:Mode$ Continuous | Affected$ Creature.YouCtrl | AddPower$ 1"));
        }
        f.game().getAction().checkStaticAbilities();
        final var prepared = CombatStaticProjectionPreparation.prepare(f.ai(), () -> true).orElseThrow();
        Assert.assertFalse(prepared.supported());
        Assert.assertTrue(prepared.worlds().isEmpty());
    }

    @Test
    public void typeChangingLayerCannotFreezeTribalRecipientsAsIfMembershipWerePermanent() {
        final var f = fixture();
        creature(f.ai(), 4, 4, List.of("S:Mode$ Continuous | Affected$ Creature.Human+YouCtrl | AddPower$ 1"));
        creature(f.ai(), 2, 2, List.of("S:Mode$ Continuous | Affected$ Creature.YouCtrl | AddType$ Human"));
        f.game().getAction().checkStaticAbilities();
        final var prepared = CombatStaticProjectionPreparation.prepare(f.ai(), () -> true).orElseThrow();
        Assert.assertFalse(prepared.supported());
        Assert.assertTrue(prepared.worlds().isEmpty(), "Unknown type dependency invalidates every combined world");
    }

    @Test
    public void survivingTribalProviderKeepsItsIdentityWhenItsLastRecipientLeaves() {
        final var f = fixture();
        final Card source = creature(f.ai(), 4, 4, List.of(
                "S:Mode$ Continuous | Affected$ Creature.Elf+YouCtrl | AddPower$ 1 | AddToughness$ 1"));
        final Card recipient = creature(f.ai(), 2, 2, List.of("Types:Creature Elf"));
        f.game().getAction().checkStaticAbilities();
        Assert.assertEquals(recipient.getNetPower(), 3);
        final var before = CombatStaticProjectionPreparation.prepare(f.ai(), () -> true).orElseThrow();
        f.ai().getZone(ZoneType.Battlefield).remove(recipient);
        f.ai().getZone(ZoneType.Graveyard).add(recipient);
        f.game().getAction().checkStaticAbilities();
        final var fresh = CombatStaticProjectionPreparation.prepare(f.ai(), () -> true).orElseThrow();
        Assert.assertTrue(fresh.supported(), fresh.reasons().toString());
        Assert.assertEquals(fresh.providers(), Set.of(source.getId()));
        Assert.assertEquals(fresh, before.surviving(Set.of(recipient.getId())), "Execution capture agrees with frozen rebasing");
    }

    private Fixture fixture() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(0);
        final Player opponent = game.getPlayers().get(1);
        ai.setTeam(0);
        opponent.setTeam(1);
        return new Fixture(game, ai, opponent);
    }

    private static int effects(final Game game) {
        int count = 0;
        for (final var ignored : game.getStaticEffects().getEffects()) { count++; }
        return count;
    }

    private static Card creature(final Player owner, final int power, final int toughness, final List<String> abilities) {
        final List<String> script = new ArrayList<>(List.of("Name:Static Combat Fixture", "ManaCost:2",
                "Types:Creature Human", "PT:" + power + "/" + toughness));
        script.addAll(abilities);
        final Card card = Card.fromPaperCard(new PaperCard(CardRules.fromScript(script), CardEdition.UNKNOWN_CODE, CardRarity.Special), owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());
        owner.getZone(ZoneType.Battlefield).add(card);
        card.setSickness(false);
        return card;
    }
}
