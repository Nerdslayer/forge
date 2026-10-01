package forge.ai.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.ai.LobbyPlayerAi;
import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.effect.ValuationCompleteness;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

/** Engine callback fixtures, not just a second scalar damage calculation. */
public class CombatExecutionPlanTest extends AITest {
    private record Fixture(Game game, Player attacker, Player defender, Combat combat,
            Card source, List<Card> blockers, PublicCombatSnapshot snapshot,
            CombatDamageOptimizer.Candidate choice, CombatExecutionPlan execution) { }

    @Test
    public void executionAcceptsProjectedAnthemLossButRejectsAnUnrelatedPump() {
        for (final String scope : List.of("Creature", "Creature.YouCtrl+Other")) {
            final Fixture f = fixture(3, 5, List.of("K:Double strike", "K:Trample"), List.of(
                    List.of("PT:1/1", "S:Mode$ Continuous | Affected$ " + scope + " | AddPower$ 1 | AddToughness$ 1"),
                    List.of("PT:1/3")));
            installTestController(f);
            resolve(f);
            compare(f);
            Assert.assertFalse(f.execution().isInvalid(), "Expected source loss is not a stale prediction: " + scope);
        }
        final Fixture stale = fixture(3, 5, List.of("K:Double strike"), List.of(
                List.of("PT:1/1", "S:Mode$ Continuous | Affected$ Creature | AddPower$ 1")));
        stale.source().addPTBoost(1, 0, stale.game().getNextTimestamp(), 0);
        Assert.assertTrue(stale.execution().orderBlockers(stale.source(), new CardCollection(stale.blockers())).isEmpty());
        Assert.assertTrue(stale.execution().isInvalid());
    }

    @Test
    public void engineKeepsMenaceDoubleStrikerBlockedAfterItsWholeGangDiesInFirstStrike() {
        for (final boolean legacy : List.of(false, true)) {
            final Fixture f = fixture(4, 4, List.of("K:Double strike", "K:Menace"),
                    List.of(List.of("PT:2/2"), List.of("PT:2/2")), legacy);
            final Map<Integer, Integer> hits = Map.of(f.blockers().get(0).getId(), 2, f.blockers().get(1).getId(), 2);
            final CombatDamagePlan damage = new CombatDamagePlan(
                    Map.of(f.source().getId(), new CombatDamageAllocation.Allocation(hits, 0)),
                    Map.of(f.source().getId(), new CombatDamageAllocation.Allocation(Map.of(), 0)));
            final var execution = CombatExecutionPlan.create(f.attacker(), f.combat(), f.snapshot(), f.choice().assignment(), damage).orElseThrow();
            final PlayerControllerAi controller = new PlayerControllerAi(f.game(), f.attacker(), f.attacker().getLobbyPlayer()) {
                @Override protected boolean isCombatPlanningEnabled() { return true; }
            };
            f.attacker().dangerouslySetController(controller);
            Assert.assertTrue(controller.installCombatExecutionPlan(execution));
            resolve(f);
            Assert.assertTrue(f.blockers().stream().noneMatch(Card::isInPlay));
            Assert.assertTrue(f.source().isInPlay());
            Assert.assertEquals(f.source().getDamage(), 0);
            Assert.assertEquals(f.defender().getLife(), 20);
            Assert.assertFalse(execution.isInvalid());
        }
    }

    @Test
    public void pendingAttackExecutionRepricesActualBlocksBeforeDamageWithoutInstallingHypotheticalBlocks() {
        final Fixture f = fixture(4, 4, List.of("K:Double strike", "K:Deathtouch", "K:Trample", "K:Lifelink"),
                List.of(List.of("PT:1/6", "K:Indestructible")));
        for (final Player player : f.game().getPlayers()) {
            for (int count = 0; count < 4; count++) { addCardToZone("Forest", player, ZoneType.Library); }
        }
        final PlayerControllerAi controller = new PlayerControllerAi(f.game(), f.attacker(), f.attacker().getLobbyPlayer()) {
            @Override protected boolean isCombatPlanningEnabled() { return true; }
        };
        f.attacker().dangerouslySetController(controller);
        final int sentinel = f.game().nextCardId();
        Assert.assertTrue(controller.requestCombatExecutionPlan(f.combat()));
        Assert.assertEquals(f.combat().getBlockers(f.source()), f.blockers());
        Assert.assertEquals(f.game().nextCardId(), sentinel + 1);
        resolve(f);
        Assert.assertEquals(f.defender().getLife(), 14, "Execute legal excess damage rather than assigning all damage to indestructible");
        Assert.assertEquals(f.attacker().getLife(), 28);
        Assert.assertEquals(f.blockers().get(0).getDamage(), 2);
        Assert.assertTrue(f.blockers().get(0).isInPlay());
    }

    @Test
    public void pendingExecutionRejectsDefaultForeignCombatAndAlreadyResolvedFirstStrikeBoundary() {
        final Fixture f = fixture(3, 3, List.of("K:Double strike"), List.of(List.of("PT:2/2")));
        ((LobbyPlayerAi) f.attacker().getLobbyPlayer()).setAiProfile("Default");
        final PlayerControllerAi ordinary = new PlayerControllerAi(f.game(), f.attacker(), f.attacker().getLobbyPlayer());
        Assert.assertFalse(ordinary.requestCombatExecutionPlan(f.combat()));
        Assert.assertFalse(ordinary.requestCombatExecutionPlan(new Combat(f.attacker())));
        f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE, f.attacker(), false);
        final int sentinel = f.game().nextCardId();
        Assert.assertTrue(CombatDamagePlanner.prepare(f.attacker(), f.combat(), false, new CombatSearchBudget(10000)).isEmpty());
        Assert.assertEquals(f.game().nextCardId(), sentinel + 1);
        Assert.assertEquals(f.blockers().get(0).getDamage(), 0);
    }

    @Test
    public void pendingExecutionBindsGangOrdersAndBothStrikeStepsUnderModernAndLegacyRules() {
        for (final boolean legacy : List.of(false, true)) {
            final Fixture f = fixture(3, 3, List.of("K:Double strike"),
                    List.of(List.of("PT:2/2"), List.of("PT:3/3")), legacy);
            for (final Player player : f.game().getPlayers()) {
                for (int count = 0; count < 4; count++) { addCardToZone("Forest", player, ZoneType.Library); }
            }
            final PlayerControllerAi controller = new PlayerControllerAi(f.game(), f.attacker(), f.attacker().getLobbyPlayer()) {
                @Override protected boolean isCombatPlanningEnabled() { return true; }
            };
            f.attacker().dangerouslySetController(controller);
            Assert.assertTrue(controller.requestCombatExecutionPlan(f.combat()));
            resolve(f);
            Assert.assertTrue(f.source().isInPlay(), "Kill the 3/3 in first strike to keep the double-striker alive");
            Assert.assertEquals(f.source().getDamage(), 2);
            Assert.assertTrue(f.blockers().stream().noneMatch(Card::isInPlay));
        }
    }

    @Test
    public void engineExecutesSavedDeathtouchTrampleInsteadOfOverassigningToIndestructible() {
        final Fixture f = fixture(4, 4, List.of("K:Double strike", "K:Deathtouch", "K:Trample", "K:Lifelink"),
                List.of(List.of("PT:1/6", "K:Indestructible")));
        final AtomicInteger assigned = installTestController(f);
        resolve(f);
        Assert.assertEquals(assigned.get(), 2);
        Assert.assertEquals(f.defender().getLife(), 14);
        Assert.assertEquals(f.attacker().getLife(), 28);
        Assert.assertTrue(f.blockers().get(0).isInPlay());
        Assert.assertEquals(f.blockers().get(0).getDamage(), 2);
        Assert.assertFalse(f.execution().isInvalid());
        compare(f);
    }

    @Test
    public void regularDamageRevalidationAcceptsPredictedFirstStrikeDeathsAndRejectsNoNewState() {
        final Fixture f = fixture(3, 3, List.of("K:Double strike"),
                List.of(List.of("PT:2/2"), List.of("PT:3/3")));
        final AtomicInteger assigned = installTestController(f);
        resolve(f);
        Assert.assertEquals(assigned.get(), 2);
        Assert.assertTrue(f.source().isInPlay());
        Assert.assertEquals(f.source().getDamage(), 2);
        Assert.assertTrue(f.blockers().stream().noneMatch(Card::isInPlay));
        Assert.assertFalse(f.execution().isInvalid());
        compare(f);
    }

    @Test
    public void suppressionOfAFrozenPreventionProviderInvalidatesItsDamageExecution() {
        final Fixture f = fixture(3, 3, List.of("K:Trample", "S:Mode$ CantPreventDamage | ValidSource$ Card.Self"),
                List.of(List.of("PT:1/2")));
        Assert.assertEquals(f.snapshot().preventionRules().size(), 1);
        f.source().getStaticAbilities().get(0).setSuppressed(true);
        f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE, f.attacker(), false);
        Assert.assertTrue(f.execution().assignDamage(f.source(), new CardCollection(f.blockers()), 3,
                f.defender(), !f.snapshot().legacyDamageOrder()).isEmpty());
        Assert.assertTrue(f.execution().isInvalid());
    }

    @Test
    public void shieldConsumptionInFirstStrikeIsAcceptedButUnpredictedCountersInvalidateRegularDamage() {
        for (final boolean unexpected : List.of(false, true)) {
            final Fixture f = fixture(5, 6, List.of("K:Double strike", "K:Trample"),
                    List.of(List.of("PT:2/2", "K:Double strike")));
            f.source().setCounters(forge.game.card.CounterEnumType.SHIELD, 1);
            final var snapshot = PublicCombatSnapshot.capture(f.attacker(), f.combat());
            final var execution = CombatExecutionPlan.create(f.attacker(), f.combat(), snapshot,
                    f.choice().assignment(), f.choice().damagePlan()).orElseThrow();
            final PlayerControllerAi controller = new PlayerControllerAi(f.game(), f.attacker(), f.attacker().getLobbyPlayer()) {
                @Override protected boolean isCombatPlanningEnabled() { return true; }
            };
            f.attacker().dangerouslySetController(controller);
            Assert.assertTrue(controller.installCombatExecutionPlan(execution));
            f.combat().orderBlockersForDamageAssignment();
            f.combat().orderAttackersForDamageAssignment();
            Assert.assertFalse(execution.isInvalid(), "Shield plan must survive ordering");
            f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_FIRST_STRIKE_DAMAGE, f.attacker(), false);
            Assert.assertTrue(f.combat().assignCombatDamage(true));
            Assert.assertFalse(execution.isInvalid(), "Shield plan must supply first-strike assignment");
            f.combat().dealAssignedDamage();
            f.game().getAction().checkStateEffects(true);
            Assert.assertEquals(f.source().getCounters(forge.game.card.CounterEnumType.SHIELD), 0);
            Assert.assertEquals(f.source().getDamage(), 0);
            if (unexpected) { f.source().setCounters(forge.game.card.CounterEnumType.SHIELD, 1); }
            f.combat().removeAbsentCombatants();
            f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE, f.attacker(), false);
            Assert.assertEquals(execution.assignDamage(f.source(), new CardCollection(), 5,
                    f.defender(), !snapshot.legacyDamageOrder()).isEmpty(), unexpected,
                    "Regular damage must use the expected depleted shield count");
            Assert.assertEquals(execution.isInvalid(), unexpected);
        }
    }

    @Test
    public void stunCountsAreValidatedWithoutRejectingAnUnchangedCounterReplacement() {
        for (final boolean change : List.of(false, true)) {
            final Fixture f = fixture(3, 3, List.of("K:Trample"), List.of(List.of("PT:1/2")));
            f.source().setCounters(forge.game.card.CounterEnumType.STUN, 2);
            final var snapshot = PublicCombatSnapshot.capture(f.attacker(), f.combat());
            final var execution = CombatExecutionPlan.create(f.attacker(), f.combat(), snapshot,
                    f.choice().assignment(), f.choice().damagePlan()).orElseThrow();
            if (change) { f.source().setCounters(forge.game.card.CounterEnumType.STUN, 1); }
            f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE, f.attacker(), false);
            final var allocation = execution.assignDamage(f.source(), new CardCollection(f.blockers()), 3,
                    f.defender(), !snapshot.legacyDamageOrder());
            Assert.assertEquals(allocation.isEmpty(), change);
            Assert.assertEquals(execution.isInvalid(), change);
        }
    }

    @Test
    public void staleLifeDamageTimestampAndCombatIdentityInvalidateSavedChoices() {
        for (int change = 0; change < 4; change++) {
            final Fixture f = fixture(3, 3, List.of("K:Trample"), List.of(List.of("PT:1/2")));
            f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE, f.attacker(), false);
            switch (change) {
                case 0 -> f.defender().setLife(19, null);
                case 1 -> f.blockers().get(0).setDamage(1);
                case 2 -> f.blockers().get(0).setGameTimestamp(f.game().getNextTimestamp());
                default -> f.game().getPhaseHandler().setCombat(new Combat(f.attacker()));
            }
            Assert.assertTrue(f.execution().assignDamage(f.source(), new CardCollection(f.blockers()), 3,
                    f.defender(), !f.snapshot().legacyDamageOrder()).isEmpty());
            Assert.assertTrue(f.execution().isInvalid());
        }
    }

    @Test
    public void wrongCallbackDamageOrRecipientsCannotExecuteTheOldMap() {
        final Fixture damage = fixture(3, 3, List.of("K:Trample"), List.of(List.of("PT:1/2")));
        damage.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE, damage.attacker(), false);
        Assert.assertTrue(damage.execution().assignDamage(damage.source(), new CardCollection(damage.blockers()), 4,
                damage.defender(), !damage.snapshot().legacyDamageOrder()).isEmpty());
        final Fixture targets = fixture(3, 3, List.of("K:Trample"), List.of(List.of("PT:1/2")));
        targets.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE, targets.attacker(), false);
        Assert.assertTrue(targets.execution().assignDamage(targets.source(), new CardCollection(), 3,
                targets.defender(), !targets.snapshot().legacyDamageOrder()).isEmpty());
        Assert.assertTrue(damage.execution().isInvalid() && targets.execution().isInvalid());
    }

    @Test
    public void defaultProfileCannotInstallAPlanAndStaleCallbacksDelegateToLegacy() {
        final Fixture disabled = fixture(3, 3, List.of("K:Trample"), List.of(List.of("PT:1/2")));
        ((LobbyPlayerAi) disabled.attacker().getLobbyPlayer()).setAiProfile("Default");
        Assert.assertFalse(AiProfileUtil.getBoolProperty(disabled.attacker(), AiProps.ENABLE_COMBAT_BLOCK_PLANNING));
        Assert.assertFalse(AiProfileUtil.getBoolProperty(disabled.attacker(), AiProps.ENABLE_COMBAT_ATTACK_PLANNING));
        Assert.assertFalse(((PlayerControllerAi) disabled.attacker().getController()).installCombatExecutionPlan(disabled.execution()));

        final Fixture stale = fixture(4, 4, List.of("K:Deathtouch", "K:Trample"), List.of(List.of("PT:1/6", "K:Indestructible")));
        installTestController(stale);
        stale.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE, stale.attacker(), false);
        stale.defender().setLife(19, null);
        final Map<Card, Integer> fallback = stale.attacker().getController().assignCombatDamage(stale.source(),
                new CardCollection(stale.blockers()), null, 4, stale.defender(), !stale.snapshot().legacyDamageOrder());
        Assert.assertTrue(stale.execution().isInvalid());
        Assert.assertEquals(fallback.get(stale.blockers().get(0)).intValue(), 4);
        Assert.assertFalse(fallback.containsKey(null), "Legacy behavior, rather than the stale spillover, must be retained");
    }

    @Test
    public void hiddenHandChangesDoNotInvalidatePublicExecutionAndNoGameIdsAreAllocated() {
        final Fixture f = fixture(3, 3, List.of("K:Trample"), List.of(List.of("PT:1/2")));
        addCardToZone("Giant Growth", f.defender(), ZoneType.Hand);
        f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE, f.attacker(), false);
        final int sentinel = f.game().nextCardId();
        final Map<Card, Integer> allocation = f.execution().assignDamage(f.source(), new CardCollection(f.blockers()), 3,
                f.defender(), !f.snapshot().legacyDamageOrder()).orElseThrow();
        Assert.assertEquals(allocation.get(null).intValue(), 1);
        Assert.assertEquals(allocation.get(f.blockers().get(0)).intValue(), 2);
        Assert.assertEquals(f.game().nextCardId(), sentinel + 1);
        Assert.assertEquals(f.blockers().get(0).getDamage(), 0);
        Assert.assertFalse(f.execution().isInvalid());
    }

    private Fixture fixture(final int power, final int toughness, final List<String> keywords,
            final List<List<String>> blockerScripts) {
        return fixture(power, toughness, keywords, blockerScripts, false);
    }

    private Fixture fixture(final int power, final int toughness, final List<String> keywords,
            final List<List<String>> blockerScripts, final boolean legacy) {
        final Game game = initAndCreateGame();
        game.getRules().setOrderCombatants(legacy);
        final Player attacker = game.getPlayers().get(0);
        final Player defender = game.getPlayers().get(1);
        attacker.setTeam(1);
        defender.setTeam(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, attacker);
        final Card source = creature(attacker, List.of("PT:" + power + "/" + toughness), keywords);
        final List<Card> blockers = blockerScripts.stream().map(script -> creature(defender, script, List.of())).toList();
        game.getAction().checkStaticAbilities();
        final Combat combat = new Combat(attacker);
        combat.addAttacker(source, defender);
        final PublicCombatSnapshot snapshot = PublicCombatSnapshot.capture(attacker, combat);
        final Map<Integer, PreparedCombatValuation.PermanentValue> ledger = new LinkedHashMap<>();
        snapshot.creatures().forEach((id, card) -> ledger.put(id, new PreparedCombatValuation.PermanentValue(id, card.controllerId(),
                card.controllerId() == attacker.getId() ? -100 : 100, 0, 0, List.of())));
        final CombatDamageOptimizer.Candidate choice = CombatDamageOptimizer.optimizeBlockGroups(snapshot,
                new CombatAssignment(snapshot.attackersToDefenders(), Map.of(source.getId(), blockers.stream().map(Card::getId).toList())),
                new PreparedCombatValuation(ledger, List.of(), ValuationCompleteness.PARTIAL, List.of()), new CombatSearchBudget(10000))
                .best().orElseThrow();
        blockers.forEach(blocker -> combat.addBlocker(source, blocker));
        combat.setBlocked(source, true);
        game.getPhaseHandler().setCombat(combat);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, attacker, false);
        final CombatExecutionPlan execution = CombatExecutionPlan.create(attacker, combat, snapshot,
                choice.assignment(), choice.damagePlan()).orElseThrow();
        return new Fixture(game, attacker, defender, combat, source, blockers, snapshot, choice, execution);
    }

    private static AtomicInteger installTestController(final Fixture f) {
        final AtomicInteger assigned = new AtomicInteger();
        final PlayerControllerAi controller = new PlayerControllerAi(f.game(), f.attacker(), f.attacker().getLobbyPlayer()) {
            @Override
            protected boolean isCombatPlanningEnabled() { return true; }

            @Override
            public Map<Card, Integer> assignCombatDamage(final Card attacker, final CardCollectionView blockers,
                    final CardCollectionView remaining, final int damage, final GameEntity defender, final boolean overrideOrder) {
                assigned.incrementAndGet();
                return super.assignCombatDamage(attacker, blockers, remaining, damage, defender, overrideOrder);
            }
        };
        f.attacker().dangerouslySetController(controller);
        Assert.assertTrue(controller.installCombatExecutionPlan(f.execution()));
        return assigned;
    }

    private static void resolve(final Fixture f) {
        f.combat().orderBlockersForDamageAssignment();
        f.combat().orderAttackersForDamageAssignment();
        for (final boolean first : List.of(true, false)) {
            f.game().getPhaseHandler().devModeSet(first ? PhaseType.COMBAT_FIRST_STRIKE_DAMAGE : PhaseType.COMBAT_DAMAGE,
                    f.attacker(), false);
            f.combat().removeAbsentCombatants();
            if (f.combat().assignCombatDamage(first)) { f.combat().dealAssignedDamage(); }
            f.game().getAction().checkStateEffects(true);
        }
    }

    private static void compare(final Fixture f) {
        Assert.assertEquals(f.attacker().getLife(), f.choice().projection().playerLifeAfter().get(f.attacker().getId()).intValue());
        Assert.assertEquals(f.defender().getLife(), f.choice().projection().playerLifeAfter().get(f.defender().getId()).intValue());
        final List<Card> all = new ArrayList<>(f.blockers());
        all.add(f.source());
        for (final Card card : all) {
            Assert.assertEquals(!card.isInPlay(), f.choice().projection().lostCreatures().contains(card.getId()));
            if (card.isInPlay()) { Assert.assertEquals(card.getDamage(), f.choice().projection().survivors().get(card.getId()).markedDamage()); }
        }
    }

    private static Card creature(final Player owner, final List<String> properties, final List<String> keywords) {
        final List<String> script = new ArrayList<>(List.of("Name:Execution Fixture", "ManaCost:2", "Types:Creature Human"));
        script.addAll(properties);
        script.addAll(keywords);
        final Card card = Card.fromPaperCard(new PaperCard(CardRules.fromScript(script), CardEdition.UNKNOWN_CODE, CardRarity.Special), owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());
        owner.getZone(ZoneType.Battlefield).add(card);
        card.setSickness(false);
        return card;
    }
}
