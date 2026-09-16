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
        Assert.assertEquals(mana.value(), (double) PlayerResourceValueEvaluator.evaluateMana(2));
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
    public void genericCounterGroupsUseReferenceRecipientCounts() {
        final State initial = state(CREATURE, CREATURE, SOURCE);
        final OutcomePlan<State> friendly = evaluate(leaf("PutCounterAll", Map.of(
                "ValidCards", "Creature.YouCtrl", "CounterType", "P1P1")), initial);
        Assert.assertEquals(friendly.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(friendly.value(), 50.0);
        Assert.assertEquals(friendly.state().controllerCreature().power(), 3);
        Assert.assertEquals(friendly.state().sourcePermanent().power(), 3);

        final OutcomePlan<State> opposing = evaluate(leaf("PutCounterAll", Map.of(
                "ValidCards", "Creature.OppCtrl", "CounterType", "P1P1")), initial);
        Assert.assertEquals(opposing.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(opposing.value(), -25.0);
        Assert.assertEquals(opposing.state().opponentCreature().power(), 3);

        final OutcomePlan<State> other = evaluate(leaf("PutCounterAll", Map.of(
                "ValidCards", "Creature.YouCtrl+StrictlyOther", "CounterType", "P1P1")), initial);
        Assert.assertEquals(other.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(other.value(), 25.0);
        Assert.assertEquals(other.state().sourcePermanent(), SOURCE);
    }

    @Test
    public void permanentPumpOutcomesReuseCreatureDeltaAndGroupCounts() {
        final State initial = state(CREATURE, CREATURE, SOURCE);
        final OutcomePlan<State> self = evaluate(leaf("Pump", Map.of(
                "Defined", "Self", "NumAtt", "+1", "NumDef", "+2", "Duration", "Permanent")), initial);
        Assert.assertEquals(self.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(self.value(), 35.0);
        Assert.assertEquals(self.state().sourcePermanent().power(), 3);
        Assert.assertEquals(self.state().sourcePermanent().toughness(), 4);

        final OutcomePlan<State> group = evaluate(leaf("PumpAll", Map.of(
                "ValidCards", "Creature.YouCtrl", "NumAtt", "+1", "NumDef", "+1",
                "Duration", "Perpetual")), initial);
        Assert.assertEquals(group.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(group.value(), 50.0);
        Assert.assertEquals(group.state().controllerCreature().power(), 3);
        Assert.assertEquals(group.state().sourcePermanent().power(), 3);

        final OutcomePlan<State> temporary = evaluate(leaf("Pump", Map.of(
                "Defined", "Self", "NumAtt", "+1", "NumDef", "+1", "Duration", "UntilEndOfTurn")), initial);
        Assert.assertEquals(temporary.completeness(), Completeness.UNSUPPORTED);
    }

    @Test
    public void persistentKeywordOutcomesReuseCreatureDeltaAndGroupCounts() {
        final State initial = state(CREATURE, CREATURE, SOURCE);
        final OutcomePlan<State> self = evaluate(leaf("Pump", Map.of(
                "Defined", "Self", "KW", "Flying", "Duration", "Permanent")), initial);
        Assert.assertEquals(self.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(self.value(), 20.0);
        Assert.assertTrue(self.state().sourcePermanent().keywords().contains("flying"));

        final OutcomePlan<State> group = evaluate(leaf("PumpAll", Map.of(
                "ValidCards", "Creature.YouCtrl", "KW", "Flying", "Duration", "Perpetual")), initial);
        Assert.assertEquals(group.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(group.value(), 40.0);
        Assert.assertTrue(group.state().controllerCreature().keywords().contains("flying"));

        final PermanentProfile flyingSource = new PermanentProfile(true, PermanentKind.CREATURE,
                true, 2, 2, Set.of("Flying"));
        final OutcomePlan<State> loss = evaluate(leaf("Debuff", Map.of(
                "Defined", "Self", "Keywords", "Flying", "Duration", "Permanent")),
                state(CREATURE, CREATURE, flyingSource));
        Assert.assertEquals(loss.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(loss.value(), -20.0);
        Assert.assertTrue(loss.state().sourcePermanent().keywords().isEmpty());
    }

    @Test
    public void persistentAnimationUsesPermanentProfileDeltaAndGenericTargets() {
        final State initial = state(CREATURE, CREATURE, SOURCE);
        final OutcomePlan<State> self = evaluate(leaf("Animate", Map.of(
                "Defined", "Self", "Power", "4", "Toughness", "4", "Types", "Creature",
                "Keywords", "Flying", "Duration", "Permanent")), initial);
        Assert.assertEquals(self.completeness(), Completeness.COMPLETE);
        Assert.assertTrue(self.value() > 0);
        Assert.assertEquals(self.state().sourcePermanent().power(), 4);
        Assert.assertEquals(self.state().sourcePermanent().toughness(), 4);
        Assert.assertTrue(self.state().sourcePermanent().keywords().contains("flying"));

        final PermanentProfile artifact = new PermanentProfile(true, PermanentKind.ARTIFACT,
                true, 0, 0, Set.of());
        final State targetState = new State(0, 7, 20, 20, 3, 3, 1, 1, CREATURE, CREATURE,
                artifact, PermanentProfile.absent(), SOURCE, null);
        final OutcomePlan<State> target = evaluate(leaf("Animate", Map.of(
                "ValidTgts", "Permanent.YouCtrl", "Power", "3", "Toughness", "3",
                "Types", "Artifact,Creature", "Keywords", "Trample", "Duration", "Perpetual")),
                targetState);
        Assert.assertEquals(target.completeness(), Completeness.COMPLETE);
        Assert.assertTrue(target.value() > 0);
        Assert.assertEquals(target.state().controllerPermanent().kind(), PermanentKind.CREATURE);
        Assert.assertTrue(target.state().controllerPermanent().keywords().contains("trample"));

        final OutcomePlan<State> temporary = evaluate(leaf("Animate", Map.of(
                "Defined", "Self", "Power", "4", "Toughness", "4", "Types", "Creature",
                "Duration", "UntilEndOfTurn")), initial);
        Assert.assertEquals(temporary.completeness(), Completeness.UNSUPPORTED);
    }

    @Test
    public void persistentGroupAnimationUsesCreatureCountsAndSourceInteraction() {
        final State initial = state(CREATURE, CREATURE, SOURCE);
        final OutcomePlan<State> group = evaluate(leaf("AnimateAll", Map.of(
                "ValidCards", "Creature.YouCtrl", "Power", "4", "Toughness", "4",
                "Types", "Creature", "Keywords", "Flying", "Duration", "Permanent")), initial);
        Assert.assertEquals(group.completeness(), Completeness.COMPLETE);
        Assert.assertTrue(group.value() > 0);
        Assert.assertEquals(group.state().controllerCreature().power(), 4);
        Assert.assertEquals(group.state().sourcePermanent().toughness(), 4);
        Assert.assertTrue(group.state().sourcePermanent().keywords().contains("flying"));

        final OutcomePlan<State> opponentGroup = evaluate(leaf("AnimateAll", Map.of(
                "ValidCards", "Creature.OppCtrl", "Power", "4", "Toughness", "4",
                "Types", "Creature", "Duration", "Perpetual")), initial);
        Assert.assertEquals(opponentGroup.completeness(), Completeness.COMPLETE);
        Assert.assertTrue(opponentGroup.value() < 0);
        Assert.assertEquals(opponentGroup.state().opponentCreature().power(), 4);
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
    public void m1m1CountersUseTheSameSignedCreatureDelta() {
        final State initial = state(CREATURE,
                new CreatureProfile(true, 3, 3, Set.of(), false, false), SOURCE);
        final OutcomePlan<State> opposing = evaluate(leaf("PutCounter", Map.of(
                "ValidTgts", "Creature.OppCtrl", "CounterType", "M1M1")), initial);
        Assert.assertEquals(opposing.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(opposing.state().opponentCreature().power(), 2);
        Assert.assertEquals(opposing.state().opponentCreature().toughness(), 2);
        Assert.assertTrue(opposing.value() > 0);

        final OutcomePlan<State> friendly = evaluate(leaf("PutCounter", Map.of(
                "ValidTgts", "Creature.YouCtrl", "CounterType", "M1M1")), initial);
        Assert.assertEquals(friendly.value(), -25.0);
    }

    @Test
    public void loyaltyCountersUsePermanentValueForSelfPlaneswalkers() {
        final PermanentProfile planeswalker = new PermanentProfile(true, PermanentKind.PLANESWALKER,
                true, 0, 0, Set.of(), false, 3);
        final State initial = state(CreatureProfile.absent(), CreatureProfile.absent(), planeswalker);
        final OutcomePlan<State> result = evaluate(leaf("PutCounter", Map.of(
                "Defined", "Self", "CounterType", "LOYALTY")), initial);
        Assert.assertEquals(result.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(result.state().sourcePermanent().loyalty(), 4);
        Assert.assertEquals(result.value(), 8.0);
    }

    @Test
    public void shieldAndStunCountersReuseObservedCreatureKeywords() {
        final State initial = state(CREATURE, CREATURE, SOURCE);
        final OutcomePlan<State> shield = evaluate(leaf("PutCounter", Map.of(
                "ValidTgts", "Creature.YouCtrl", "CounterType", "SHIELD")), initial);
        Assert.assertEquals(shield.completeness(), Completeness.COMPLETE);
        Assert.assertTrue(shield.state().controllerCreature().keywords().stream()
                .anyMatch(keyword -> keyword.equalsIgnoreCase("SHIELD")));
        Assert.assertEquals(shield.value(), 45.0);

        final OutcomePlan<State> stun = evaluate(leaf("PutCounter", Map.of(
                "ValidTgts", "Creature.OppCtrl", "CounterType", "STUN")), initial);
        Assert.assertEquals(stun.completeness(), Completeness.COMPLETE);
        Assert.assertTrue(stun.state().opponentCreature().keywords().stream()
                .anyMatch(keyword -> keyword.equalsIgnoreCase("STUN")));
        Assert.assertEquals(stun.value(), 20.0);
    }

    @Test
    public void keywordCountersReuseCreatureAbilityValue() {
        final State initial = state(CREATURE, CREATURE, SOURCE);
        final OutcomePlan<State> lifelink = evaluate(leaf("PutCounter", Map.of(
                "ValidTgts", "Creature.YouCtrl", "CounterType", "Lifelink")), initial);
        Assert.assertEquals(lifelink.completeness(), Completeness.COMPLETE);
        Assert.assertTrue(lifelink.state().controllerCreature().keywords().contains("Lifelink"));
        Assert.assertEquals(lifelink.value(), 20.0);

        final OutcomePlan<State> indestructible = evaluate(leaf("PutCounter", Map.of(
                "ValidTgts", "Creature.YouCtrl", "CounterType", "Indestructible")), initial);
        Assert.assertEquals(indestructible.completeness(), Completeness.COMPLETE);
        Assert.assertTrue(indestructible.state().controllerCreature().keywords().contains("Indestructible"));
        Assert.assertEquals(indestructible.value(), 70.0);
    }

    @Test
    public void commaSeparatedCounterTypesChooseTheBestSupportedMode() {
        final State initial = state(CreatureProfile.absent(), CreatureProfile.absent(), SOURCE);
        final OutcomePlan<State> result = evaluate(leaf("PutCounter", Map.of(
                "Defined", "Self", "CounterType", "Flying,Lifelink,P1P1")), initial);
        Assert.assertEquals(result.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(result.state().sourcePermanent().power(), 3);
        Assert.assertEquals(result.state().sourcePermanent().toughness(), 3);
        Assert.assertEquals(result.value(), 25.0);
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
        Assert.assertEquals(backend().referenceDimensions(leaf("Destroy", Map.of("ValidTgts", "Creature"))), Set.of(
                IntrinsicDrawOutcomeBackend.CONTROLLER_CREATURE,
                IntrinsicDrawOutcomeBackend.OPPONENT_CREATURE));
    }

    @Test
    public void removalBounceAndSelfSacrificeProjectReferenceState() {
        final State initial = state(CREATURE,
                new CreatureProfile(true, 3, 3, Set.of(), false, false), SOURCE);
        final OutcomePlan<State> destroy = evaluate(leaf("Destroy", Map.of(
                "ValidTgts", "Creature.OppCtrl")), initial);
        Assert.assertEquals(destroy.completeness(), Completeness.COMPLETE);
        Assert.assertFalse(destroy.state().opponentCreature().present());
        Assert.assertTrue(destroy.value() > 0);

        final OutcomePlan<State> bounce = evaluate(leaf("ChangeZone", Map.of(
                "ValidTgts", "Creature.OppCtrl", "Origin", "Battlefield", "Destination", "Hand")), initial);
        Assert.assertEquals(bounce.completeness(), Completeness.COMPLETE);
        Assert.assertFalse(bounce.state().opponentCreature().present());
        Assert.assertEquals(bounce.state().opponentHand(), 8);
        Assert.assertTrue(bounce.value() > 0);

        final OutcomePlan<State> sacrifice = evaluate(leaf("Sacrifice", Map.of(
                "Defined", "Self")), initial);
        Assert.assertEquals(sacrifice.completeness(), Completeness.COMPLETE);
        Assert.assertFalse(sacrifice.state().sourcePermanent().present());
        Assert.assertTrue(sacrifice.value() < 0);
    }

    @Test
    public void selfSacrificeIgnoresIndestructible() {
        final PermanentProfile indestructibleSource = new PermanentProfile(true, PermanentKind.CREATURE,
                true, 2, 2, Set.of("indestructible"));
        final OutcomePlan<State> sacrifice = evaluate(leaf("Sacrifice", Map.of(
                "Defined", "Self")), state(CREATURE, CREATURE, indestructibleSource));
        Assert.assertEquals(sacrifice.completeness(), Completeness.COMPLETE);
        Assert.assertFalse(sacrifice.state().sourcePermanent().present());
        Assert.assertTrue(sacrifice.value() < 0);
    }

    @Test
    public void gainControlTransfersOpponentPermanentValueAndReferenceState() {
        final State initial = state(CREATURE,
                new CreatureProfile(true, 3, 3, Set.of("flying"), false, false), SOURCE);
        final OutcomePlan<State> result = evaluate(leaf("GainControl", Map.of(
                "ValidTgts", "Creature.OppCtrl", "NewController", "You")), initial);
        Assert.assertEquals(result.completeness(), Completeness.COMPLETE);
        Assert.assertFalse(result.state().opponentCreature().present());
        Assert.assertEquals(result.state().opponentCreatureCount(), 0);
        Assert.assertEquals(result.state().controllerCreatureCount(), 2);
        Assert.assertTrue(result.value() > 0);
    }

    @Test
    public void creatureSacrificeChoosesAReferenceCreatureAndProjectsItsRemoval() {
        final State initial = state(CREATURE,
                new CreatureProfile(true, 3, 3, Set.of(), false, false), SOURCE);
        final OutcomePlan<State> result = evaluate(leaf("Sacrifice", Map.of(
                "SacValid", "Creature.YouCtrl")), initial);
        Assert.assertEquals(result.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(result.state().controllerCreatureCount(), 0);
        Assert.assertTrue(result.value() < 0);

        final OutcomePlan<State> forcedOpponent = evaluate(leaf("Sacrifice", Map.of(
                "SacValid", "Creature.OppCtrl", "Amount", "1")), initial);
        Assert.assertEquals(forcedOpponent.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(forcedOpponent.state().opponentCreatureCount(), 0);
        Assert.assertTrue(forcedOpponent.value() > 0);
    }

    @Test
    public void sacrificeAllProjectsCreatureGroupsAndSourceWithoutDoubleCounting() {
        final State initial = state(CREATURE,
                new CreatureProfile(true, 3, 3, Set.of(), false, false), SOURCE);
        final OutcomePlan<State> result = evaluate(leaf("SacrificeAll", Map.of(
                "ValidCards", "Creature")), initial);
        Assert.assertEquals(result.completeness(), Completeness.COMPLETE);
        Assert.assertEquals(result.state().controllerCreatureCount(), 0);
        Assert.assertEquals(result.state().opponentCreatureCount(), 0);
        Assert.assertFalse(result.state().sourcePermanent().present());
    }

    @Test
    public void destroyAllProjectsCreatureGroupRemovalAndPreservesIndestructibleCreatures() {
        final State initial = state(CREATURE,
                new CreatureProfile(true, 3, 3, Set.of(), false, false), SOURCE);
        final OutcomePlan<State> opposing = evaluate(leaf("DestroyAll", Map.of(
                "ValidCards", "Creature.OppCtrl")), initial);
        Assert.assertEquals(opposing.completeness(), Completeness.COMPLETE);
        Assert.assertFalse(opposing.state().opponentCreature().present());
        Assert.assertEquals(opposing.state().opponentCreatureCount(), 0);
        Assert.assertTrue(opposing.state().sourcePermanent().present());
        Assert.assertTrue(opposing.value() > 0);

        final State indestructible = state(CREATURE,
                new CreatureProfile(true, 3, 3, Set.of("indestructible"), false, false), SOURCE);
        final OutcomePlan<State> protectedGroup = evaluate(leaf("DestroyAll", Map.of(
                "ValidCards", "Creature.OppCtrl")), indestructible);
        Assert.assertEquals(protectedGroup.completeness(), Completeness.COMPLETE);
        Assert.assertTrue(protectedGroup.state().opponentCreature().present());
        Assert.assertEquals(protectedGroup.state().opponentCreatureCount(), 1);
        Assert.assertEquals(protectedGroup.value(), 0.0);
    }

    @Test
    public void changeZoneAllProjectsCreatureGroupExileAndBounceIncludingTokenRules() {
        final State initial = state(CREATURE,
                new CreatureProfile(true, 3, 3, Set.of(), false, false), SOURCE);
        final OutcomePlan<State> bounce = evaluate(leaf("ChangeZoneAll", Map.of(
                "ChangeType", "Creature.OppCtrl", "Origin", "Battlefield",
                "Destination", "Hand")), initial);
        Assert.assertEquals(bounce.completeness(), Completeness.COMPLETE);
        Assert.assertFalse(bounce.state().opponentCreature().present());
        Assert.assertEquals(bounce.state().opponentCreatureCount(), 0);
        Assert.assertEquals(bounce.state().opponentHand(), 8);
        Assert.assertTrue(bounce.value() > 0);

        final OutcomePlan<State> exile = evaluate(leaf("ChangeZoneAll", Map.of(
                "ChangeType", "Creature.OppCtrl", "Origin", "Battlefield",
                "Destination", "Exile")), initial);
        Assert.assertEquals(exile.completeness(), Completeness.COMPLETE);
        Assert.assertFalse(exile.state().opponentCreature().present());
        Assert.assertEquals(exile.state().opponentHand(), 7);

        final PermanentProfile token = new PermanentProfile(true, PermanentKind.TOKEN,
                true, 2, 2, Set.of());
        final OutcomePlan<State> tokenBounce = evaluate(leaf("ChangeZoneAll", Map.of(
                "ChangeType", "Creature.YouCtrl", "Origin", "Battlefield",
                "Destination", "Hand")), state(CreatureProfile.absent(),
                        CreatureProfile.absent(), token));
        Assert.assertEquals(tokenBounce.completeness(), Completeness.COMPLETE);
        Assert.assertFalse(tokenBounce.state().sourcePermanent().present());
        Assert.assertEquals(tokenBounce.state().controllerHand(), 0);
    }

    @Test
    public void fightProjectsSimultaneousDeathsAndCombatKeywords() {
        final PermanentProfile fighter = new PermanentProfile(true, PermanentKind.CREATURE,
                true, 3, 3, Set.of("indestructible"));
        final State initial = state(CREATURE,
                new CreatureProfile(true, 2, 2, Set.of(), false, false), fighter);
        final OutcomePlan<State> result = evaluate(leaf("Fight", Map.of(
                "Defined", "Self", "ValidTgts", "Creature.OppCtrl")), initial);
        Assert.assertEquals(result.completeness(), Completeness.COMPLETE);
        Assert.assertFalse(result.state().opponentCreature().present());
        Assert.assertEquals(result.state().opponentCreatureCount(), 0);
        Assert.assertTrue(result.state().sourcePermanent().present());
        Assert.assertTrue(result.value() > 0);

        final PermanentProfile fragileFighter = new PermanentProfile(true, PermanentKind.CREATURE,
                true, 1, 1, Set.of());
        final OutcomePlan<State> unfavorable = evaluate(leaf("Fight", Map.of(
                "Defined", "Self", "ValidTgts", "Creature.OppCtrl")), state(CREATURE,
                        new CreatureProfile(true, 3, 3, Set.of(), false, false), fragileFighter));
        Assert.assertEquals(unfavorable.completeness(), Completeness.COMPLETE);
        Assert.assertFalse(unfavorable.state().sourcePermanent().present());
        Assert.assertTrue(unfavorable.state().opponentCreature().present());
        Assert.assertTrue(unfavorable.value() < 0);
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
    public void selfTapTriggersUseTappedOrAttackRatesAndFirstTimeIsBounded() {
        final AbilityOutcomeDescription self = leaf("PutCounter", Map.of(
                "Defined", "Self", "CounterType", "P1P1"));
        final IntrinsicReferenceModel model = IntrinsicReferenceModel.defaults();
        final IntrinsicEvaluationSettings settings = IntrinsicEvaluationSettings.defaults();
        final double tapped = IntrinsicEventTriggerEstimator.estimate(
                IntrinsicEventTriggerAdapter.describe(Map.of("Mode", "Taps", "ValidCard", "Card.Self"))
                        .orElseThrow(), SOURCE, model, settings, EntryTiming.NORMAL_SPEED).expectedOccurrences();
        final IntrinsicAbilityEvaluator.AbilityValue result = evaluateAbility(self,
                Map.of("Mode", "Taps", "ValidCard", "Card.Self"));
        Assert.assertTrue(result.contribution().complete(), result.toString());
        Assert.assertEquals(result.expectedOccurrences(), tapped, .0000001);
        Assert.assertEquals(result.contribution().value(), tapped * 25, .0000001);

        final Map<String, String> attackerTap = Map.of("Mode", "Taps", "ValidCard", "Card.Self",
                "Attacker", "True", "FirstTime", "True");
        final IntrinsicEventTrigger trigger = IntrinsicEventTriggerAdapter.describe(attackerTap).orElseThrow();
        Assert.assertEquals(trigger.eventType(), IntrinsicReferenceModel.EventType.ATTACK);
        Assert.assertTrue(trigger.atMostOncePerTurn());
        Assert.assertTrue(evaluateAbility(self, attackerTap).contribution().complete());
        Assert.assertFalse(evaluateAbility(self, Map.of("Mode", "Taps", "ValidCard", "Card.Self",
                "Attacker", "False")).contribution().complete());
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
