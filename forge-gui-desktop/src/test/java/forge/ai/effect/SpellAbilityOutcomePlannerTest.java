package forge.ai.effect;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.PlayerResourceValueEvaluator;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class SpellAbilityOutcomePlannerTest extends AITest {
    @Test
    public void simultaneousSacrificeBindsAllPlayersInActivePlayerOrder() {
        final Card source = source();
        final Player ai = source.getController();
        final Player active = ai.getOpponents().get(0);
        source.getGame().getPhaseHandler().devModeSet(forge.game.phase.PhaseType.MAIN1, active);
        final SpellAbility sacrifice = ability(source, "DB$ Sacrifice | Defined$ Player | SacValid$ Creature");
        final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(sacrifice, ai);
        Assert.assertTrue(plan.supported(), plan.reason());
        Assert.assertEquals(plan.decisions().size(), 3);
        Assert.assertEquals(plan.decisions().get(0).id(), "sacrifice:" + sacrifice.getId() + ":" + active.getId());
        Assert.assertEquals(plan.decisions().get(1).id(), "sacrifice:" + sacrifice.getId() + ":" + ai.getId());
        Assert.assertEquals(plan.decisions().get(2).kind(), OutcomePlan.DecisionKind.EFFECT);
        Assert.assertEquals(plan.state().battlefield(ai).stream().filter(Card::isCreature).count(), 0L);
        Assert.assertEquals(ai.getCreaturesInPlay().size(), 1);
        Assert.assertEquals(active.getCreaturesInPlay().size(), 1);
    }

    @Test
    public void copiedOpponentTokensCanBeSacrificedDuringResolution() {
        final Card source = source();
        final Player player = source.getController();
        final Card creature = player.getCreaturesInPlay().get(0);
        creature.setSVar("SacCopies", "DB$ Sacrifice | Defined$ Opponent | SacValid$ Creature.token | Amount$ 2");
        final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(ability(creature,
                "DB$ CopyPermanent | Defined$ Self | Controller$ Opponent | NumCopies$ 2 | SubAbility$ SacCopies"), player);
        Assert.assertTrue(plan.supported(), plan.reason());
        Assert.assertEquals(plan.state().createdTokens.size(), 2);
        Assert.assertEquals(plan.state().battlefield(player).stream().filter(Card::isToken).count(), 0L);
        Assert.assertEquals(plan.value(), 0.0);
    }

    @Test
    public void counterTransferPreservesHistoricalSourceAndSupportsAmountOverride() {
        final Card source = source();
        final Player player = source.getController();
        final Card creature = player.getCreaturesInPlay().get(0);
        creature.setCounters(CounterEnumType.P1P1, 3);
        final Card historical = forge.game.card.CardCopyService.getLKICopy(creature);
        creature.setCounters(CounterEnumType.P1P1, 0);
        final SpellAbility transfer = ability(source, "DB$ PutCounter | ValidTgts$ Creature.YouCtrl"
                + " | CounterType$ EachFromSource | EachFromSource$ TriggeredCardLKICopy");
        transfer.setTriggeringObject(forge.game.ability.AbilityKey.Card, historical);
        final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(transfer, player);
        Assert.assertTrue(plan.supported(), plan.reason());
        Assert.assertEquals(plan.state().card(creature).getCounters(CounterEnumType.P1P1), 3);
        final SpellAbility limited = ability(source, "DB$ PutCounter | ValidTgts$ Creature.YouCtrl"
                + " | CounterType$ EachFromSource | EachFromSource$ TriggeredCardLKICopy | CounterNum$ 1");
        limited.setTriggeringObject(forge.game.ability.AbilityKey.Card, historical);
        final OutcomePlan<OutcomeState> override = SpellAbilityOutcomePlanner.evaluate(limited, player);
        Assert.assertTrue(override.supported(), override.reason());
        Assert.assertEquals(override.state().card(creature).getCounters(CounterEnumType.P1P1), 1);
        Assert.assertEquals(creature.getCounters(CounterEnumType.P1P1), 0);
    }

    @Test
    public void createdTokensAreDistinctSacrificeChoicesWithoutLiveMutation() {
        final Card source = source();
        final Player player = source.getController();
        final int before = player.getCreaturesInPlay().size();
        source.setSVar("SacToken", "DB$ Sacrifice | Defined$ You | SacValid$ Creature.token | Amount$ 1");
        final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(ability(source,
                "DB$ Token | TokenScript$ br_1_1_goblin | TokenAmount$ 2 | SubAbility$ SacToken"), player);
        Assert.assertTrue(plan.supported(), plan.reason());
        Assert.assertEquals(plan.state().createdTokens.size(), 2);
        Assert.assertNotEquals(plan.state().createdTokens.get(0).getId(), plan.state().createdTokens.get(1).getId());
        Assert.assertEquals(plan.state().battlefield(player).stream().filter(Card::isToken).count(), 1L);
        Assert.assertEquals(player.getCreaturesInPlay().size(), before);
        Assert.assertTrue(plan.value() < 0);
    }

    @Test
    public void transferredCountersUseCombinedDeltaAndRejectUnknownTypes() {
        final Card source = source();
        final Player player = source.getController();
        final Card creature = player.getCreaturesInPlay().get(0);
        creature.setCounters(CounterEnumType.P1P1, 2);
        creature.setCounters(forge.game.card.CounterType.getType("Flying"), 1);
        final SpellAbility transfer = ability(creature,
                "DB$ PutCounter | Defined$ Self | CounterType$ EachFromSource | EachFromSource$ Self");
        final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(transfer, player);
        Assert.assertTrue(plan.supported(), plan.reason());
        Assert.assertEquals(plan.state().card(creature).getCounters(CounterEnumType.P1P1), 4);
        Assert.assertEquals(plan.state().card(creature).getCounters(forge.game.card.CounterType.getType("Flying")), 2);
        Assert.assertEquals(creature.getCounters(CounterEnumType.P1P1), 2);
        creature.setCounters(CounterEnumType.CHARGE, 1);
        Assert.assertFalse(SpellAbilityOutcomePlanner.evaluate(transfer, player).supported());
    }

    private Card source() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        for (final Player player : game.getPlayers()) {
            player.setLife(20, null);
            addCard("Grizzly Bears", player);
            for (int i = 0; i < 8; i++) { addCardToZone("Forest", player, ZoneType.Library); }
        }
        return addCard("Sol Ring", ai);
    }

    private SpellAbility ability(final Card source, final String script) {
        final SpellAbility ability = AbilityFactory.getAbility(script + " | SpellDescription$ Planner test", source);
        ability.setActivatingPlayer(source.getController());
        return ability;
    }

    @Test
    public void sharedPlayerReferenceAndIndependentTargets() {
        final Card source = source();
        source.setSVar("SharedSac", "DB$ Sacrifice | Defined$ TargetedPlayer | SacValid$ Creature");
        source.setSVar("SeparateSac", "DB$ Sacrifice | ValidTgts$ Player | SacValid$ Creature");
        final SpellAbility shared = ability(source, "DB$ Draw | ValidTgts$ Player | SubAbility$ SharedSac");
        final SpellAbility separate = ability(source, "DB$ Draw | ValidTgts$ Player | SubAbility$ SeparateSac");
        final OutcomePlan<OutcomeState> sharedPlan = SpellAbilityOutcomePlanner.evaluate(shared, source.getController());
        final OutcomePlan<OutcomeState> separatePlan = SpellAbilityOutcomePlanner.evaluate(separate, source.getController());
        Assert.assertTrue(sharedPlan.supported(), sharedPlan.reason());
        Assert.assertTrue(separatePlan.supported(), separatePlan.reason());
        Assert.assertEquals(sharedPlan.decisions().stream().filter(d -> d.id().startsWith("target:")).count(), 1L);
        Assert.assertEquals(separatePlan.decisions().stream().filter(d -> d.id().startsWith("target:")).count(), 2L);
        Assert.assertTrue(separatePlan.value() < sharedPlan.value());
        Assert.assertTrue(shared.getTargets().isEmpty());
        final OutcomePlan.Decision effect = sharedPlan.decisions().stream()
                .filter(d -> d.kind() == OutcomePlan.DecisionKind.EFFECT).findFirst().orElseThrow();
        Assert.assertEquals(effect.selections().size(), 1);
        Assert.assertFalse(((SpellAbility) effect.selections().get(0)).getTargets().isEmpty());
        Assert.assertTrue(separate.getSubAbility().getTargets().isEmpty());
        Assert.assertEquals(source.getController().getCardsIn(ZoneType.Hand).size(), 0);
        Assert.assertEquals(source.getController().getCreaturesInPlay().size(), 1);
    }

    @Test
    public void sequentialDrawsUseProjectedHandSize() {
        final Card source = source();
        source.setSVar("Again", "DB$ Draw | Defined$ You | NumCards$ 1");
        final SpellAbility draw = ability(source, "DB$ Draw | Defined$ You | NumCards$ 1 | SubAbility$ Again");
        final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(draw, source.getController());
        Assert.assertTrue(plan.supported(), plan.reason());
        Assert.assertEquals(plan.value(), -(double) PlayerResourceValueEvaluator.evaluateCardDraw(0, 2));
        Assert.assertEquals(plan.state().hand(source.getController()), 2);
        Assert.assertEquals(source.getController().getCardsIn(ZoneType.Hand).size(), 0);
    }

    @Test
    public void nestedChoiceAndCounterAlternativesAreGeneric() {
        final Card source = source();
        source.setSVar("Draw", "DB$ Draw | Defined$ You");
        source.setSVar("Life", "DB$ GainLife | Defined$ You | LifeAmount$ 3");
        source.setSVar("Nested", "DB$ Charm | Choices$ Draw,Life | SpellDescription$ Nested choice");
        source.setSVar("Counter", "DB$ PutCounter | ValidTgts$ Creature.YouCtrl | CounterType$ P1P1,Flying,Lifelink");
        final SpellAbility charm = ability(source, "DB$ Charm | Choices$ Nested,Counter");
        final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(charm, source.getController());
        Assert.assertTrue(plan.supported(), plan.reason());
        Assert.assertTrue(plan.value() < 0);
        Assert.assertTrue(plan.decisions().size() >= 2);
        Assert.assertEquals(source.getController().getCreaturesInPlay().get(0).getCounters(CounterEnumType.P1P1), 0);
    }

    @Test
    public void chooseTwoMixedModesWithSharedPlayerChain() {
        final Card source = source();
        source.setSVar("Damage", "DB$ DealDamage | ValidTgts$ Creature,Player,Planeswalker | NumDmg$ 3");
        source.setSVar("Counter", "DB$ PutCounter | ValidTgts$ Creature | CounterType$ P1P1");
        source.setSVar("DrawSac", "DB$ Draw | ValidTgts$ Player | SubAbility$ Sac");
        source.setSVar("Sac", "DB$ Sacrifice | Defined$ ParentTarget | SacValid$ Creature");
        final SpellAbility charm = ability(source, "DB$ Charm | CharmNum$ 2 | Choices$ Damage,Counter,DrawSac");
        final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(charm, source.getController());
        Assert.assertTrue(plan.supported(), plan.reason());
        Assert.assertEquals(plan.decisions().get(0).selections().size(), 2);
        Assert.assertTrue(plan.value() < 0);
    }

    @Test
    public void multipleTargetsAndUpToZero() {
        final Card source = source();
        final SpellAbility draw = ability(source, "DB$ Draw | ValidTgts$ Player | TargetMin$ 2 | TargetMax$ 2");
        final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(draw, source.getController());
        Assert.assertTrue(plan.supported(), plan.reason());
        Assert.assertEquals(plan.value(), 0.0);
        Assert.assertEquals(plan.state().targets.get(draw).size(), 2);
        final SpellAbility harmful = ability(source, "DB$ LoseLife | ValidTgts$ Player.You | LifeAmount$ 3 | TargetMin$ 0 | TargetMax$ 1");
        final OutcomePlan<OutcomeState> optional = SpellAbilityOutcomePlanner.evaluate(harmful, source.getController());
        Assert.assertTrue(optional.supported(), optional.reason());
        Assert.assertEquals(optional.value(), 0.0);
    }

    @Test
    public void randomCharmUsesMeanAndRetainsBranches() {
        final Card source = source();
        source.setSVar("Small", "DB$ GainLife | Defined$ You | LifeAmount$ 1");
        source.setSVar("Large", "DB$ GainLife | Defined$ You | LifeAmount$ 10");
        final SpellAbility random = ability(source, "DB$ Charm | Random$ True | Choices$ Small,Large");
        final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(random, source.getController());
        Assert.assertTrue(plan.supported(), plan.reason());
        Assert.assertEquals(plan.branches().size(), 2);
        Assert.assertEquals(plan.value(), plan.branches().stream().mapToDouble(OutcomePlan::value).average().orElseThrow());
        Assert.assertEquals(source.getController().getLife(), 20);
    }

    @Test
    public void unsupportedModeDoesNotBecomeFreeZeroValueOption() {
        final Card source = source();
        source.setSVar("Supported", "DB$ Draw | Defined$ You");
        source.setSVar("Unknown", "DB$ Scry | ScryNum$ 3");
        final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(
                ability(source, "DB$ Charm | Choices$ Supported,Unknown"), source.getController());
        Assert.assertFalse(plan.supported());
    }

    @Test
    public void distinctTargetConstraintIsEnforcedAcrossChain() {
        final Card source = source();
        source.setSVar("Again", "DB$ Draw | ValidTgts$ Player | TargetUnique$ True");
        final SpellAbility draw = ability(source, "DB$ Draw | ValidTgts$ Player | SubAbility$ Again");
        final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(draw, source.getController());
        Assert.assertTrue(plan.supported(), plan.reason());
        Assert.assertNotEquals(plan.state().targets.get(draw), plan.state().targets.get(draw.getSubAbility()));
        Assert.assertEquals(plan.value(), 0.0);
    }

    @Test
    public void lethalDamageCanBeSavedByLaterCountersBeforeStateBasedActions() {
        final Card source = source();
        final Card creature = source.getController().getCreaturesInPlay().get(0);
        source.setSVar("Save", "DB$ PutCounter | Defined$ Targeted | CounterType$ P1P1 | CounterNum$ 2");
        final SpellAbility save = ability(source,
                "DB$ DealDamage | ValidTgts$ Creature.YouCtrl | NumDmg$ 3 | SubAbility$ Save");
        final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(save, source.getController());
        Assert.assertTrue(plan.supported(), plan.reason());
        Assert.assertTrue(plan.value() < 0, "The creature survives as a larger creature: " + plan.value());
        Assert.assertEquals(plan.state().card(creature).getCounters(CounterEnumType.P1P1), 2);
        Assert.assertEquals(plan.state().card(creature).getDamage(), 3);
        Assert.assertEquals(creature.getDamage(), 0);
    }

    @Test
    public void repeatedTargetedModesCanSelectDifferentRecipients() {
        final Card source = source();
        addCard("Runeclaw Bear", source.getController().getOpponents().get(0));
        source.setSVar("Remove", "DB$ Destroy | ValidTgts$ Creature.OppCtrl | NoRegen$ True");
        final SpellAbility repeat = ability(source,
                "DB$ Charm | CharmNum$ 2 | CanRepeatModes$ True | Choices$ Remove");
        final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(repeat, source.getController());
        Assert.assertTrue(plan.supported(), plan.reason());
        Assert.assertEquals(plan.state().cards.values().stream().filter(java.util.Objects::isNull).count(), 2L);
    }

    @Test
    public void genericOpponentChoiceAndRandomMultipleModes() {
        final Card source = source();
        source.setSVar("Small", "DB$ GainLife | Defined$ You | LifeAmount$ 1");
        source.setSVar("Large", "DB$ GainLife | Defined$ You | LifeAmount$ 10");
        source.setSVar("Draw", "DB$ Draw | Defined$ You");
        final OutcomePlan<OutcomeState> opponentChoice = SpellAbilityOutcomePlanner.evaluate(
                ability(source, "DB$ GenericChoice | Defined$ Opponent | Choices$ Small,Large"), source.getController());
        Assert.assertTrue(opponentChoice.supported(), opponentChoice.reason());
        final OutcomePlan<OutcomeState> small = SpellAbilityOutcomePlanner.evaluate(
                ability(source, "DB$ GainLife | Defined$ You | LifeAmount$ 1"), source.getController());
        Assert.assertEquals(opponentChoice.value(), small.value());
        final OutcomePlan<OutcomeState> random = SpellAbilityOutcomePlanner.evaluate(
                ability(source, "DB$ Charm | Random$ True | CharmNum$ 2 | Choices$ Small,Large,Draw"), source.getController());
        Assert.assertTrue(random.supported(), random.reason());
        Assert.assertEquals(random.branches().size(), 3);
    }

    @Test
    public void laterDamageObservesEarlierSourceKeywordChange() {
        final Card ring = source();
        final Player ai = ring.getController();
        final Card source = ai.getCreaturesInPlay().get(0);
        final Card victim = ai.getOpponents().get(0).getCreaturesInPlay().get(0);
        source.setSVar("Hit", "DB$ DealDamage | ValidTgts$ Creature.OppCtrl | NumDmg$ 1");
        final SpellAbility combo = ability(source,
                "DB$ Pump | Defined$ Self | KW$ Deathtouch | Duration$ Permanent | SubAbility$ Hit");
        final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(combo, ai);
        Assert.assertTrue(plan.supported(), plan.reason());
        Assert.assertTrue(plan.value() < -forge.ai.ComputerUtilCard.evaluatePermanent(ai, victim));
        Assert.assertTrue(plan.state().card(victim).hasBeenDealtDeathtouchDamage());
        Assert.assertFalse(source.hasKeyword(forge.game.keyword.Keyword.DEATHTOUCH));
        Assert.assertEquals(victim.getDamage(), 0);
    }
}
