package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.ComputerUtilAbility;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.ApiType;
import forge.game.ability.SpellApiBased;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Regression coverage for the shared action valuation boundary. */
public class UnifiedActionValueEvaluatorTest extends AITest {
    @Test
    public void removalActionComposesTheExistingPermanentAndTransitionEvaluators() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        final Card target = addCard("Grizzly Bears", opponent);
        final ValuationContext context = ValuationContext.forRemoval(ai, 0, 0);

        final CardValueBreakdown expected = RemovalActionEvaluator.evaluate(ai, target,
                UnifiedCardValueEvaluator.evaluatePermanent(target, context),
                RemovalActionKind.BOUNCE);
        final CardValueBreakdown actual = UnifiedActionValueEvaluator.evaluate(
                new RemovalValuationAction(target, RemovalActionKind.BOUNCE), context);

        Assert.assertEquals(actual, expected);
    }

    @Test
    public void nullTraceUsesTheSameDisabledDiagnosticsBehavior() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card target = addCard("Grizzly Bears", game.getPlayers().get(0));
        final ValuationContext context = ValuationContext.forRemoval(ai, 0, 0);

        final CardValueBreakdown result = UnifiedCardValueEvaluator.evaluatePermanent(target,
                context, null);

        Assert.assertEquals(result, UnifiedCardValueEvaluator.evaluatePermanent(target, context));
    }

    @Test
    public void castActionValuesACompleteImmediateOutcomeAndChargesResources() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card source = addCardToZone("Divination", ai, ZoneType.Hand);
        addCardToZone("Forest", ai, ZoneType.Library);
        final SpellAbility draw = new SpellApiBased(ApiType.Draw, source, new Cost("1", false),
                null, Map.of("Defined", "You", "NumCards", "1"));
        draw.setActivatingPlayer(ai);

        final CardValueBreakdown result = UnifiedActionValueEvaluator.evaluate(
                new CastValuationAction(draw), ValuationContext.forCast(ai, true));

        Assert.assertTrue(result.isComplete(), result.toString());
        Assert.assertTrue(result.transitionValue() > 0, result.toString());
        Assert.assertEquals(result.accessCost(), 25 + 90);
        Assert.assertTrue(result.netValue() < result.transitionValue());
    }

    @Test
    public void activationActionValuesACompleteImmediateOutcomeAndChargesManaOnly() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card source = addCard("Grizzly Bears", ai);
        addCard("Forest", ai);
        final SpellAbility gainLife = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ 1 | Defined$ You | LifeAmount$ 2", source);
        gainLife.setActivatingPlayer(ai);

        final CardValueBreakdown result = UnifiedActionValueEvaluator.evaluate(
                new ActivateValuationAction(source, gainLife),
                ValuationContext.forActivation(ai, true));

        Assert.assertTrue(result.isComplete(), result.toString());
        Assert.assertTrue(result.transitionValue() > 0, result.toString());
        Assert.assertEquals(result.accessCost(), 25);
        Assert.assertTrue(result.netValue() < result.transitionValue());
    }

    @Test
    public void activationActionValuesAFixedLifePayment() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card source = addCard("Grizzly Bears", ai);
        final SpellAbility gainLife = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ PayLife<3> | Defined$ You | LifeAmount$ 20", source);
        gainLife.setActivatingPlayer(ai);

        final CardValueBreakdown result = UnifiedActionValueEvaluator.evaluate(
                new ActivateValuationAction(source, gainLife),
                ValuationContext.forActivation(ai, true));

        Assert.assertTrue(result.isComplete(), result.toString());
        Assert.assertTrue(result.transitionValue() > 0, result.toString());
        Assert.assertTrue(result.accessCost() > 0, result.toString());
    }

    @Test
    public void sourceBoundSacrificeActivationIncludesTheSourceResource() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card source = addCard("Grizzly Bears", ai);
        addCard("Forest", ai);
        final SpellAbility sacrificeForLife = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ 1 Sac<1/CARDNAME> | Defined$ You | LifeAmount$ 20", source);
        sacrificeForLife.setActivatingPlayer(ai);

        final CardValueBreakdown result = UnifiedActionValueEvaluator.evaluate(
                new ActivateValuationAction(source, sacrificeForLife),
                ValuationContext.forActivation(ai, true));

        Assert.assertTrue(result.isComplete(), result.toString());
        Assert.assertTrue(result.transitionValue() > 0, result.toString());
    }

    @Test
    public void manaCombinationSelectorBoundsRepeatableNonTapActivations() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card source = addCard("Grizzly Bears", ai);
        for (int i = 0; i < 4; i++) {
            addCard("Forest", ai);
        }
        final SpellAbility gainLife = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ 1 | Defined$ You | LifeAmount$ 2", source);
        gainLife.setActivatingPlayer(ai);

        final ManaActionCombinationSelector.Selection selection =
                ManaActionCombinationSelector.select(ai, List.of(gainLife), true);

        Assert.assertTrue(selection.hasAction(), selection.toString());
        Assert.assertEquals(selection.usedMana(), 4, selection.toString());
        Assert.assertEquals(selection.actions().size(), 4, selection.toString());
    }

    @Test
    public void manaCombinationSelectorDoesNotUseASacrificedManaSourceToPayItsOwnCost() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card source = addCard("Llanowar Elves", ai);
        final SpellAbility sacrificeForLife = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ 1 Sac<1/CARDNAME> | Defined$ You | LifeAmount$ 20", source);
        sacrificeForLife.setActivatingPlayer(ai);

        final ManaActionCombinationSelector.Selection selection =
                ManaActionCombinationSelector.select(ai, List.of(sacrificeForLife), true);

        Assert.assertFalse(selection.hasAction(), selection.toString());
    }

    @Test
    public void activationTieBreakerUsesSharedValueOnlyForAnExactLegacyTie() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card weakSource = addCard("Grizzly Bears", ai);
        final Card strongSource = addCard("Grizzly Bears", ai);
        addCard("Forest", ai);
        final SpellAbility weak = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ 1 | Defined$ You | LifeAmount$ 1", weakSource);
        final SpellAbility strong = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ 1 | Defined$ You | LifeAmount$ 20", strongSource);
        weak.setActivatingPlayer(ai);
        strong.setActivatingPlayer(ai);
        final List<SpellAbility> abilities = new ArrayList<>(List.of(weak, strong));

        Assert.assertEquals(ComputerUtilAbility.saEvaluator.compare(weak, strong), 0);
        ActivateAbilityValueTieBreaker.apply(ai, abilities);

        Assert.assertSame(abilities.get(0), strong);
    }

    @Test
    public void discardActionUsesKnownHandValueWithOpponentPolarity() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        final Card weak = addCardToZone("Craw Wurm", opponent, ZoneType.Hand);
        final Card strong = addCardToZone("Colossal Dreadmaw", opponent, ZoneType.Hand);
        final ValuationContext context = ValuationContext.forDiscard(ai, true);

        final CardValueBreakdown weakValue = UnifiedActionValueEvaluator.evaluate(
                new DiscardValuationAction(weak, opponent), context);
        final CardValueBreakdown strongValue = UnifiedActionValueEvaluator.evaluate(
                new DiscardValuationAction(strong, opponent), context);

        Assert.assertTrue(weakValue.isComplete(), weakValue.toString());
        Assert.assertTrue(strongValue.isComplete(), strongValue.toString());
        Assert.assertTrue(weakValue.transitionValue() > 0, weakValue.toString());
        Assert.assertTrue(strongValue.transitionValue() > weakValue.transitionValue(),
                strongValue + " <= " + weakValue);
    }

    @Test
    public void discardingOwnCardHasTheOppositePolarity() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card card = addCardToZone("Grizzly Bears", ai, ZoneType.Hand);

        final CardValueBreakdown result = UnifiedActionValueEvaluator.evaluate(
                new DiscardValuationAction(card, ai), ValuationContext.forDiscard(ai, true));

        Assert.assertTrue(result.isComplete(), result.toString());
        Assert.assertTrue(result.transitionValue() < 0, result.toString());
    }

    @Test
    public void genericActionSelectorChoosesTheBestCompleteAction() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        final Card weak = addCardToZone("Craw Wurm", opponent, ZoneType.Hand);
        final Card strong = addCardToZone("Colossal Dreadmaw", opponent, ZoneType.Hand);
        final List<Card> candidates = new ArrayList<>(List.of(weak, strong));

        final ActionValueSelector.Selection<Card> selection = ActionValueSelector.selectBest(
                candidates, ValuationContext.forDiscard(ai, true), card -> true,
                card -> new DiscardValuationAction(card, opponent));

        Assert.assertTrue(selection.valuationUsed(), selection.reason());
        Assert.assertSame(selection.selected(), strong);
        Assert.assertEquals(selection.orderedCandidates(), List.of(strong, weak));
    }

    @Test
    public void genericActionSelectorPreservesFallbackForUnsupportedActions() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card target = addCard("Grizzly Bears", game.getPlayers().get(0));
        final ValuationAction action = new RemovalValuationAction(target, RemovalActionKind.EXILE);
        final List<ValuationAction> candidates = new ArrayList<>(List.of(action, action));

        final ActionValueSelector.Selection<ValuationAction> selection =
                ActionValueSelector.selectBest(candidates, ValuationContext.forDiscard(ai, true),
                        candidate -> true, candidate -> candidate);

        Assert.assertFalse(selection.valuationUsed(), selection.reason());
        Assert.assertSame(selection.selected(), action);
        Assert.assertEquals(selection.orderedCandidates(), candidates);
    }

    @Test
    public void manaCombinationSelectorCanPreferTwoSmallerSpellsOverOneLargerSpell() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCard("Forest", ai);
        addCard("Forest", ai);
        addCard("Forest", ai);
        addCard("Forest", ai);
        addCardToZone("Grizzly Bears", ai, ZoneType.Hand);
        addCardToZone("Grizzly Bears", ai, ZoneType.Hand);
        addCardToZone("Hill Giant", ai, ZoneType.Hand);

        final List<SpellAbility> abilities = ComputerUtilAbility.getSpellAbilities(
                new CardCollection(ai.getCardsIn(ZoneType.Hand)), ai);
        final ManaActionCombinationSelector.Selection selection =
                ManaActionCombinationSelector.select(ai, abilities, true);

        Assert.assertTrue(selection.hasAction(), selection.toString());
        Assert.assertEquals(selection.usedMana(), 4, selection.toString());
        Assert.assertEquals(selection.actions().size(), 2, selection.toString());
        Assert.assertEquals(selection.firstAction().getHostCard().getName(), "Grizzly Bears");
    }

    @Test
    public void manaCombinationSelectorCanAnchorAPlanToTheLegacyFirstAction() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        for (int i = 0; i < 6; i++) {
            addCard("Forest", ai);
        }
        addCardToZone("Savannah Lions", ai, ZoneType.Hand);
        final Card colossalDreadmaw = addCardToZone("Colossal Dreadmaw", ai, ZoneType.Hand);

        final List<SpellAbility> abilities = ComputerUtilAbility.getSpellAbilities(
                new CardCollection(ai.getCardsIn(ZoneType.Hand)), ai);
        final SpellAbility legacyFirst = abilities.stream()
                .filter(ability -> ability.getHostCard() == colossalDreadmaw)
                .findFirst()
                .orElseThrow();
        final ManaActionCombinationSelector.Selection selection =
                ManaActionCombinationSelector.selectWithFirstAction(ai, abilities, true,
                        legacyFirst);

        Assert.assertTrue(selection.hasAction(), selection.toString());
        Assert.assertSame(selection.firstAction(), legacyFirst);
        Assert.assertTrue(selection.actions().contains(legacyFirst), selection.toString());
    }

    @Test
    public void actionCombinationGatePreservesLegacyOrderingForFallbackOrIncompletePlans() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card legacyCard = addCardToZone("Savannah Lions", ai, ZoneType.Hand);
        final Card alternativeCard = addCardToZone("Grizzly Bears", ai, ZoneType.Hand);
        final SpellAbility legacyAction = legacyCard.getSpellAbilities().get(0);
        final SpellAbility alternativeAction = alternativeCard.getSpellAbilities().get(0);
        final List<SpellAbility> abilities = new ArrayList<>(
                List.of(legacyAction, alternativeAction));
        final ManaActionCombinationSelector.Selection legacyPlan = selection(legacyAction,
                100, 0, 0, 0);

        for (final ManaActionCombinationSelector.Selection incompletePlan : List.of(
                selection(alternativeAction, 200, 1, 0, 0),
                selection(alternativeAction, 200, 0, 1, 0),
                selection(alternativeAction, 200, 0, 0, 1))) {
            final ActionCombinationOverrideGate.Decision decision =
                    ActionCombinationOverrideGate.evaluate(legacyAction,
                    legacyPlan, incompletePlan, true, false, false, false, 20);

            Assert.assertFalse(decision.shouldOverride(), decision.toString());
            ManaActionCombinationSelector.reorder(abilities,
                    decision.shouldOverride() ? incompletePlan : null);
            Assert.assertSame(abilities.get(0), legacyAction);
        }
    }

    @Test
    public void actionCombinationGateReordersOnlyForACompletePlanAboveThreshold() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card legacyCard = addCardToZone("Savannah Lions", ai, ZoneType.Hand);
        final Card alternativeCard = addCardToZone("Grizzly Bears", ai, ZoneType.Hand);
        final SpellAbility legacyAction = legacyCard.getSpellAbilities().get(0);
        final SpellAbility alternativeAction = alternativeCard.getSpellAbilities().get(0);
        final List<SpellAbility> abilities = new ArrayList<>(
                List.of(legacyAction, alternativeAction));
        final ManaActionCombinationSelector.Selection legacyPlan = selection(legacyAction,
                100, 0, 0, 0);
        final ManaActionCombinationSelector.Selection proposedPlan = selection(alternativeAction,
                120, 0, 0, 0);

        final ActionCombinationOverrideGate.Decision decision =
                ActionCombinationOverrideGate.evaluate(legacyAction,
                legacyPlan, proposedPlan, true, false, false, false, 20);
        if (decision.shouldOverride()) {
            ManaActionCombinationSelector.reorder(abilities, proposedPlan);
        }

        Assert.assertTrue(decision.shouldOverride(), decision.toString());
        Assert.assertSame(abilities.get(0), alternativeAction);
    }

    @Test
    public void comparisonDistinguishesNoAlternativeFromLegacyRejectedAlternative() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCard("Forest", ai);
        final Card legacyCard = addCardToZone("Savannah Lions", ai, ZoneType.Hand);
        final Card alternativeCard = addCardToZone("Grizzly Bears", ai, ZoneType.Hand);
        final SpellAbility legacyAction = legacyCard.getSpellAbilities().get(0);
        final SpellAbility alternativeAction = alternativeCard.getSpellAbilities().get(0);
        final ActionDecisionSnapshot snapshot = ActionDecisionSnapshot.capture(ai);

        final ManaActionCombinationSelector.Comparison noAlternative =
                ManaActionCombinationSelector.compare(ai, List.of(legacyAction), true,
                        legacyAction, snapshot, ignored -> true);
        final ManaActionCombinationSelector.Comparison rejectedAlternative =
                ManaActionCombinationSelector.compare(ai,
                        List.of(legacyAction, alternativeAction), true, legacyAction, snapshot,
                        ability -> ability == legacyAction,
                        ability -> "legacy timing=" + (ability == alternativeAction
                                ? "WAIT_FOR_COMBAT" : "PLAY_NOW"));

        Assert.assertEquals(noAlternative.status(), "no_distinct_alternative_action");
        Assert.assertEquals(rejectedAlternative.status(),
                "alternatives_rejected_by_legacy_filter");
        Assert.assertTrue(rejectedAlternative.diagnosticSummary()
                .contains("Grizzly Bears"), rejectedAlternative.diagnosticSummary());
        Assert.assertTrue(rejectedAlternative.diagnosticSummary()
                .contains("WAIT_FOR_COMBAT"), rejectedAlternative.diagnosticSummary());
    }

    private static ManaActionCombinationSelector.Selection selection(
            final SpellAbility firstAction, final int score, final int fallbackActions,
            final int incompleteActions, final int uncertainActions) {
        return new ManaActionCombinationSelector.Selection(firstAction, List.of(firstAction),
                3, 1, 2, score, 0, 1, fallbackActions, fallbackActions,
                incompleteActions, uncertainActions, List.of());
    }

    @Test
    public void manaCombinationSelectorRejectsAnUnpayableColoredCombination() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCard("Plains", ai);
        addCard("Plains", ai);
        addCardToZone("Savannah Lions", ai, ZoneType.Hand);
        addCardToZone("Llanowar Elves", ai, ZoneType.Hand);

        final List<SpellAbility> abilities = ComputerUtilAbility.getSpellAbilities(
                new CardCollection(ai.getCardsIn(ZoneType.Hand)), ai);
        final ActionDecisionSnapshot snapshot = ActionDecisionSnapshot.capture(ai);
        final ManaActionCombinationSelector.Selection selection =
                ManaActionCombinationSelector.select(ai, abilities, true, snapshot, null);

        Assert.assertTrue(selection.hasAction(), selection.toString());
        Assert.assertEquals(selection.actions().size(), 1, selection.toString());
        Assert.assertTrue(selection.firstAction().getHostCard().getName().equals("Savannah Lions")
                || selection.firstAction().getHostCard().getName().equals("Llanowar Elves"),
                selection.toString());
    }

    @Test
    public void actionDecisionSnapshotSupportsStaticComboLandAsOneFlexibleMana() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCard("Blossoming Sands", ai);

        final ActionDecisionSnapshot snapshot = ActionDecisionSnapshot.capture(ai);

        Assert.assertTrue(snapshot.manaResourcesComplete(), snapshot.toString());
        Assert.assertEquals(snapshot.manaSources().size(), 1, snapshot.toString());
        final ActionManaSource source = snapshot.manaSources().get(0);
        Assert.assertEquals(source.outputMasks().size(), 1, source.toString());
        Assert.assertTrue((source.outputMasks().get(0) & ManaAtom.GREEN) != 0,
                source.toString());
        Assert.assertTrue((source.outputMasks().get(0) & ManaAtom.WHITE) != 0,
                source.toString());
    }

    @Test
    public void actionDecisionSnapshotKeepsDynamicComboLandFailClosed() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCard("Command Tower", ai);

        final ActionDecisionSnapshot snapshot = ActionDecisionSnapshot.capture(ai);

        Assert.assertFalse(snapshot.manaResourcesComplete(), snapshot.toString());
    }

    @Test
    public void manaCombinationSelectorUsesGrossValueForAHighCostPermanent() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        for (int i = 0; i < 6; i++) {
            addCard("Forest", ai);
        }
        addCardToZone("Savannah Lions", ai, ZoneType.Hand);
        addCardToZone("Colossal Dreadmaw", ai, ZoneType.Hand);

        final List<SpellAbility> abilities = ComputerUtilAbility.getSpellAbilities(
                new CardCollection(ai.getCardsIn(ZoneType.Hand)), ai);
        final ManaActionCombinationSelector.Selection selection =
                ManaActionCombinationSelector.select(ai, abilities, true);

        Assert.assertTrue(selection.hasAction(), selection.toString());
        Assert.assertEquals(selection.usedMana(), 6, selection.toString());
        Assert.assertEquals(selection.firstAction().getHostCard().getName(), "Colossal Dreadmaw");
    }

    @Test
    public void manaCombinationSelectorUsesFairRateFallbackForUnsupportedCast() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCard("Forest", ai);
        addCard("Forest", ai);
        addCard("Forest", ai);
        addCard("Forest", ai);
        addCard("Forest", ai);
        final Card knownOneDrop = addCardToZone("Savannah Lions", ai, ZoneType.Hand);
        final Card unknownFiveDrop = addCardToZone("Grizzly Bears", ai, ZoneType.Hand);
        final SpellAbility unsupported = new SpellApiBased(ApiType.RollPlanarDice, unknownFiveDrop,
                new Cost("5", false), null, Map.of());
        unsupported.setActivatingPlayer(ai);

        final List<SpellAbility> abilities = new ArrayList<>();
        abilities.add(knownOneDrop.getSpellAbilities().get(0));
        abilities.add(unsupported);
        final ManaActionCombinationSelector.Selection selection =
                ManaActionCombinationSelector.select(ai, abilities, true);

        Assert.assertTrue(selection.hasAction(), selection.toString());
        Assert.assertSame(selection.firstAction(), unsupported);
        Assert.assertEquals(selection.fallbackCandidateCount(), 1);
        Assert.assertEquals(selection.fallbackActionCount(), 1);
    }

    @Test
    public void manaCombinationSelectorSkipsCrewCostsBeforePaymentSearch() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card jet = addCard("Royal Talon Fighter Jet", ai);
        addCard("Grizzly Bears", ai);
        final SpellAbility crew = jet.getSpellAbilities().stream()
                .filter(SpellAbility::isCrew)
                .findFirst()
                .orElseThrow();

        Assert.assertNull(crew.getActivatingPlayer());
        final ManaActionCombinationSelector.Selection selection =
                ManaActionCombinationSelector.select(ai, List.of(crew), true);

        Assert.assertFalse(selection.hasAction(), selection.toString());
        Assert.assertEquals(selection.evaluatedCandidateCount(), 0);
        Assert.assertNull(crew.getActivatingPlayer(), "Valuation must not mutate the live ability");
    }

    @Test
    public void removalActionRejectsAContextForAnotherDecision() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card target = addCard("Grizzly Bears", game.getPlayers().get(0));

        final CardValueBreakdown result = UnifiedActionValueEvaluator.evaluate(
                new RemovalValuationAction(target, RemovalActionKind.EXILE),
                ValuationContext.forCast(ai, true));

        Assert.assertEquals(result.completeness(), ValuationCompleteness.UNSUPPORTED);
    }
}
