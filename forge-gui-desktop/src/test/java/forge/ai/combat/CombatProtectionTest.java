package forge.ai.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.effect.ValuationCompleteness;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

public class CombatProtectionTest extends AITest {
    private record Fixture(Game game, Player attacker, Player defender, Combat combat, Card source, Card blocker) { }

    @DataProvider(name = "qualities")
    public Object[][] qualities() {
        return new Object[][] {
            {"R", "Creature Human", "Protection from red", true},
            {"G", "Creature Human", "Protection from red", false},
            {"R G", "Creature Human", "Protection from each color", true},
            {"6", "Creature Human", "Protection from each color", false},
            {"6", "Creature Human", "Protection from colorless", true},
            {"6", "Creature Human", "Protection from everything", true},
            {"6", "Artifact Creature Human", "Protection:Artifact:artifacts", true},
            {"G", "Creature Human", "Protection:Artifact:artifacts", false},
            {"G", "Creature Human", "Protection:Creature.Human:Humans", true},
            {"G", "Creature Elf", "Protection:Creature.Human:Humans", false},
            {"G", "Creature Human", "Protection:Human", true},
            {"G", "Creature Elf", "Protection:Human", false},
            {"6", "Artifact Creature Equipment", "Protection:Equipment", true},
            {"6", "Artifact Creature Construct", "Protection:Equipment", false},
            {"2 R", "Creature Human", "Protection:Card.cmcEQ3:mana value 3", true},
            {"4 R", "Creature Human", "Protection:Card.cmcEQ3:mana value 3", false}
        };
    }

    @Test(dataProvider = "qualities")
    public void preparedMatchingAndDamageAgreeWithEngine(final String cost, final String type, final String quality, final boolean prevents) {
        final var f = fixture(cost, type, List.of(), List.of("K:" + quality));
        final var before = PublicCombatSnapshot.capture(f.attacker(), f.combat());
        Assert.assertTrue(before.unsupportedReasons().isEmpty(), before.unsupportedReasons().toString());
        Assert.assertFalse(before.protection().creatures().isEmpty(), "A known no-match is not absent protection");
        Assert.assertEquals(before.protection().prevents(f.source().getId(), f.blocker().getId(), CombatEventBatch.Recipient.CREATURE), prevents);
        Assert.assertFalse(before.protection().prevents(f.blocker().getId(), f.source().getId(), CombatEventBatch.Recipient.CREATURE));
        Assert.assertFalse(before.protection().prevents(f.source().getId(), f.blocker().getId(), CombatEventBatch.Recipient.PLAYER));
        final var result = CombatOutcomePredictor.predict(before, assignment(f, before));
        Assert.assertTrue(result.supported() && result.available(), result.reasons().toString());
        Assert.assertEquals(result.lostCreatures().contains(f.blocker().getId()), !prevents);
        resolveAndCompare(f, result);
    }

    @Test
    public void protectionRestrictsBlockingOnlyForTheProtectedAttackerAndInstancesUnion() {
        final var f = fixture("R", "Creature Human", List.of(), List.of("K:Protection from red", "K:Protection from black"));
        var board = PublicCombatSnapshot.capture(f.attacker(), f.combat());
        Assert.assertTrue(board.legalBlockers().get(f.source().getId()).contains(f.blocker().getId()));
        Assert.assertEquals(board.protection().creatures().get(f.blocker().getId()).qualities().size(), 2);
        final Combat reversed = new Combat(f.defender());
        reversed.addAttacker(f.blocker(), f.attacker());
        f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, f.defender());
        f.game().getPhaseHandler().setCombat(reversed);
        board = PublicCombatSnapshot.capture(f.defender(), reversed);
        Assert.assertFalse(board.legalBlockers().get(f.blocker().getId()).contains(f.source().getId()));
        Assert.assertTrue(board.unsupportedReasons().isEmpty(), board.unsupportedReasons().toString());
    }

    @Test
    public void trampleDoubleStrikeLifelinkAndDeathtouchUseActualNotAssignedDamage() {
        final var f = fixture("G", "Creature Human", List.of("K:Trample", "K:Double strike", "K:Lifelink", "K:Deathtouch"),
                List.of("K:Protection from green"));
        final var before = PublicCombatSnapshot.capture(f.attacker(), f.combat());
        final var result = CombatOutcomePredictor.predict(before, assignment(f, before));
        Assert.assertTrue(result.supported() && result.available(), result.reasons().toString());
        Assert.assertEquals(result.playerLifeAfter().get(f.defender().getId()).intValue(), 10);
        Assert.assertEquals(result.playerLifeAfter().get(f.attacker().getId()).intValue(), 30);
        Assert.assertEquals(result.survivors().get(f.blocker().getId()).markedDamage(), 0);
        Assert.assertFalse(result.survivors().get(f.blocker().getId()).markedDeathtouch());
        Assert.assertFalse(result.eventBatches().stream().flatMap(b -> b.events().stream()).anyMatch(e ->
                e instanceof CombatEventBatch.Damage hit && hit.recipientId() == f.blocker().getId()));
        resolveAndCompare(f, result);
    }

    @Test
    public void protectionPreservesShieldsButUnpreventableHitsStillConsumeThem() {
        for (final boolean unpreventable : List.of(false, true)) {
            final var f = fixture("R", "Creature Human", unpreventable ? List.of("S:Mode$ CantPreventDamage | ValidSource$ Card.Self") : List.of(),
                    List.of("K:Protection from red"));
            f.blocker().setCounters(CounterEnumType.SHIELD, 1);
            final var board = PublicCombatSnapshot.capture(f.attacker(), f.combat());
            final var result = CombatOutcomePredictor.predict(board, assignment(f, board));
            Assert.assertTrue(result.supported(), result.reasons().toString());
            Assert.assertEquals(f.blocker().getCounters(CounterEnumType.SHIELD), 1, "Prediction does not consume live counters");
            if (unpreventable) { Assert.assertTrue(result.lostCreatures().contains(f.blocker().getId())); }
            else { Assert.assertEquals(result.survivors().get(f.blocker().getId()).shieldCounters(), 1); }
            resolveAndCompare(f, result);
        }
    }

    @Test
    public void persistentProtectionSurvivesCopiesRepliesAndExactCacheReuseButNotIdentityPermutation() {
        final var f = fixture("R", "Creature Human", List.of(), List.of("K:Protection from red"));
        final var board = PublicCombatSnapshot.capture(f.defender(), f.combat());
        final var held = CombatAttackCandidates.assign(board, Map.of());
        Assert.assertEquals(held.protection(), board.protection());
        final var result = CombatOutcomePredictor.predict(board, assignment(f, board));
        final var next = CombatTurnProjectionBuilder.advance(board, result, PublicCombatReadiness.capture(f.defender(), board), values(board));
        Assert.assertTrue(next.turn().isPresent(), next.reasons().toString());
        Assert.assertEquals(next.turn().orElseThrow().alternatives().protection(), board.protection());
        final var cached = CombatBlockSearch.search(board, values(board), new CombatSearchBudget(5000), 16, true);
        final var reference = CombatBlockSearch.search(board, values(board), new CombatSearchBudget(5000), 16, false);
        Assert.assertEquals(cached.best(), reference.best());
        Assert.assertFalse(board.singletonPermutationSafe());
        Assert.assertEquals(board.protection().surviving(Set.of(f.blocker().getId())), PreparedCombatProtection.empty());
        Assert.assertFalse(board.protection().surviving(Set.of(f.source().getId())).sources().containsKey(f.source().getId()));
    }

    @Test
    public void ownBlockPlannerUsesTheSafeBlockAndPreparationIsInterruptible() {
        final var f = fixture("R", "Creature Human", List.of(), List.of("K:Protection from red"));
        ((LobbyPlayerAi) f.defender().getLobbyPlayer()).setAiProfile("Mastermind");
        f.defender().setLife(5, null);
        f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, f.attacker(), false);
        final var plan = CombatBlockPlanner.plan(f.defender(), f.combat(), new CombatSearchBudget(50000, 30000, () -> 0L));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertEquals(plan.search().orElseThrow().best().orElseThrow().assignment().blockersByAttacker(),
                Map.of(f.source().getId(), List.of(f.blocker().getId())));
        Assert.assertTrue(CombatBlockPlanner.tryDeclare(f.defender(), f.defender(), f.combat(), new CombatSearchBudget(50000)));
        final var count = new AtomicInteger();
        Assert.assertTrue(CombatProtectionPreparation.prepare(f.defender(), () -> count.incrementAndGet() < 3).isEmpty());
    }

    @Test
    public void temporaryDynamicAndNoncreatureProtectionAreExplicitlyRejected() {
        final var temporary = fixture("R", "Creature Human", List.of(), List.of());
        temporary.blocker().addChangedCardKeywords(List.of("Protection from red"), List.of(), false, temporary.game().getNextTimestamp(), null);
        Assert.assertTrue(PublicCombatSnapshot.capture(temporary.attacker(), temporary.combat()).unsupportedReasons().stream()
                .anyMatch(r -> r.contains("Protection grant")));
        for (final String validity : List.of("Creature.powerGE4", "Human.powerGE4", "Equipment.powerGE4")) {
            final var dynamic = fixture("R", "Creature Human", List.of(), List.of("K:Protection:" + validity + ":power 4 or greater"));
            Assert.assertTrue(PublicCombatSnapshot.capture(dynamic.attacker(), dynamic.combat()).unsupportedReasons().stream()
                    .anyMatch(r -> r.contains("Dynamic Protection quality")));
        }
        temporary.defender().addChangedKeywords(List.of("Protection from everything"), List.of(), temporary.game().getNextTimestamp(), 0);
        Assert.assertTrue(PublicCombatSnapshot.capture(temporary.attacker(), temporary.combat()).unsupportedReasons().stream()
                .anyMatch(r -> r.contains("Protected player")));
    }

    @Test
    public void unconditionalPublicChosenPlayerAndColorSelfGrantsArePersistent() {
        for (final boolean player : List.of(false, true)) {
            final String keyword = player ? "Protection:Player.PlayerUID_ChosenPlayerUID:ChosenPlayerName"
                    : "Protection:Card.ChosenColor:chosenColor";
            final var f = fixture("R", "Creature Human", List.of(), List.of(
                    "S:Mode$ Continuous | AddKeyword$ " + keyword + " | AffectedDefined$ Self"));
            if (player) { f.blocker().setChosenPlayer(f.attacker()); }
            else { f.blocker().setChosenColors(List.of("Red")); }
            f.game().getAction().checkStaticAbilities();
            final var board = PublicCombatSnapshot.capture(f.attacker(), f.combat());
            Assert.assertTrue(board.unsupportedReasons().isEmpty(), board.unsupportedReasons().toString());
            Assert.assertTrue(board.protection().prevents(f.source().getId(), f.blocker().getId(), CombatEventBatch.Recipient.CREATURE));
            Assert.assertTrue(CombatProtectionPreparation.persistentKeywordChanges(f.blocker()));
            resolveAndCompare(f, CombatOutcomePredictor.predict(board, assignment(f, board)));
        }
    }

    @Test
    public void externalGrantsAndShieldCounterConsequencesAreNotSilentlyAdmitted() {
        final var external = fixture("R", "Creature Human", List.of(), List.of());
        card(external.defender(), "W", "Enchantment", "0/0", List.of(
                "S:Mode$ Continuous | Affected$ Creature.YouCtrl | AddKeyword$ Protection from red"));
        external.game().getAction().checkStaticAbilities();
        Assert.assertTrue(PublicCombatSnapshot.capture(external.attacker(), external.combat()).unsupportedReasons().stream()
                .anyMatch(r -> r.contains("Protection grant")));
        final var shield = fixture("R", "Creature Human", List.of(), List.of("K:Protection from red",
                "T:Mode$ CounterRemoved | ValidCard$ Card.Self | CounterType$ Shield | Execute$ Draw",
                "SVar:Draw:DB$ Draw | Defined$ You | NumCards$ 1"));
        shield.blocker().setCounters(CounterEnumType.SHIELD, 1);
        Assert.assertTrue(PublicCombatSnapshot.capture(shield.attacker(), shield.combat()).unsupportedReasons().stream()
                .anyMatch(r -> r.contains("Protection/shield ordering")));
    }

    @Test
    public void firstStrikeDepartureOfUnpreventableProviderRestoresProtectionForRegularDamage() {
        final var f = fixture("R", "Creature Human", List.of("K:Double strike"), List.of("K:Protection from red"));
        final Card provider = card(f.attacker(), "R", "Creature Human", "1/1", List.of("S:Mode$ CantPreventDamage"));
        final Card firstStriker = card(f.defender(), "W", "Creature Human", "1/2", List.of("K:First strike"));
        f.combat().addAttacker(provider, f.defender());
        final var board = PublicCombatSnapshot.capture(f.attacker(), f.combat());
        final var blocks = new CombatAssignment(board.attackersToDefenders(), Map.of(
                f.source().getId(), List.of(f.blocker().getId()), provider.getId(), List.of(firstStriker.getId())));
        // Give the protected blocker enough toughness to survive the unpreventable first hit.
        f.blocker().setCounters(CounterEnumType.P1P1, 5);
        final var updated = PublicCombatSnapshot.capture(f.attacker(), f.combat());
        final var result = CombatOutcomePredictor.predict(updated, blocks);
        Assert.assertTrue(result.available(), result.reasons().toString());
        Assert.assertTrue(result.lostCreatures().contains(provider.getId()));
        Assert.assertEquals(result.survivors().get(f.blocker().getId()).markedDamage(), 6);
        Assert.assertEquals(result.batches().size(), 2);
        Assert.assertEquals(result.batches().get(1).creatureDamage().getOrDefault(f.blocker().getId(), 0).intValue(), 0);
    }

    @Test
    public void simultaneousProtectedAndUnprotectedHitsOnlySpendTheNecessaryShield() {
        final var f = fixture("R", "Creature Human", List.of("K:Lifelink", "K:Deathtouch"), List.of("K:Protection from red"));
        final Card green = card(f.attacker(), "G", "Creature Human", "2/2", List.of("K:Lifelink", "K:Deathtouch"));
        final Card guard = card(f.defender(), "W", "Creature Human", "2/4", List.of());
        f.blocker().setCounters(CounterEnumType.SHIELD, 1);
        guard.setCounters(CounterEnumType.SHIELD, 1);
        f.combat().addAttacker(green, f.defender());
        final var board = PublicCombatSnapshot.capture(f.attacker(), f.combat());
        final var blocks = new CombatAssignment(board.attackersToDefenders(), Map.of(
                f.source().getId(), List.of(f.blocker().getId()), green.getId(), List.of(guard.getId())));
        final var result = CombatOutcomePredictor.predict(board, blocks);
        Assert.assertTrue(result.available(), result.reasons().toString());
        Assert.assertEquals(result.survivors().get(f.blocker().getId()).shieldCounters(), 1);
        Assert.assertEquals(result.survivors().get(guard.getId()).shieldCounters(), 0);
        Assert.assertEquals(result.survivors().get(guard.getId()).markedDamage(), 0);
        Assert.assertFalse(result.survivors().get(guard.getId()).markedDeathtouch());
        Assert.assertEquals(result.playerLifeAfter().get(f.attacker().getId()).intValue(), 20);
        f.combat().addBlocker(green, guard);
        f.combat().setBlocked(green, true);
        resolveAndCompare(f, result);
        Assert.assertEquals(guard.getCounters(CounterEnumType.SHIELD), 0);
    }

    @Test
    public void gangAllocationsCanKillTheUnprotectedBlockerButNotTheProtectedOne() {
        final var f = fixture("R", "Creature Human", List.of("K:Menace"), List.of("K:Protection from red"));
        final Card ordinary = card(f.defender(), "W", "Creature Human", "2/2", List.of());
        final var board = PublicCombatSnapshot.capture(f.attacker(), f.combat());
        final var blocks = new CombatAssignment(board.attackersToDefenders(), Map.of(
                f.source().getId(), List.of(f.blocker().getId(), ordinary.getId())));
        final var allocations = CombatDamageOptimizer.optimizeBlockGroups(board, blocks, values(board), new CombatSearchBudget(5000));
        Assert.assertTrue(allocations.exhaustive() && allocations.outcomeSupported(), allocations.reasons().toString());
        final var result = allocations.best().orElseThrow().projection();
        Assert.assertEquals(result.lostCreatures(), Set.of(ordinary.getId()));
        Assert.assertEquals(result.survivors().get(f.blocker().getId()).markedDamage(), 0);
        f.combat().addBlocker(f.source(), ordinary);
        resolveAndCompare(f, result);
    }

    @Test
    public void executionRejectsChangedProtectionAndMatchingSourceCharacteristicsWithoutAllocatingIds() {
        for (final boolean sourceChange : List.of(false, true)) {
            final var f = fixture("R", "Creature Human", List.of("K:Trample"), List.of("K:Protection from red"));
            f.combat().addBlocker(f.source(), f.blocker());
            f.combat().setBlocked(f.source(), true);
            f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, f.attacker(), false);
            final int sentinel = f.game().nextCardId();
            final var board = PublicCombatSnapshot.capture(f.attacker(), f.combat());
            final var choice = CombatDamageOptimizer.optimizeBlockGroups(board, assignment(f, board), values(board), new CombatSearchBudget(5000)).best().orElseThrow();
            final var execution = CombatExecutionPlan.create(f.attacker(), f.combat(), board, choice.assignment(), choice.damagePlan()).orElseThrow();
            Assert.assertEquals(f.game().nextCardId(), sentinel + 1);
            if (sourceChange) { f.source().addColor(forge.card.ColorSet.fromNames(List.of("Green")), false, f.game().getNextTimestamp(), null); }
            else { f.blocker().addChangedCardKeywords(List.of("Protection from green"), List.of(), false, f.game().getNextTimestamp(), null); }
            Assert.assertTrue(execution.orderBlockers(f.source(), new forge.game.card.CardCollection(List.of(f.blocker()))).isEmpty());
            Assert.assertTrue(execution.isInvalid());
        }
    }

    @Test
    public void attackPlannerExploitsProtectionAndDoesNotPredictAnIllegalMatchingBlock() {
        final var f = fixture("R", "Creature Human", List.of("K:Protection from white"), List.of());
        ((LobbyPlayerAi) f.attacker().getLobbyPlayer()).setAiProfile("Mastermind");
        f.combat().removeFromCombat(f.source());
        final var plan = CombatAttackPlanner.plan(f.attacker(), f.combat(), new CombatSearchBudget(50000, 30000, () -> 0L));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        final var selected = plan.search().orElseThrow().best().orElseThrow();
        Assert.assertEquals(selected.attackers(), List.of(f.source().getId()));
        Assert.assertTrue(selected.combat().assignment().blockersByAttacker().isEmpty());
    }

    private Fixture fixture(final String cost, final String type, final List<String> sourceExtra, final List<String> blockerExtra) {
        final Game game = initAndCreateGame();
        final Player attacker = game.getPlayers().get(0), defender = game.getPlayers().get(1);
        attacker.setTeam(0); defender.setTeam(1);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, attacker);
        final Card source = card(attacker, cost, type, "6/6", sourceExtra);
        final Card blocker = card(defender, "W", "Creature Human", "2/2", blockerExtra);
        for (final Player player : game.getPlayers()) {
            for (int index = 0; index < 4; index++) { addCardToZone("Forest", player, ZoneType.Library); }
        }
        final Combat combat = new Combat(attacker);
        game.getPhaseHandler().setCombat(combat);
        combat.addAttacker(source, defender);
        return new Fixture(game, attacker, defender, combat, source, blocker);
    }

    private static Card card(final Player owner, final String cost, final String type, final String pt, final List<String> extra) {
        final var script = new ArrayList<>(List.of("Name:Protection Combat Fixture", "ManaCost:" + cost, "Types:" + type, "PT:" + pt));
        script.addAll(extra);
        final Card card = Card.fromPaperCard(new PaperCard(CardRules.fromScript(script), CardEdition.UNKNOWN_CODE, CardRarity.Special), owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());
        owner.getZone(ZoneType.Battlefield).add(card);
        card.setSickness(false);
        return card;
    }

    private static CombatAssignment assignment(final Fixture f, final PublicCombatSnapshot board) {
        return new CombatAssignment(board.attackersToDefenders(), Map.of(f.source().getId(), List.of(f.blocker().getId())));
    }

    private static PreparedCombatValuation values(final PublicCombatSnapshot board) {
        final Map<Integer, PreparedCombatValuation.PermanentValue> result = new LinkedHashMap<>();
        board.creatures().forEach((id,c) -> result.put(id, new PreparedCombatValuation.PermanentValue(id, c.controllerId(),
                c.controllerId() == board.observingPlayerId() ? -100 : 100, 0, 0, List.of())));
        return new PreparedCombatValuation(result, List.of(), ValuationCompleteness.COMPLETE, List.of());
    }

    private static void resolveAndCompare(final Fixture f, final CombatProjection result) {
        f.combat().addBlocker(f.source(), f.blocker());
        f.combat().setBlocked(f.source(), true);
        CombatExecutionPlan execution = null;
        if (f.source().hasKeyword(forge.game.keyword.Keyword.TRAMPLE) || f.combat().getBlockers(f.source()).size() > 1) {
            f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, f.attacker(), false);
            final var before = PublicCombatSnapshot.capture(f.attacker(), f.combat());
            final Map<Integer, List<Integer>> declared = new LinkedHashMap<>();
            f.combat().getAttackers().forEach(a -> declared.put(a.getId(), f.combat().getBlockers(a).stream().map(Card::getId).toList()));
            final var choice = CombatDamageOptimizer.optimizeBlockGroups(before, new CombatAssignment(before.attackersToDefenders(), declared), values(before), new CombatSearchBudget(5000))
                    .best().orElseThrow();
            execution = CombatExecutionPlan.create(f.attacker(), f.combat(), before, choice.assignment(), choice.damagePlan()).orElseThrow();
            final var controller = new PlayerControllerAi(f.game(), f.attacker(), f.attacker().getLobbyPlayer()) {
                @Override protected boolean isCombatPlanningEnabled() { return true; }
            };
            f.attacker().dangerouslySetController(controller);
            Assert.assertTrue(controller.installCombatExecutionPlan(execution));
        }
        f.combat().orderBlockersForDamageAssignment();
        f.combat().orderAttackersForDamageAssignment();
        for (final boolean first : List.of(true, false)) {
            f.game().getPhaseHandler().devModeSet(first ? PhaseType.COMBAT_FIRST_STRIKE_DAMAGE : PhaseType.COMBAT_DAMAGE, f.attacker(), false);
            f.combat().removeAbsentCombatants();
            if (f.combat().assignCombatDamage(first)) { f.combat().dealAssignedDamage(); }
            f.game().getAction().checkStateEffects(true);
        }
        Assert.assertEquals(f.attacker().getLife(), result.playerLifeAfter().get(f.attacker().getId()).intValue());
        Assert.assertEquals(f.defender().getLife(), result.playerLifeAfter().get(f.defender().getId()).intValue());
        for (final Card card : List.of(f.source(), f.blocker())) {
            Assert.assertEquals(!card.isInPlay(), result.lostCreatures().contains(card.getId()));
            if (card.isInPlay()) {
                final var survivor = result.survivors().get(card.getId());
                Assert.assertEquals(card.getDamage(), survivor.markedDamage());
                Assert.assertEquals(card.getCounters(CounterEnumType.SHIELD), survivor.shieldCounters());
                Assert.assertEquals(card.hasBeenDealtDeathtouchDamage(), survivor.markedDeathtouch());
            }
        }
        if (execution != null) { Assert.assertFalse(execution.isInvalid()); }
    }
}
