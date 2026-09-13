package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.PlayerResourceValueEvaluator;
import forge.ai.effect.CardAbilityTraversal.AbilityDescription;
import forge.ai.effect.IntrinsicDrawOutcomeBackend.State;
import forge.ai.effect.IntrinsicDrawOutcomeBackend.TargetRef;
import forge.ai.effect.IntrinsicReferenceModel.CreatureProfile;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;
import forge.ai.effect.OutcomePlan.Completeness;

/** Semantic regressions for the deliberately bounded intrinsic reference adapter. */
public class IntrinsicOutcomeBackendTest {
    private static final PermanentProfile SOURCE = new PermanentProfile(true, PermanentKind.CREATURE,
            true, 2, 2, Set.of());
    private static final CreatureProfile CREATURE = new CreatureProfile(true, 2, 2, Set.of(), false, false);

    private static IntrinsicDrawOutcomeBackend backend() {
        return new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults(), SOURCE);
    }

    private static AbilityOutcomeDescription leaf(final String api, final Map<String, String> parameters) {
        return new AbilityOutcomeDescription(api, api, parameters, List.of(), null, "");
    }

    private static AbilityOutcomeDescription sequence(final AbilityOutcomeDescription first,
            final AbilityOutcomeDescription second) {
        return new AbilityOutcomeDescription(first.path(), first.api(), first.parameters(),
                first.choices(), second, first.issue());
    }

    private static AbilityOutcomeDescription counter(final String target) {
        return leaf("PutCounter", Map.of("CounterType", "P1P1", "ValidTgts", target));
    }

    private static State state(final CreatureProfile friendly, final CreatureProfile opponent,
            final PermanentProfile source) {
        return new State(0, 7, 20, 20, 3, 3, 1, 1, friendly, opponent,
                PermanentProfile.absent(), PermanentProfile.absent(), source, null);
    }

    private static OutcomePlan<State> evaluate(final AbilityOutcomeDescription description, final State state) {
        return new OutcomePlanner<State>().evaluate(
                new OutcomeDescriptionCompiler<>(backend()).compile(description), state);
    }

    private static OutcomePlan<State> evaluate(final IntrinsicDrawOutcomeBackend backend,
            final AbilityOutcomeDescription description, final State state) {
        return new OutcomePlanner<State>().evaluate(
                new OutcomeDescriptionCompiler<>(backend).compile(description), state);
    }

    @Test
    public void defaultDrawAndDrawSequenceProjectOnlyTheCorrectHands() {
        final AbilityOutcomeDescription draw = leaf("Draw", Map.of("DB", "Draw", "Defined", "You"));
        final State initial = state(CREATURE, CREATURE, SOURCE).withTarget(TargetRef.OPPONENT_CREATURE);
        final OutcomePlan<State> result = evaluate(sequence(draw, draw), initial);
        Assert.assertEquals(result.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(result.value(), (double) PlayerResourceValueEvaluator.evaluateCardDraw(0, 2));
        Assert.assertEquals(result.state().controllerHand(), 2);
        Assert.assertEquals(result.state().opponentHand(), 7);
        Assert.assertNull(result.state().target());
        Assert.assertEquals(initial.controllerHand(), 0);
        final OutcomePlan<State> opposing = evaluate(leaf("Draw", Map.of("Defined", "Opponent")), initial);
        Assert.assertEquals(opposing.value(), -(double) PlayerResourceValueEvaluator.evaluateCardDraw(7, 1));
        Assert.assertEquals(opposing.state().opponentHand(), 8);
    }

    @Test
    public void resourceOutcomesUseProjectedReferenceStateAndSharedValues() {
        final State initial = new State(3, 7, 10, 15, 2, 4, 1, 1,
                CREATURE, CREATURE, PermanentProfile.absent(), PermanentProfile.absent(), SOURCE, null);

        final OutcomePlan<State> gain = evaluate(leaf("GainLife", Map.of("Defined", "You",
                "LifeAmount", "3")), initial);
        Assert.assertEquals(gain.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(gain.state().controllerLife(), 13);
        Assert.assertTrue(gain.value() > 0);

        final OutcomePlan<State> loss = evaluate(leaf("LoseLife", Map.of("Defined", "Opponent",
                "LifeAmount", "5")), initial);
        Assert.assertEquals(loss.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(loss.state().opponentLife(), 10);
        Assert.assertTrue(loss.value() > 0);

        final OutcomePlan<State> discard = evaluate(leaf("Discard", Map.of("Defined", "Opponent",
                "Mode", "TgtChoose", "NumCards", "2")), initial);
        Assert.assertEquals(discard.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(discard.state().opponentHand(), 5);
        Assert.assertTrue(discard.value() > 0);

        final OutcomePlan<State> mana = evaluate(leaf("Mana", Map.of("Defined", "You",
                "Produced", "G", "Amount", "2")), initial);
        Assert.assertEquals(mana.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(mana.state().controllerMana(), 4);
        Assert.assertEquals(mana.value(), 70.0);
    }

    @Test
    public void fixedCreatureTokensAndPlayerDamageAreEvaluatedWithOwnerPerspective() {
        final IntrinsicDrawOutcomeBackend tokenBackend = new IntrinsicDrawOutcomeBackend(
                IntrinsicEvaluationSettings.defaults(), SOURCE, script ->
                        "w_1_1_soldier".equals(script)
                                ? Optional.of(new PermanentProfile(true, PermanentKind.TOKEN, true,
                                        1, 1, Set.of())) : Optional.empty());
        final State initial = state(CREATURE, CREATURE, SOURCE);
        final OutcomePlan<State> tokens = evaluate(tokenBackend, leaf("Token", Map.of(
                "TokenScript", "w_1_1_soldier", "TokenAmount", "2", "TokenOwner", "You")), initial);
        Assert.assertEquals(tokens.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(tokens.state().controllerCreatureCount(), 3);
        Assert.assertTrue(tokens.value() > 0);

        final OutcomePlan<State> opposingTokens = evaluate(tokenBackend, leaf("Token", Map.of(
                "TokenScript", "w_1_1_soldier", "TokenOwner", "Opponent")), initial);
        Assert.assertEquals(opposingTokens.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(opposingTokens.state().opponentCreatureCount(), 2);
        Assert.assertTrue(opposingTokens.value() < 0);

        final OutcomePlan<State> damage = evaluate(leaf("DealDamage", Map.of(
                "Defined", "Opponent", "NumDmg", "5")), initial);
        Assert.assertEquals(damage.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(damage.state().opponentLife(), 15);
        Assert.assertTrue(damage.value() > 0);
    }

    @Test
    public void damageCanChooseAValidCreatureAndLethalDamageRemovesIt() {
        final State initial = state(CREATURE,
                new CreatureProfile(true, 3, 3, Set.of(), false, false), SOURCE);
        final OutcomePlan<State> damage = evaluate(leaf("DealDamage", Map.of(
                "ValidTgts", "Creature.OppCtrl", "NumDmg", "3")), initial);
        Assert.assertEquals(damage.completeness(), Completeness.COMPLETE);
        Assert.assertFalse(damage.state().opponentCreature().present());
        Assert.assertTrue(damage.value() > 0);

        final OutcomePlan<State> anyTarget = evaluate(leaf("DealDamage", Map.of(
                "ValidTgts", "Any", "NumDmg", "3")), initial);
        Assert.assertEquals(anyTarget.completeness(), Completeness.COMPLETE);
        Assert.assertFalse(anyTarget.state().opponentCreature().present());
    }

    @Test
    public void unsupportedDynamicAndNoncreatureTokenFormsRemainUnresolved() {
        final IntrinsicDrawOutcomeBackend tokenBackend = new IntrinsicDrawOutcomeBackend(
                IntrinsicEvaluationSettings.defaults(), SOURCE, script ->
                        Optional.of(new PermanentProfile(true, PermanentKind.ARTIFACT, true,
                                0, 0, Set.of())));
        final State initial = state(CREATURE, CREATURE, SOURCE);
        for (final AbilityOutcomeDescription outcome : List.of(
                leaf("Token", Map.of("TokenScript", "soldier", "TokenAmount", "X")),
                leaf("Token", Map.of("TokenScript", "artifact", "TokenOwner", "You")),
                leaf("Mana", Map.of("Defined", "You", "Produced", "G", "Amount", "X")),
                leaf("DealDamage", Map.of("Defined", "Opponent", "NumDmg", "X")),
                leaf("DealDamage", Map.of("NumDmg", "1")))) {
            Assert.assertEquals(new OutcomePlanner<State>().evaluate(
                    new OutcomeDescriptionCompiler<>(tokenBackend).compile(outcome), initial)
                    .completeness(), Completeness.UNSUPPORTED, outcome.toString());
        }
    }

    @Test
    public void selfCounterSequencesUpdateTheSourceAndPreserveOtherRecipients() {
        final AbilityOutcomeDescription self = leaf("PutCounter", Map.of("Defined", "Self", "CounterType", "P1P1"));
        final State initial = state(CREATURE, CREATURE, SOURCE);
        final OutcomePlan<State> result = evaluate(sequence(self, self), initial);
        Assert.assertEquals(result.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(result.value(), 50.0);
        Assert.assertEquals(result.state().sourcePermanent().power(), 4);
        Assert.assertEquals(result.state().sourcePermanent().toughness(), 4);
        Assert.assertEquals(result.state().controllerCreature(), CREATURE);
        Assert.assertEquals(initial.sourcePermanent(), SOURCE);
    }

    @Test
    public void realTargetScopesRespectSideOtherAndSourceAvailability() {
        final State initial = state(CREATURE, CREATURE, SOURCE);
        final OutcomePlan<State> friendly = evaluate(counter("Creature.YouCtrl+Other"), initial);
        Assert.assertEquals(friendly.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(friendly.value(), 25.0);
        Assert.assertEquals(friendly.state().controllerCreature().power(), 3);
        Assert.assertEquals(friendly.state().sourcePermanent(), SOURCE);
        Assert.assertNull(friendly.state().target());
        Assert.assertTrue(friendly.decisions().stream().anyMatch(d -> d.kind() == OutcomePlan.DecisionKind.TARGET));

        final OutcomePlan<State> opposing = evaluate(counter("Creature.OppCtrl"), initial);
        Assert.assertEquals(opposing.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(opposing.value(), -25.0);
        Assert.assertEquals(opposing.state().opponentCreature().power(), 3);
        Assert.assertEquals(opposing.state().controllerCreature(), CREATURE);

        final State alone = state(CreatureProfile.absent(), CreatureProfile.absent(), SOURCE);
        Assert.assertEquals(evaluate(counter("Creature.YouCtrl+Other"), alone).completeness(), Completeness.UNAVAILABLE);
        final OutcomePlan<State> selfTarget = evaluate(counter("Creature.YouCtrl"), alone);
        Assert.assertEquals(selfTarget.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(selfTarget.state().sourcePermanent().power(), 3);
        Assert.assertEquals(evaluate(counter("Creature"), alone).value(), 25.0);
    }

    @Test
    public void targetProtectionAndProfileFlagsSurviveProjection() {
        final CreatureProfile protectedCreature = new CreatureProfile(true, 2, 2, Set.of(), true, true);
        final State initial = state(protectedCreature, protectedCreature, PermanentProfile.absent());
        final OutcomePlan<State> friendly = evaluate(counter("Creature.YouCtrl"), initial);
        Assert.assertEquals(friendly.value(), 25.0);
        Assert.assertTrue(friendly.state().controllerCreature().hexproof());
        Assert.assertTrue(friendly.state().controllerCreature().indestructible());
        Assert.assertEquals(evaluate(counter("Creature.OppCtrl"), initial).completeness(), Completeness.UNAVAILABLE);
        final CreatureProfile shroud = new CreatureProfile(true, 2, 2, Set.of("Shroud"), false, false);
        Assert.assertEquals(evaluate(counter("Creature.YouCtrl"), state(shroud, CREATURE,
                PermanentProfile.absent())).completeness(), Completeness.UNAVAILABLE);
        final CreatureProfile ward = new CreatureProfile(true, 2, 2, Set.of("Ward:2"), false, false);
        Assert.assertEquals(evaluate(counter("Creature"), state(CREATURE, ward,
                PermanentProfile.absent())).completeness(), Completeness.UNSUPPORTED);
    }

    @Test
    public void groupAliasesSharedBindingsAndUnknownParametersFailClosed() {
        final State initial = state(CREATURE, CREATURE, SOURCE);
        for (final String defined : List.of("YouCtrl", "OpponentCtrl", "Targeted", "Remembered")) {
            Assert.assertEquals(evaluate(leaf("PutCounter", Map.of("Defined", defined,
                    "CounterType", "P1P1")), initial).completeness(), Completeness.UNSUPPORTED, defined);
        }
        for (final Map<String, String> parameters : List.of(
                Map.of("NumCards", "X"), Map.of("NumCards", ""), Map.of("Cost", "3"),
                Map.of("AB", "Draw"), Map.of("SP", "Draw"), Map.of("Optional", "True"),
                Map.of("Condition", "Metalcraft"), Map.of("ValidTgts", "Player"))) {
            Assert.assertEquals(evaluate(leaf("Draw", parameters), initial).completeness(),
                    Completeness.UNSUPPORTED, parameters.toString());
        }
        Assert.assertEquals(evaluate(counter("Creature.YouCtrl+Soldier"), initial).completeness(), Completeness.UNSUPPORTED);
        Assert.assertEquals(evaluate(leaf("PutCounter", Map.of("CounterType", "P1P1",
                "ValidTgts", "Creature", "TargetMax", "2")), initial).completeness(), Completeness.UNSUPPORTED);
        Assert.assertEquals(evaluate(sequence(counter("Creature.YouCtrl"), counter("Creature.OppCtrl")),
                initial).completeness(), Completeness.UNSUPPORTED);
    }

    @Test
    public void nestedDimensionsIncludeEverySupportedBranchAndContinuation() {
        final AbilityOutcomeDescription choice = new AbilityOutcomeDescription("choice", "Charm", Map.of(),
                List.of(counter("Creature.YouCtrl+Other"), leaf("Draw", Map.of("Defined", "Opponent"))),
                leaf("Draw", Map.of("Defined", "You")), "");
        Assert.assertEquals(backend().referenceDimensions(choice), Set.of(
                IntrinsicDrawOutcomeBackend.CONTROLLER_CREATURE,
                IntrinsicDrawOutcomeBackend.OPPONENT_HAND, IntrinsicDrawOutcomeBackend.CONTROLLER_HAND));
        Assert.assertEquals(backend().referenceDimensions(leaf("Destroy", Map.of("ValidTgts", "Creature"))), Set.of());
    }

    @Test
    public void referenceDepthLimitFailsClosedInsteadOfUsingFallbackDimensions() {
        AbilityOutcomeDescription tree = leaf("Draw", Map.of("Defined", "Opponent"));
        for (int i = 0; i < 30; i++) {
            tree = sequence(leaf("Draw", Map.of("Defined", "You")), tree);
        }
        final IntrinsicAbilityEvaluator.AbilityValue result = evaluateAbility(tree,
                Map.of("Mode", "Phase", "Phase", "Upkeep", "ValidPlayer", "You"));
        Assert.assertFalse(result.contribution().complete());
        Assert.assertEquals(result.contribution().unsupportedCaseProbability(), 1.0);
        Assert.assertEquals(result.contribution().value(), 0.0);
    }

    @Test
    public void tokenTriggerUsesOnlyAuditedIdentityFiltersAndAbsentTargetsRemainUnderstood() {
        final Map<String, String> trigger = Map.of("Mode", "TokenCreated", "ValidPlayer", "You",
                "ValidToken", "Card.token+YouCtrl");
        final IntrinsicAbilityEvaluator.AbilityValue result = evaluateAbility(counter("Creature.YouCtrl+Other"), trigger);
        Assert.assertTrue(result.contribution().complete(), result.toString());
        Assert.assertTrue(result.contribution().value() > 0);
        Assert.assertEquals(result.contribution().unavailableCaseProbability(), .2, .0000001);
        final IntrinsicAbilityEvaluator.AbilityValue restricted = evaluateAbility(counter("Creature.YouCtrl+Other"),
                Map.of("Mode", "TokenCreated", "ValidPlayer", "You", "ValidToken", "Creature.Soldier"));
        Assert.assertFalse(restricted.contribution().complete());
    }

    @Test
    public void choiceAndRandomUnknownLeavesRetainDifferentCompletenessInformation() {
        final List<AbilityOutcomeDescription> options = List.of(leaf("Draw", Map.of()),
                leaf("Destroy", Map.of()));
        final State initial = state(CREATURE, CREATURE, SOURCE);
        final OutcomePlan<State> choice = evaluate(new AbilityOutcomeDescription("choice", "Charm",
                Map.of(), options, null, ""), initial);
        Assert.assertEquals(choice.completeness(), Completeness.PARTIAL);
        Assert.assertEquals(choice.unresolvedProbability(), 0.0);
        Assert.assertFalse(choice.unresolvedAlternatives().isEmpty());
        final OutcomePlan<State> random = evaluate(new AbilityOutcomeDescription("random", "Charm",
                Map.of("Random", "True"), options, null, ""), initial);
        Assert.assertEquals(random.completeness(), Completeness.PARTIAL);
        Assert.assertEquals(random.unresolvedProbability(), .5);
        Assert.assertEquals(random.value(), PlayerResourceValueEvaluator.evaluateCardDraw(0, 1) * .5);
    }

    @Test
    public void zeroLifeCannotEarnAnotherLethalBonus() {
        Assert.assertEquals(new IntrinsicOutcomeEvaluator().evaluateLifeLoss(0, 5, false), 0);
        Assert.assertEquals(new IntrinsicOutcomeEvaluator().evaluateLifeLoss(-1, 5, true), 0);
    }

    @Test
    public void selfAttackUsesExistingRateWhileOtherCombatFiltersRemainUnsupported() {
        final AbilityOutcomeDescription self = leaf("PutCounter", Map.of("Defined", "Self", "CounterType", "P1P1"));
        final IntrinsicReferenceModel model = IntrinsicReferenceModel.defaults();
        final double expected = IntrinsicEventTriggerEstimator.estimate(
                IntrinsicEventTriggerAdapter.describe(Map.of("Mode", "Attacks", "ValidCard", "Card.Self")).orElseThrow(),
                SOURCE, model, IntrinsicEvaluationSettings.defaults(), EntryTiming.NORMAL_SPEED).expectedOccurrences();
        for (final String filter : List.of("Card.Self", "Creature.Self")) {
            final IntrinsicAbilityEvaluator.AbilityValue result = evaluateAbility(self,
                    Map.of("Mode", "Attacks", "ValidCard", filter));
            Assert.assertTrue(result.contribution().complete(), result.toString());
            Assert.assertEquals(result.expectedOccurrences(), expected, .0000001);
            Assert.assertEquals(result.contribution().value(), expected * 25, .0000001);
        }
        for (final Map<String, String> trigger : List.of(
                Map.of("Mode", "Attacks", "ValidCard", "Creature.YouCtrl"),
                Map.of("Mode", "Attacks", "ValidCard", "Card.Self", "ValidPlayer", "You"),
                Map.of("Mode", "Attacks", "ValidCard", "Card.Self", "Alone", "True"),
                Map.of("Mode", "Blocks", "ValidCard", "Card.Self"))) {
            Assert.assertFalse(evaluateAbility(self, trigger).contribution().complete(), trigger.toString());
        }
    }

    @Test
    public void nestedDrawsUseWeightedHandCasesInsteadOfFallbackHands() {
        final AbilityOutcomeDescription draw = leaf("Draw", Map.of("Defined", "You", "NumCards", "2"));
        final AbilityOutcomeDescription opponent = leaf("Draw", Map.of("Defined", "Opponent"));
        final AbilityOutcomeDescription options = new AbilityOutcomeDescription("random", "Charm",
                Map.of("Random", "True"), List.of(draw, opponent), null, "");
        final IntrinsicAbilityEvaluator.AbilityValue result = evaluateAbility(options,
                Map.of("Mode", "Phase", "Phase", "Upkeep", "ValidPlayer", "You"));
        final double perResolution = IntrinsicReferenceModel.defaults().handSizes().entries().stream()
                .mapToDouble(e -> e.weight() * .5 * (PlayerResourceValueEvaluator.evaluateCardDraw(e.value(), 2)
                        - PlayerResourceValueEvaluator.evaluateCardDraw(e.value(), 1))).sum();
        Assert.assertTrue(result.contribution().complete());
        Assert.assertEquals(result.contribution().value(), perResolution * result.expectedOccurrences(), .0000001);
    }

    @Test
    public void oversizedCartesianReferenceModelIsRejectedBeforeExpansion() {
        final IntrinsicReferenceModel defaults = IntrinsicReferenceModel.defaults();
        final List<WeightedValue<Integer>> hands = new java.util.ArrayList<>();
        for (int i = 0; i < 70; i++) {
            hands.add(new WeightedValue<>(i, 1.0 / 70));
        }
        final IntrinsicReferenceModel model = new IntrinsicReferenceModel(defaults.lifeTotals(),
                new WeightedDistribution<>(hands), defaults.availableMana(), defaults.friendlyCreatureCounts(),
                defaults.opposingCreatureCounts(), defaults.creatureProfiles(), defaults.permanentProfiles(),
                Map.of(), Map.of());
        final AbilityOutcomeDescription outcome = sequence(leaf("Draw", Map.of("Defined", "You")),
                leaf("Draw", Map.of("Defined", "Opponent")));
        final AbilityDescription ability = new AbilityDescription("large", CardAbilityTraversal.Origin.TRIGGER,
                CardAbilityTraversal.Provenance.PRINTED,
                Map.of("Mode", "Phase", "Phase", "Upkeep", "ValidPlayer", "You"), outcome);
        final IntrinsicAbilityEvaluator.AbilityValue result = new IntrinsicAbilityEvaluator(model,
                IntrinsicEvaluationSettings.defaults()).evaluate(List.of(ability), SOURCE, EntryTiming.NORMAL_SPEED).get(0);
        Assert.assertEquals(result.contribution().unsupportedCaseProbability(), 1.0);
        Assert.assertEquals(result.contribution().value(), 0.0);
    }

    private static IntrinsicAbilityEvaluator.AbilityValue evaluateAbility(final AbilityOutcomeDescription outcome,
            final Map<String, String> parameters) {
        final AbilityDescription ability = new AbilityDescription("test", CardAbilityTraversal.Origin.TRIGGER,
                CardAbilityTraversal.Provenance.PRINTED, parameters, outcome);
        return new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults())
                .evaluate(List.of(ability), SOURCE, EntryTiming.NORMAL_SPEED).get(0);
    }
}
