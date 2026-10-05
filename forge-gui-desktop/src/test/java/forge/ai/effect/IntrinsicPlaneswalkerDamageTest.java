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

/** Loyalty damage, typed recipient unions, keyword semantics and existing creature-damage reuse. */
public class IntrinsicPlaneswalkerDamageTest extends AITest {
    private static final IntrinsicEvaluationSettings SETTINGS = IntrinsicEvaluationSettings.defaults();

    private static AbilityOutcomeDescription damage(final String validity, final int amount) {
        return new AbilityOutcomeDescription("damage", "DealDamage", Map.of("ValidTgts", validity,
                "NumDmg", Integer.toString(amount)), List.of(), null, "");
    }

    private static PermanentProfile planeswalker(final boolean friendly, final int loyalty, final Set<String> keywords) {
        return new PermanentProfile(true, PermanentKind.PLANESWALKER, friendly, 0, 0, keywords, false, loyalty);
    }

    private static OutcomePlan<State> evaluate(final AbilityOutcomeDescription node, final State state) {
        return new OutcomePlanner<State>().evaluate(new OutcomeDescriptionCompiler<>(new IntrinsicDrawOutcomeBackend(
                SETTINGS, state.sourcePermanent(), null)).compile(node), state);
    }

    @Test
    public void damageLowersLoyaltyAndZeroLoyaltyIgnoresIndestructible() {
        final var utility = new IntrinsicOutcomeEvaluator(SETTINGS);
        final var target = planeswalker(false, 4, Set.of("indestructible"));
        final var source = new PermanentProfile(true, PermanentKind.CREATURE, true, 1, 1, Set.of("deathtouch"));
        final var initial = new State(3, 3).withPermanent(false, target).withSourcePermanent(source);
        for (final int amount : List.of(0, 1, 3, 4, 9)) {
            final var plan = evaluate(damage("Planeswalker.OppCtrl", amount), initial);
            final var after = amount >= 4 ? PermanentProfile.absent() : planeswalker(false, 4 - amount, target.keywords());
            Assert.assertTrue(plan.complete());
            Assert.assertEquals(plan.state().opponentPermanent(), after);
            Assert.assertEquals(plan.value(), (double) utility.evaluatePermanentDelta(target, after, false), 1e-9);
        }
    }

    @Test
    public void damageRespectsTargetProtectionAndLifelinkUsesUncappedDamage() {
        final var source = new PermanentProfile(true, PermanentKind.CREATURE, true, 1, 1, Set.of("lifelink"));
        final var initial = new State(3, 3).withSourcePermanent(source).withLife(true, 5)
                .withPermanent(false, planeswalker(false, 1, Set.of()));
        final var plan = evaluate(damage("Planeswalker", 5), initial);
        Assert.assertTrue(plan.complete());
        Assert.assertEquals(plan.state().controllerLife(), 10);
        Assert.assertFalse(plan.state().opponentPermanent().present());
        for (final String protection : List.of("hexproof", "shroud")) {
            final var protectedState = initial.withPermanent(false, planeswalker(false, 4, Set.of(protection)));
            final var protectedPlan = evaluate(damage("Planeswalker.OppCtrl", 5), protectedState);
            Assert.assertEquals(protectedPlan.completeness(), OutcomePlan.Completeness.UNAVAILABLE);
            Assert.assertEquals(protectedPlan.value(), 0.0);
            Assert.assertEquals(protectedPlan.state(), protectedState);
        }
        final var friendly = initial.withPermanent(false, PermanentProfile.absent())
                .withPermanent(true, planeswalker(true, 4, Set.of("hexproof")));
        Assert.assertTrue(evaluate(damage("Planeswalker.YouCtrl", 1), friendly.withSourcePermanent(PermanentProfile.absent())).value() < 0);
    }

    @Test
    public void explicitCreaturePlaneswalkerUnionsChooseTheBestLegalRecipient() {
        final var initial = new State(3, 3).withPermanent(false, planeswalker(false, 4, Set.of()))
                .withCreatures(false, new CreatureProfile(true, 6, 6, Set.of(), false, false));
        final var plan = evaluate(damage("Creature.OppCtrl,Planeswalker.OppCtrl", 4), initial);
        Assert.assertTrue(plan.complete());
        Assert.assertFalse(plan.state().opponentPermanent().present());
        Assert.assertTrue(plan.state().opponentCreature().present());
        final var source = initial.withPermanent(false, PermanentProfile.absent())
                .withSourcePermanent(planeswalker(false, 4, Set.of()));
        Assert.assertFalse(evaluate(damage("Planeswalker.OppCtrl", 4), source).state().sourcePermanent().present());
        for (final String filter : List.of("Creature,Battle", "Planeswalker.cmcLE3", "Creature.YouCtrl,Planeswalker.OppCtrl")) {
            Assert.assertFalse(evaluate(damage(filter, 4), initial).complete());
        }
    }

    @Test
    public void exileOnDeathPreservesImmediateDamageValueWithoutInventingFutureCredit() {
        final var initial = new State(3, 3).withCreatures(false, new CreatureProfile(true, 2, 2, Set.of(), false, false));
        final var ordinary = damage("Creature.OppCtrl", 2);
        final var parameters = new java.util.HashMap<>(ordinary.parameters());
        parameters.put("ReplaceDyingDefined", "Targeted");
        final var replacement = new AbilityOutcomeDescription("exile", "DealDamage", parameters, List.of(), null, "");
        Assert.assertEquals(evaluate(replacement, initial).value(), evaluate(ordinary, initial).value());
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), SETTINGS);
        for (final String card : List.of("Magma Spray", "Scorching Dragonfire", "Obliterating Bolt")) {
            final var value = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard(card), CardStateName.Original).get(0);
            Assert.assertEquals(value.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED, card);
            Assert.assertTrue(value.contribution().value() > 0, card);
        }
    }
}
