package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.effect.IntrinsicDrawOutcomeBackend.State;
import forge.ai.effect.IntrinsicReferenceModel.CreatureProfile;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;
import forge.card.CardStateName;
import forge.model.FModel;

/** Affected-player choice, capped populations, protections and subsequent projected effects. */
public class IntrinsicPlayerSacrificeTest extends AITest {
    private static final IntrinsicEvaluationSettings SETTINGS = IntrinsicEvaluationSettings.defaults();
    private static final CreatureProfile CREATURE = new CreatureProfile(true, 2, 2,
            Set.of("indestructible", "hexproof", "shroud"), false, true);

    private static AbilityOutcomeDescription sacrifice(final String player, final int amount) {
        return new AbilityOutcomeDescription("sacrifice", "Sacrifice", Map.of("Defined", player,
                "SacValid", "Creature", "Amount", Integer.toString(amount)), List.of(), null, "");
    }

    private static State state(final int friendly, final int hostile, final PermanentProfile source) {
        return new State(3, 3).withCreatures(true, friendly == 0 ? CreatureProfile.absent() : CREATURE)
                .withCreatures(false, hostile == 0 ? CreatureProfile.absent() : CREATURE)
                .withCreatureCount(true, friendly).withCreatureCount(false, hostile).withSourcePermanent(source);
    }

    private static OutcomePlan<State> evaluate(final AbilityOutcomeDescription node, final State state) {
        return new OutcomePlanner<State>().evaluate(new OutcomeDescriptionCompiler<>(
                new IntrinsicDrawOutcomeBackend(SETTINGS)).compile(node), state);
    }

    @Test
    public void fixedAmountsCapToInventoryAndIgnoreProtectionKeywords() {
        final int creatureValue = new IntrinsicOutcomeEvaluator(SETTINGS).evaluateCreatureDelta(CREATURE,
                CreatureProfile.absent(), false);
        for (final int count : List.of(0, 1, 3, 5)) {
            for (final int amount : List.of(0, 1, 3, 8)) {
                final var result = evaluate(sacrifice("Opponent", amount), state(0, count, PermanentProfile.absent()));
                Assert.assertTrue(result.complete());
                Assert.assertEquals(result.value(), (double) creatureValue * Math.min(count, amount), 1e-9);
                Assert.assertEquals(result.state().opponentCreatureCount(), Math.max(0, count - amount));
                Assert.assertEquals(result.state().opponentCreature().present(), count > amount);
            }
        }
    }

    @Test
    public void eachAffectedPlayerChoosesItsLeastValuableCreatureIncludingSource() {
        final var utility = new IntrinsicOutcomeEvaluator(SETTINGS);
        for (final boolean friendly : List.of(true, false)) {
            final var source = new PermanentProfile(true, PermanentKind.CREATURE, friendly, 1, 1, Set.of());
            final var initial = state(friendly ? 2 : 0, friendly ? 0 : 2, source);
            final var one = evaluate(sacrifice(friendly ? "You" : "Opponent", 1), initial);
            Assert.assertTrue(one.complete());
            Assert.assertFalse(one.state().sourcePermanent().present());
            Assert.assertEquals(one.state().creatureCount(friendly), 2);
            Assert.assertEquals(one.value(), (double) utility.evaluatePermanentDelta(source, PermanentProfile.absent(), friendly), 1e-9);
            final var three = evaluate(sacrifice(friendly ? "You" : "Opponent", 3), initial);
            Assert.assertTrue(three.complete());
            Assert.assertEquals(three.value(), one.value() + 2.0 * utility.evaluateCreatureDelta(CREATURE, CreatureProfile.absent(), friendly), 1e-9);
            Assert.assertEquals(three.state().creatureCount(friendly), 0);
            Assert.assertFalse(three.state().sourcePermanent().present());
        }
    }

    @Test
    public void allPlayersAndTargetedPlayersRemainDifferentAndEmptyBoardsAllowFollowups() {
        final var initial = state(1, 3, PermanentProfile.absent());
        final var all = evaluate(sacrifice("Player", 2), initial);
        Assert.assertTrue(all.complete());
        Assert.assertEquals(all.state().controllerCreatureCount(), 0);
        Assert.assertEquals(all.state().opponentCreatureCount(), 1);
        final var targeted = new AbilityOutcomeDescription("targeted", "Sacrifice", Map.of("ValidTgts", "Player",
                "SacValid", "Creature", "Amount", "2"), List.of(), null, "");
        final var chosen = evaluate(targeted, initial);
        Assert.assertTrue(chosen.complete());
        Assert.assertEquals(chosen.state().controllerCreatureCount(), 1);
        Assert.assertEquals(chosen.state().opponentCreatureCount(), 1);
        final var draw = new AbilityOutcomeDescription("draw", "Draw", Map.of("NumCards", "1"), List.of(), null, "");
        final var sequence = new AbilityOutcomeDescription("sequence", "Sacrifice", targeted.parameters(), List.of(), draw, "");
        final var empty = evaluate(sequence, state(0, 0, PermanentProfile.absent()));
        Assert.assertTrue(empty.complete());
        Assert.assertEquals(empty.state().controllerHand(), 4);
        Assert.assertTrue(empty.value() > 0);
        final var dimensions = new IntrinsicDrawOutcomeBackend(SETTINGS).referenceDimensions(targeted);
        Assert.assertTrue(dimensions.containsAll(Set.of(IntrinsicDrawOutcomeBackend.CONTROLLER_CREATURE_COUNT,
                IntrinsicDrawOutcomeBackend.OPPONENT_CREATURE_COUNT)));
    }

    @Test
    public void realPlayerSacrificeDefinitionsReuseLoyaltyAndTokenAdapters() {
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), SETTINGS);
        final var taste = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard("Taste of Death"), CardStateName.Original).get(0);
        Assert.assertEquals(taste.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        final var liliana = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard("Liliana of the Veil"), CardStateName.Original);
        Assert.assertTrue(Set.of(IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED, IntrinsicAbilityEvaluator.SupportStatus.PARTIAL)
                .contains(liliana.get(1).outcomeStatus()), "The unsupported ultimate may still make the shared loyalty policy partial");
        for (final var extra : List.of(Map.of("RememberSacrificed", "True"), Map.of("StrictAmount", "True"),
                Map.of("Amount", "9"), Map.of("SacValid", "Permanent"), Map.of("SacValid", "Creature.nonToken"))) {
            final var parameters = new java.util.HashMap<>(sacrifice("Opponent", 1).parameters());
            parameters.putAll(extra);
            Assert.assertFalse(evaluate(new AbilityOutcomeDescription("unsupported", "Sacrifice", parameters, List.of(), null, ""),
                    state(1, 3, PermanentProfile.absent())).complete());
        }
    }

    @Test
    public void watchedEntrantsParticipateInSacrificeChoiceWithoutDepletingOtherCreatures() {
        for (final boolean friendly : List.of(true, false)) {
            final var watched = new PermanentProfile(true, PermanentKind.CREATURE, friendly, 1, 1, Set.of("Lifelink"));
            final var initial = state(friendly ? 1 : 0, friendly ? 0 : 1, PermanentProfile.absent())
                    .withWatchedCreature(watched);
            final var one = evaluate(sacrifice(friendly ? "You" : "Opponent", 1), initial);
            Assert.assertTrue(one.complete());
            Assert.assertFalse(one.state().watchedCreature().present());
            Assert.assertEquals(one.state().creatureCount(friendly), 1);
            final var all = evaluate(sacrifice(friendly ? "You" : "Opponent", 3), initial);
            Assert.assertTrue(all.complete());
            Assert.assertFalse(all.state().watchedCreature().present());
            Assert.assertEquals(all.state().creatureCount(friendly), 0);
            final var only = evaluate(sacrifice(friendly ? "You" : "Opponent", 1),
                    state(0, 0, PermanentProfile.absent()).withWatchedCreature(watched));
            Assert.assertTrue(only.complete());
            Assert.assertFalse(only.state().watchedCreature().present());
            Assert.assertNotEquals(only.value(), 0.0);
        }
    }
}
