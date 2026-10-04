package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.effect.IntrinsicDrawOutcomeBackend.State;
import forge.ai.effect.IntrinsicReferenceModel.CreatureProfile;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;

/** Damage and lifelink form one simultaneous life transition, independent of casualties. */
public class IntrinsicLifelinkDamageTest {
    private static final PermanentProfile SOURCE = new PermanentProfile(true, PermanentKind.CREATURE,
            true, 1, 1, Set.of("Lifelink"));

    private static OutcomePlan<State> evaluate(final State state, final String api, final Map<String, String> parameters) {
        final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults(), state.sourcePermanent());
        final var node = new AbilityOutcomeDescription("damage", api, parameters, List.of(), null, "");
        return new OutcomePlanner<State>(100).evaluate(new OutcomeDescriptionCompiler<>(backend).compile(node), state);
    }

    private static State state(final int controllerLife, final int opponentLife) {
        return new State(3, 3).withLife(true, controllerLife).withLife(false, opponentLife).withSourcePermanent(SOURCE);
    }

    @Test
    public void selfDamageAndLifelinkCancelBeforeCheckingLethal() {
        for (final int damage : List.of(0, 1, 5)) {
            final var plan = evaluate(state(1, 15), "DealDamage", Map.of("Defined", "You", "NumDmg", "" + damage));
            Assert.assertTrue(plan.supported());
            Assert.assertEquals(plan.state().controllerLife(), 1);
            Assert.assertEquals(plan.value(), 0.0);
        }
        final var oppositeSource = new PermanentProfile(true, PermanentKind.CREATURE, false, 1, 1, Set.of("Lifelink"));
        final var opponent = evaluate(state(15, 1).withSourcePermanent(oppositeSource), "DealDamage",
                Map.of("Defined", "Opponent", "NumDmg", "5"));
        Assert.assertEquals(opponent.state().opponentLife(), 1);
        Assert.assertEquals(opponent.value(), 0.0);
        final var toxicSource = new PermanentProfile(true, PermanentKind.CREATURE, true, 1, 1, Set.of("Toxic"));
        final var noncombat = evaluate(state(15, 15).withSourcePermanent(toxicSource), "DealDamage",
                Map.of("Defined", "Opponent", "NumDmg", "3"));
        Assert.assertTrue(noncombat.supported());
        Assert.assertEquals(noncombat.state().opponentLife(), 12);
        Assert.assertEquals(noncombat.value(), (double) new IntrinsicOutcomeEvaluator(IntrinsicEvaluationSettings.defaults())
                .evaluatePlayerDamage(15, 3, false));
    }

    @Test
    public void creatureDamageGainsFullAmountWithoutRequiringCasualties() {
        for (final var victim : List.of(new CreatureProfile(true, 2, 8, Set.of(), false, false),
                new CreatureProfile(true, 2, 2, Set.of("Indestructible"), false, true))) {
            final var plan = evaluate(state(5, 15).withCreatures(false, victim).withCreatureCount(false, 1),
                    "DealDamage", Map.of("ValidTgts", "Creature.OppCtrl", "NumDmg", "5"));
            Assert.assertTrue(plan.supported());
            Assert.assertEquals(plan.state().controllerLife(), 10);
            Assert.assertEquals(plan.state().opponentCreature(), victim);
            Assert.assertEquals(plan.value(), (double) new IntrinsicOutcomeEvaluator(IntrinsicEvaluationSettings.defaults())
                    .evaluateLifeGain(5, 5, true));
        }
    }

    @Test
    public void groupDamageRetainsLifelinkWhenSourceDiesAndNetsPlayerChangesOnce() {
        final var victim = new CreatureProfile(true, 4, 4, Set.of("Indestructible"), false, true);
        final var initial = state(1, 1).withCreatures(false, victim).withCreatureCount(false, 2);
        final var plan = evaluate(initial, "DamageAll", Map.of("ValidPlayers", "Player", "ValidCards", "Creature", "NumDmg", "2"));
        Assert.assertTrue(plan.supported());
        // Five recipients: host, two enemy creatures and both players; damage is not capped by life.
        Assert.assertEquals(plan.state().controllerLife(), 9);
        Assert.assertEquals(plan.state().opponentLife(), 0);
        Assert.assertFalse(plan.state().sourcePermanent().present());
        final var evaluator = new IntrinsicOutcomeEvaluator(IntrinsicEvaluationSettings.defaults());
        final int expected = evaluator.evaluatePermanentDelta(SOURCE, PermanentProfile.absent(), true)
                + evaluator.evaluateLifeGain(1, 8, true) + evaluator.evaluateLifeLoss(1, 1, false);
        Assert.assertEquals(plan.value(), (double) expected);
    }

    @Test
    public void fightsCreditEachControllerEvenWhenBothFightersDie() {
        final var source = new PermanentProfile(true, PermanentKind.CREATURE, true, 3, 3, Set.of("Lifelink"));
        final var enemy = new CreatureProfile(true, 4, 2, Set.of("Lifelink"), false, false);
        final var initial = state(5, 10).withSourcePermanent(source).withCreatures(false, enemy).withCreatureCount(false, 1);
        final var plan = evaluate(initial, "Fight", Map.of("Defined", "Self", "ValidTgts", "Creature.OppCtrl"));
        Assert.assertTrue(plan.supported());
        Assert.assertFalse(plan.state().sourcePermanent().present());
        Assert.assertFalse(plan.state().opponentCreature().present());
        Assert.assertEquals(plan.state().controllerLife(), 8);
        Assert.assertEquals(plan.state().opponentLife(), 14);
        final var evaluator = new IntrinsicOutcomeEvaluator(IntrinsicEvaluationSettings.defaults());
        final var enemyPermanent = new PermanentProfile(true, PermanentKind.CREATURE, false, 4, 2, Set.of("Lifelink"));
        final int expected = evaluator.evaluatePermanentDelta(source, PermanentProfile.absent(), true)
                + evaluator.evaluatePermanentDelta(enemyPermanent, PermanentProfile.absent(), false)
                + evaluator.evaluateLifeGain(5, 3, true) + evaluator.evaluateLifeGain(10, 4, false);
        Assert.assertEquals(plan.value(), (double) expected);
    }

    @Test
    public void friendlyFightCombinesGainsBeforeNonlinearUtilityAndKeepsZeroPowerAtZero() {
        for (final int power : List.of(0, 2)) {
            final var ally = new CreatureProfile(true, power, 4, Set.of("Lifelink", "Indestructible"), false, true);
            final var source = new PermanentProfile(true, PermanentKind.CREATURE, true, 3, 3,
                    Set.of("Lifelink", "Indestructible"));
            final var plan = evaluate(state(2, 15).withSourcePermanent(source).withCreatures(true, ally).withCreatureCount(true, 1),
                    "Fight", Map.of("Defined", "Self", "ValidTgts", "Creature.YouCtrl"));
            Assert.assertTrue(plan.supported());
            Assert.assertEquals(plan.state().controllerLife(), 5 + power);
            Assert.assertEquals(plan.value(), (double) new IntrinsicOutcomeEvaluator(IntrinsicEvaluationSettings.defaults())
                    .evaluateLifeGain(2, 3 + power, true));
        }
        final var fight = new AbilityOutcomeDescription("fight", "Fight",
                Map.of("Defined", "Self", "ValidTgts", "Creature.OppCtrl"), List.of(), null, "");
        final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults(),
                new PermanentProfile(true, PermanentKind.CREATURE, true, 3, 3, Set.of()));
        Assert.assertTrue(backend.referenceDimensions(fight).contains(IntrinsicDrawOutcomeBackend.OPPONENT_LIFE));
        for (final String counterDamage : List.of("Infect", "Wither")) {
            final var source = new PermanentProfile(true, PermanentKind.CREATURE, true, 3, 3, Set.of(counterDamage));
            final var enemy = new CreatureProfile(true, 2, 2, Set.of("Indestructible"), false, true);
            final var unresolved = evaluate(state(5, 15).withSourcePermanent(source)
                    .withCreatures(false, enemy).withCreatureCount(false, 1), "Fight",
                    Map.of("Defined", "Self", "ValidTgts", "Creature.OppCtrl"));
            Assert.assertFalse(unresolved.supported(), counterDamage);
        }
    }
}
