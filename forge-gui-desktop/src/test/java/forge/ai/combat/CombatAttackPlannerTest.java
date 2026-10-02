package forge.ai.combat;

import java.util.ArrayList;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiController;
import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.LobbyPlayerAi;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

public class CombatAttackPlannerTest extends AITest {
    private record Fixture(Game game, Player ai, Player opponent, Combat combat) { }

    @Test
    public void menaceAttackIgnoresAnOtherwiseProfitableButIllegalSoloBlock() {
        final Fixture f = fixture();
        final Card attacker = creature(f.ai(), 2, 2, List.of("K:Menace"));
        creature(f.opponent(), 4, 4, List.of());
        f.opponent().setLife(2, null);
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(20000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertTrue(plan.search().orElseThrow().best().orElseThrow().certifiedWin());
        Assert.assertTrue(plan.search().orElseThrow().best().orElseThrow().combat().assignment().blockersByAttacker().isEmpty());
        Assert.assertTrue(CombatAttackPlanner.tryDeclare(f.ai(), f.ai(), f.combat(), new CombatSearchBudget(20000)));
        Assert.assertEquals(f.combat().getAttackers(), List.of(attacker));
    }

    @Test
    public void overloadedAttackIsPlannedReadOnlyThenValidatedAndAppliedThroughGatedController() {
        final Fixture f = fixture();
        final Card first = creature(f.ai(), 2, 2, List.of());
        final Card second = creature(f.ai(), 2, 2, List.of());
        final Card blocker = creature(f.opponent(), 1, 3, List.of());
        final int sentinel = f.game().nextCardId();
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(20000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertEquals(plan.search().orElseThrow().best().orElseThrow().attackers(), List.of(first.getId(), second.getId()));
        Assert.assertTrue(f.combat().getAttackers().isEmpty());
        Assert.assertFalse(first.getView().isAttacking() || second.getView().isAttacking() || blocker.getView().isBlocking());
        Assert.assertEquals(f.game().nextCardId(), sentinel + 1);
        Assert.assertTrue(AiProfileUtil.getBoolProperty(f.ai(), AiProps.ENABLE_COMBAT_ATTACK_PLANNING));
        final AiController controller = new AiController(f.ai(), f.game());
        controller.declareAttackers(f.ai(), f.combat());
        Assert.assertEquals(f.combat().getAttackers().size(), 2);
        Assert.assertTrue(first.getView().isAttacking() && second.getView().isAttacking());
        Assert.assertFalse(first.isTapped() || second.isTapped(), "Rules engine, not the planner, handles attack tapping");
        Assert.assertTrue(CombatUtil.validateAttackers(f.combat()));
        Assert.assertEquals(controller.getAttackAggression(), 4);
    }

    @Test
    public void nextTurnRiskPreservesAnAnchorAndDoesNotDeclareHypotheticalEnemyBlocks() {
        final Fixture f = fixture();
        f.ai().setLife(4, null);
        final Card anchor = creature(f.ai(), 5, 5, List.of());
        creature(f.opponent(), 2, 2, List.of());
        creature(f.opponent(), 2, 2, List.of());
        Assert.assertTrue(CombatAttackPlanner.tryDeclare(f.ai(), f.ai(), f.combat(), new CombatSearchBudget(20000)));
        Assert.assertTrue(f.combat().getAttackers().isEmpty());
        Assert.assertTrue(f.combat().getAllBlockers().isEmpty());
        Assert.assertFalse(anchor.isTapped() || anchor.getView().isAttacking());
    }

    @Test
    public void certifiedImmediateLethalDoesNotRequireAnAvailableFutureReply() {
        final Fixture f = fixture();
        f.opponent().setLife(2, null);
        creature(f.ai(), 2, 2, List.of());
        creature(f.ai(), 2, 2, List.of());
        creature(f.opponent(), 4, 4, List.of());
        f.ai().getZone(ZoneType.Library).removeAllCards(true);
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(20000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertTrue(plan.search().orElseThrow().best().orElseThrow().certifiedWin());
        Assert.assertFalse(plan.search().orElseThrow().outcomeDomainComplete(), "Unneeded future branches remain explicitly incomplete");
        Assert.assertTrue(CombatAttackPlanner.tryDeclare(f.ai(), f.ai(), f.combat(), new CombatSearchBudget(20000)));
        Assert.assertEquals(f.combat().getAttackers().size(), 2);
        Assert.assertTrue(f.combat().getAllBlockers().isEmpty());
    }

    @Test
    public void fixedAttacksArePreservedUnknownActivationsAreApproximatedAndEmptyBudgetRemainsUntouched() {
        final Fixture fixed = fixture();
        final Card already = creature(fixed.ai(), 2, 2, List.of());
        fixed.combat().addAttacker(already, fixed.opponent());
        Assert.assertTrue(CombatAttackPlanner.tryDeclare(fixed.ai(), fixed.ai(), fixed.combat(), new CombatSearchBudget(20000)));
        Assert.assertEquals(fixed.combat().getAttackers(), List.of(already));
        final Fixture unsupported = fixture();
        final Card pump = creature(unsupported.ai(), 2, 2, List.of("A:AB$ Pump | Cost$ U | NumAtt$ 1 | NumDef$ -1 | Defined$ Self"));
        Assert.assertTrue(CombatAttackPlanner.tryDeclare(unsupported.ai(), unsupported.ai(), unsupported.combat(), new CombatSearchBudget(20000)));
        Assert.assertEquals(unsupported.combat().getAttackers(), List.of(pump));
        final Fixture budget = fixture();
        creature(budget.ai(), 2, 2, List.of());
        Assert.assertFalse(CombatAttackPlanner.tryDeclare(budget.ai(), budget.ai(), budget.combat(), new CombatSearchBudget(0)));
        Assert.assertTrue(budget.combat().getAttackers().isEmpty());
        Assert.assertFalse(CombatAttackPlanner.tryDeclare(budget.ai(), budget.opponent(), budget.combat(), new CombatSearchBudget(20000)));
        Assert.assertFalse(CombatAttackPlanner.tryDeclare(budget.ai(), budget.ai(), new Combat(budget.ai()), new CombatSearchBudget(20000)));
    }

    @Test
    public void fixedAttackBaselineCanBeExtendedToLethalWithoutDuplicatingOrMovingAttackers() {
        final Fixture f = fixture();
        final Card fixed = creature(f.ai(), 2, 2, List.of());
        final Card extra = creature(f.ai(), 2, 2, List.of());
        creature(f.opponent(), 4, 4, List.of());
        f.opponent().setLife(2, null);
        f.combat().addAttacker(fixed, f.opponent());
        final int sentinel = f.game().nextCardId();
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(20000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertEquals(plan.search().orElseThrow().noAttack().orElseThrow().attackers(), List.of(fixed.getId()));
        Assert.assertEquals(f.combat().getAttackers(), List.of(fixed), "Planning must not publish optional attacks");
        Assert.assertFalse(extra.getView().isAttacking());
        Assert.assertEquals(f.game().nextCardId(), sentinel + 1);
        Assert.assertTrue(plan.search().orElseThrow().best().orElseThrow().certifiedWin());
        Assert.assertTrue(CombatAttackPlanner.tryDeclare(f.ai(), f.ai(), f.combat(), new CombatSearchBudget(20000)));
        Assert.assertEquals(f.combat().getAttackers().size(), 2);
        Assert.assertTrue(f.combat().getAttackers().containsAll(List.of(fixed, extra)));
        Assert.assertEquals(f.combat().getDefenderByAttacker(fixed), f.opponent());
        Assert.assertTrue(CombatUtil.validateAttackers(f.combat()));
        Assert.assertTrue(f.combat().getAllBlockers().isEmpty());
    }

    @Test
    public void fixedAttackIsNotWithdrawnEvenWhenItsTradeWouldBeUnfavorable() {
        final Fixture f = fixture();
        final Card fixed = creature(f.ai(), 4, 1, List.of());
        creature(f.opponent(), 1, 1, List.of());
        f.combat().addAttacker(fixed, f.opponent());
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(20000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertEquals(plan.search().orElseThrow().best().orElseThrow().attackers(), List.of(fixed.getId()));
        Assert.assertTrue(CombatAttackPlanner.tryDeclare(f.ai(), f.ai(), f.combat(), new CombatSearchBudget(20000)));
        Assert.assertEquals(f.combat().getAttackers(), List.of(fixed));
        Assert.assertEquals(f.combat().getDefenderByAttacker(fixed), f.opponent());
    }

    @Test
    public void currentAttackReadinessExcludesTappedSickAndDefenderButAllowsHaste() {
        final Fixture f = fixture();
        final Card tapped = creature(f.ai(), 2, 2, List.of());
        tapped.setTapped(true);
        final Card sick = creature(f.ai(), 2, 2, List.of());
        sick.setSickness(true);
        final Card wall = creature(f.ai(), 0, 4, List.of("K:Defender"));
        final Card haste = creature(f.ai(), 2, 2, List.of("K:Haste"));
        haste.setSickness(true);
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(20000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertEquals(plan.snapshot().orElseThrow().attackersToDefenders().keySet(), java.util.Set.of(haste.getId()));
        Assert.assertFalse(tapped.getView().isAttacking() || sick.getView().isAttacking() || wall.getView().isAttacking());
    }

    @Test
    public void completedGreedyDeclarationIsAppliedWithoutClaimingAllSubsetsWereSearched() {
        final Fixture f = fixture();
        final Card first = creature(f.ai(), 2, 2, List.of());
        final Card second = creature(f.ai(), 2, 2, List.of());
        final Card third = creature(f.ai(), 2, 2, List.of());
        creature(f.opponent(), 1, 3, List.of());
        final var plan = CombatAttackPlanner.plan(f.ai(), f.combat(), new CombatSearchBudget(20000));
        Assert.assertTrue(plan.applicable(), plan.reasons().toString());
        Assert.assertTrue(plan.search().orElseThrow().candidateSearchComplete());
        Assert.assertFalse(plan.search().orElseThrow().searchExhaustive());
        Assert.assertTrue(CombatAttackPlanner.tryDeclare(f.ai(), f.ai(), f.combat(), new CombatSearchBudget(20000)));
        Assert.assertEquals(java.util.Set.copyOf(f.combat().getAttackers()), java.util.Set.of(first, second, third));
    }

    private Fixture fixture() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(0);
        final Player opponent = game.getPlayers().get(1);
        ai.setTeam(0);
        opponent.setTeam(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Mastermind");
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, ai);
        final Combat combat = new Combat(ai);
        game.getPhaseHandler().setCombat(combat);
        final PaperCard library = new PaperCard(CardRules.fromScript(List.of("Name:Attack Library Fixture", "Types:Land")),
                CardEdition.UNKNOWN_CODE, CardRarity.Special);
        for (final Player player : game.getPlayers()) {
            for (int index = 0; index < 4; index++) { player.getZone(ZoneType.Library).add(Card.fromPaperCard(library, player)); }
        }
        return new Fixture(game, ai, opponent, combat);
    }

    private static Card creature(final Player owner, final int power, final int toughness, final List<String> extra) {
        final List<String> script = new ArrayList<>(List.of("Name:Attack Planner Fixture", "ManaCost:2",
                "Types:Creature Human", "PT:" + power + "/" + toughness));
        script.addAll(extra);
        final Card card = Card.fromPaperCard(new PaperCard(CardRules.fromScript(script), CardEdition.UNKNOWN_CODE, CardRarity.Special), owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());
        owner.getZone(ZoneType.Battlefield).add(card);
        card.setSickness(false);
        return card;
    }
}
