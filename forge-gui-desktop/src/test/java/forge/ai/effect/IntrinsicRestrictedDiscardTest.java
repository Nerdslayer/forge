package forge.ai.effect;

import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.PlayerResourceValueEvaluator;
import forge.ai.effect.IntrinsicDrawOutcomeBackend.State;
import forge.card.CardStateName;
import forge.model.FModel;

/** Eligibility, chooser polarity and follow-up projection without looking at hidden cards. */
public class IntrinsicRestrictedDiscardTest extends AITest {
    private static AbilityOutcomeDescription discard(final String mode, final String recipient, final String validity) {
        return new AbilityOutcomeDescription("discard", "Discard", Map.of("Mode", mode, "Defined", recipient,
                "DiscardValid", validity, "NumCards", "1"), List.of(), null, "");
    }

    private static OutcomePlan<State> evaluate(final AbilityOutcomeDescription node, final State state) {
        final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults());
        return new OutcomePlanner<State>().evaluate(new OutcomeDescriptionCompiler<>(backend).compile(node), state);
    }

    @Test
    public void revealedSelectionPreservesZeroHitsAndUsesSharedDiscardScale() {
        final var node = discard("RevealYouChoose", "Opponent", "Card.nonLand");
        for (final int hand : List.of(0, 1, 3, 7)) {
            final var plan = evaluate(node, new State(3, hand));
            Assert.assertTrue(plan.complete());
            Assert.assertEquals(plan.value(), (1 - Math.pow(.4, hand))
                    * PlayerResourceValueEvaluator.evaluateRandomDiscard(hand, 1), 1e-9);
        }
        final var restricted = discard("RevealYouChoose", "Opponent", "Artifact,Creature");
        Assert.assertEquals(evaluate(restricted, new State(3, 3)).value(), (1 - Math.pow(.64, 3))
                * PlayerResourceValueEvaluator.evaluateRandomDiscard(3, 1), 1e-9);
        final var parameters = new java.util.HashMap<>(restricted.parameters());
        parameters.remove("NumCards");
        parameters.remove("Defined");
        parameters.put("ValidTgts", "Player");
        final var targeted = new AbilityOutcomeDescription("targeted", "Discard", parameters, List.of(), null, "");
        Assert.assertEquals(evaluate(targeted, new State(3, 3)).value(), evaluate(restricted, new State(3, 3)).value(), 1e-9);
    }

    @Test
    public void restrictedAffectedPlayerChoiceAndFollowupsUseEachProjectedHand() {
        final var draw = new AbilityOutcomeDescription("draw", "Draw", Map.of("NumCards", "1"), List.of(), null, "");
        for (final String mode : List.of("TgtChoose", "RevealYouChoose")) {
            final var node = discard(mode, "You", "Card.nonCreature+nonLand");
            final var chain = new AbilityOutcomeDescription("chain", "Discard", node.parameters(), List.of(), draw, "");
            double expected = 0;
            for (final var count : IntrinsicLibraryReference.selectedCounts(3, 3, .3).entries()) {
                final int removed = Math.min(1, count.value());
                expected += count.weight() * (-PlayerResourceValueEvaluator.evaluateChosenDiscard(3, 1, count.value())
                        + PlayerResourceValueEvaluator.evaluateCardDraw(3 - removed, 1));
            }
            final var plan = evaluate(chain, new State(3, 3));
            Assert.assertTrue(plan.complete());
            Assert.assertEquals(plan.value(), expected, 1e-9);
        }
        final var noHits = discard("Random", "Opponent", "Creature.nonCreature");
        Assert.assertTrue(evaluate(noHits, new State(3, 7)).complete());
        Assert.assertEquals(evaluate(noHits, new State(3, 7)).value(), 0.0);
    }

    @Test
    public void unsupportedInventoryAndCardPredicatesRemainUnresolved() {
        for (final String filter : List.of("Card.nonLand+cmcLEX", "Creature.Artifact", "Card.namedForest")) {
            Assert.assertFalse(evaluate(discard("RevealYouChoose", "Opponent", filter), new State(3, 3)).complete());
        }
        final var one = discard("RevealYouChoose", "Opponent", "Creature");
        final var repeated = new AbilityOutcomeDescription("repeated", "Discard", one.parameters(), List.of(), one, "");
        Assert.assertFalse(evaluate(repeated, new State(3, 3)).complete());
        final var alternate = new AbilityOutcomeDescription("alternate", "Charm", Map.of(), List.of(one,
                discard("RevealYouChoose", "Opponent", "Artifact")), null, "");
        Assert.assertTrue(evaluate(alternate, new State(3, 3)).complete());
        Assert.assertFalse(evaluate(one, new State(3, 40)).complete());
    }

    @Test
    public void realRestrictedDiscardDefinitionsReachOutcomesWithoutInspectingAHand() {
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        for (final String name : List.of("Thoughtseize", "Duress", "Divest", "Inquisition of Kozilek")) {
            final var value = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard(name), CardStateName.Original).get(0);
            Assert.assertEquals(value.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED, name);
            Assert.assertTrue(value.contribution().complete(), name);
        }
    }

    @Test
    public void literalManaValueRestrictionsRetainAvailabilityAndNonlinearHandValues() {
        // The nonland printed-value prior has 77% of its mass at zero through three.
        final double eligible = .6 * .77;
        final var node = discard("RevealYouChoose", "Opponent", "Card.nonLand+cmcLE3");
        for (final int hand : List.of(0, 1, 3, 7)) {
            Assert.assertEquals(evaluate(node, new State(3, hand)).value(), (1 - Math.pow(1 - eligible, hand))
                    * PlayerResourceValueEvaluator.evaluateRandomDiscard(hand, 1), 1e-9);
        }
        Assert.assertEquals(evaluate(discard("Random", "Opponent", "Card.cmcLT0"), new State(3, 3)).value(), 0.0);
    }
}
