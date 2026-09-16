package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.Test;
import forge.ai.AITest;
import forge.ai.CardDefinitionValueEvaluator;
import forge.ai.PlayerResourceValueEvaluator;
import forge.card.CardStateName;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;

/** Semantic coverage for game-free interpretation, composition and live compiler parity. */
public class AbilityTraversalTest extends AITest {
    @Test
    public void definitionExecuteIsResolvedWithoutGameAndUnsupportedOriginsRemainVisible() {
        host(); // Initialize the script database only; the definition path does not use its game.
        final IntrinsicAbilityEvaluator evaluator = new IntrinsicAbilityEvaluator(
                IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        final List<IntrinsicAbilityEvaluator.AbilityValue> results = evaluator.evaluateDefinition(
                        forge.StaticData.instance().getCommonCards().getCard("Staff of Nin"), CardStateName.Original);
        Assert.assertTrue(results.stream().anyMatch(r -> r.contribution().complete() && r.contribution().value() > 0),
                results + " " + CardAbilityTraversal.inspectDefinition(
                        forge.StaticData.instance().getCommonCards().getCard("Staff of Nin"), CardStateName.Original));
        // Staff's scheduled draw and simple tap ability are now supported; retain coverage that
        // unsupported origins remain visible by checking a card with a static ability as well.
        final IntrinsicAbilityEvaluator.DefinitionEvaluation staticCard = evaluator.evaluateDefinitionDetails(
                forge.StaticData.instance().getCommonCards().getCard("Glorious Anthem"),
                CardStateName.Original);
        Assert.assertTrue(staticCard.descriptions().stream().anyMatch(description ->
                description.origin() == CardAbilityTraversal.Origin.STATIC));
        Assert.assertTrue(staticCard.values().stream().anyMatch(value -> value.contribution().complete()
                && value.contribution().value() > 0), staticCard.values().toString());
    }

    @Test
    public void intrinsicStaticPAndTValueUsesSharedCreatureDelta() {
        final CardAbilityTraversal.AbilityDescription anthem =
                new CardAbilityTraversal.AbilityDescription("Original/static:0",
                        CardAbilityTraversal.Origin.STATIC,
                        CardAbilityTraversal.Provenance.PRINTED,
                        Map.of("Mode", "Continuous", "Affected", "Creature.YouCtrl+Other",
                                "AddPower", "1", "AddToughness", "1"),
                        AbilityOutcomeDescription.unresolved("static", "not an outcome"));
        final IntrinsicAbilityEvaluator.AbilityValue result = new IntrinsicAbilityEvaluator(
                IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults()).evaluate(
                        List.of(anthem), new IntrinsicReferenceModel.PermanentProfile(true,
                                IntrinsicReferenceModel.PermanentKind.CREATURE, true, 2, 2, Set.of()),
                        EntryTiming.NORMAL_SPEED).get(0);

        Assert.assertEquals(result.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(result.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(result.contribution().value(), 50.0);
    }

    @Test
    public void intrinsicStaticNegativeOpponentEffectUsesRecipientPolarity() {
        final CardAbilityTraversal.AbilityDescription weakness =
                new CardAbilityTraversal.AbilityDescription("Original/static:0",
                        CardAbilityTraversal.Origin.STATIC,
                        CardAbilityTraversal.Provenance.PRINTED,
                        Map.of("Mode", "Continuous", "Affected", "Creature.OppCtrl",
                                "AddToughness", "-1"),
                        AbilityOutcomeDescription.unresolved("static", "not an outcome"));
        final IntrinsicAbilityEvaluator.AbilityValue result = new IntrinsicAbilityEvaluator(
                IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults()).evaluate(
                        List.of(weakness), new IntrinsicReferenceModel.PermanentProfile(true,
                                IntrinsicReferenceModel.PermanentKind.CREATURE, true, 2, 2, Set.of()),
                        EntryTiming.NORMAL_SPEED).get(0);

        Assert.assertEquals(result.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(result.contribution().value(), 20.0);
    }

    @Test
    public void intrinsicStaticSupportedKeywordUsesPermanentDelta() {
        final CardAbilityTraversal.AbilityDescription evasion =
                new CardAbilityTraversal.AbilityDescription("Original/static:0",
                        CardAbilityTraversal.Origin.STATIC,
                        CardAbilityTraversal.Provenance.PRINTED,
                        Map.of("Mode", "Continuous", "Affected", "Creature.YouCtrl",
                                "AddKeyword", "Flying & First Strike"),
                        AbilityOutcomeDescription.unresolved("static", "not an outcome"));
        final IntrinsicAbilityEvaluator.AbilityValue result = new IntrinsicAbilityEvaluator(
                IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults()).evaluate(
                        List.of(evasion), new IntrinsicReferenceModel.PermanentProfile(true,
                                IntrinsicReferenceModel.PermanentKind.ENCHANTMENT, true, 0, 0, Set.of()),
                        EntryTiming.NORMAL_SPEED).get(0);

        Assert.assertEquals(result.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(result.contribution().value(), 80.0);
    }

    @Test
    public void cardDefinitionEvaluationIncludesSupportedIntrinsicAbilityValue() {
        host();
        final forge.item.PaperCard staff = forge.StaticData.instance().getCommonCards().getCard("Staff of Nin");
        final CardDefinitionValueEvaluator.Evaluation evaluation = new CardDefinitionValueEvaluator()
                .evaluate(staff.getRules(), staff.getEdition());
        Assert.assertTrue(evaluation.contributions().stream().anyMatch(contribution ->
                "Intrinsic ability".equals(contribution.category()) && contribution.value() > 0),
                evaluation.toString());
    }

    @Test
    public void definitionTokenOutcomeUsesStaticTokenProfile() {
        host();
        final List<IntrinsicAbilityEvaluator.AbilityValue> results = new IntrinsicAbilityEvaluator(
                IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults()).evaluateDefinition(
                        forge.StaticData.instance().getCommonCards().getCard("Wolverine Riders"),
                        CardStateName.Original);
        Assert.assertTrue(results.stream().anyMatch(r -> r.contribution().complete()
                && r.contribution().value() > 0), results.toString());
    }

    private Card host() {
        final forge.game.Game game = initAndCreateGame();
        return addCard("Grizzly Bears", game.getPlayers().get(0));
    }

    @Test
    public void traversalDoesNotReportImplicitCastAsPrintedAbility() {
        final Card card = host();
        Assert.assertFalse(CardAbilityTraversal.inspect(card.getCurrentState()).stream()
                .anyMatch(description -> description.origin() == CardAbilityTraversal.Origin.SPELL));
    }

    @Test
    public void traversalDistinguishesSharedBranchesFromCycles() {
        final Card card = host();
        final SpellAbility root = AbilityFactory.getAbility("DB$ Draw", card);
        final forge.game.spellability.AbilitySub child = (forge.game.spellability.AbilitySub)
                AbilityFactory.getAbility("DB$ Draw", card);
        root.setAdditionalAbilityList("Choices", List.of(child, child));
        final AbilityOutcomeDescription shared = AbilityOutcomeParser.parse(root, "root");
        Assert.assertEquals(shared.choices().size(), 2);
        Assert.assertTrue(shared.choices().stream().allMatch(c -> c.issue().isEmpty()));
        child.setAdditionalAbilityList("Choices", List.of(child));
        final AbilityOutcomeDescription cyclic = AbilityOutcomeParser.parse(root, "root");
        Assert.assertTrue(cyclic.choices().get(0).choices().get(0).issue().contains("Cyclic"));
    }

    @Test
    public void randomUnknownBranchesRetainProbabilityRatherThanRenormalizing() {
        final AbilityOutcomeDescription draw = new AbilityOutcomeDescription("draw", "Draw",
                Map.of(), List.of(), null, "");
        final AbilityOutcomeDescription random = new AbilityOutcomeDescription("random", "Charm",
                Map.of("Random", "True"), List.of(draw,
                        AbilityOutcomeDescription.unresolved("unknown", "Unmodeled branch")), null, "");
        final Outcome<IntrinsicDrawOutcomeBackend.State> outcome = new OutcomeDescriptionCompiler<>(
                new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults())).compile(random);
        final OutcomePlan<IntrinsicDrawOutcomeBackend.State> plan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>()
                .evaluate(outcome, new IntrinsicDrawOutcomeBackend.State(0, 0));
        Assert.assertEquals(plan.completeness(), OutcomePlan.Completeness.PARTIAL);
        Assert.assertEquals(plan.unresolvedProbability(), 0.5);
        Assert.assertEquals(plan.value(), PlayerResourceValueEvaluator.evaluateCardDraw(0, 1) * 0.5);
    }

    @Test
    public void intrinsicCostBearingModesAreNotFreeOutcomes() {
        final AbilityOutcomeDescription paid = new AbilityOutcomeDescription("paid", "Draw",
                Map.of("Cost", "3", "NumCards", "4"), List.of(), null, "");
        final Outcome<IntrinsicDrawOutcomeBackend.State> outcome = new OutcomeDescriptionCompiler<>(
                new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults())).compile(paid);
        final OutcomePlan<IntrinsicDrawOutcomeBackend.State> plan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>()
                .evaluate(outcome, new IntrinsicDrawOutcomeBackend.State(0, 0));
        Assert.assertEquals(plan.completeness(), OutcomePlan.Completeness.UNSUPPORTED);
        Assert.assertEquals(plan.value(), 0.0);
    }

    @Test
    public void intrinsicActivatedAbilityUsesReferenceManaAndStripsExecutionMetadata() {
        final CardAbilityTraversal.AbilityDescription activation =
                new CardAbilityTraversal.AbilityDescription("Original/ability:0",
                        CardAbilityTraversal.Origin.ACTIVATION,
                        CardAbilityTraversal.Provenance.PRINTED,
                        Map.of("AB", "DealDamage", "Cost", "2 T"),
                        new AbilityOutcomeDescription("activation", "DealDamage",
                                Map.of("Cost", "2 T", "Defined", "Opponent", "NumDmg", "1"),
                                List.of(), null, ""));
        final IntrinsicAbilityEvaluator.AbilityValue result = new IntrinsicAbilityEvaluator(
                IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults()).evaluate(
                        List.of(activation), new IntrinsicReferenceModel.PermanentProfile(true,
                                IntrinsicReferenceModel.PermanentKind.CREATURE, true, 1, 1, Set.of()),
                        EntryTiming.NORMAL_SPEED).get(0);

        Assert.assertEquals(result.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(result.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED,
                result.toString());
        Assert.assertTrue(result.expectedOccurrences() > 0, result.toString());
        Assert.assertTrue(result.contribution().value() > 0, result.toString());
    }

    @Test
    public void intrinsicSimpleSpellsUseTheSharedProbabilisticOutcomeBackend() {
        host();
        final IntrinsicAbilityEvaluator evaluator = new IntrinsicAbilityEvaluator(
                IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        for (final String cardName : List.of("Lightning Bolt", "Divination")) {
            final IntrinsicAbilityEvaluator.DefinitionEvaluation evaluation = evaluator
                    .evaluateDefinitionDetails(forge.StaticData.instance().getCommonCards()
                            .getCard(cardName), CardStateName.Original);
            Assert.assertTrue(evaluation.descriptions().stream().anyMatch(description ->
                    description.origin() == CardAbilityTraversal.Origin.SPELL), cardName);
            Assert.assertTrue(evaluation.values().stream().anyMatch(value ->
                    value.triggerStatus() == IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED
                            && value.outcomeStatus() == IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED
                            && value.contribution().value() > 0),
                    cardName + ": " + evaluation);
        }
    }

    @Test
    public void intrinsicSpellCastTriggerUsesReferenceSpellRate() {
        final CardAbilityTraversal.AbilityDescription trigger =
                new CardAbilityTraversal.AbilityDescription("Original/trigger:0",
                        CardAbilityTraversal.Origin.TRIGGER, CardAbilityTraversal.Provenance.PRINTED,
                        Map.of("Mode", "SpellCast", "ValidCard", "Instant,Sorcery",
                                "ValidActivatingPlayer", "You", "TriggerZones", "Battlefield"),
                        new AbilityOutcomeDescription("draw", "Draw",
                                Map.of("Defined", "You", "NumCards", "1"), List.of(), null, ""));
        final IntrinsicAbilityEvaluator.AbilityValue result = new IntrinsicAbilityEvaluator(
                IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults()).evaluate(
                        List.of(trigger), new IntrinsicReferenceModel.PermanentProfile(true,
                                IntrinsicReferenceModel.PermanentKind.CREATURE, true, 2, 2, Set.of()),
                        EntryTiming.NORMAL_SPEED).get(0);

        Assert.assertEquals(result.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(result.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED,
                result.toString());
        Assert.assertTrue(result.expectedOccurrences() > 0, result.toString());
        Assert.assertTrue(result.contribution().value() > 0, result.toString());
    }

    @Test
    public void printedSpellCastCardsReachIntrinsicTriggerEvaluation() {
        host();
        final IntrinsicAbilityEvaluator evaluator = new IntrinsicAbilityEvaluator(
                IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        for (final String cardName : List.of("Young Pyromancer", "Zendikar Resurgent")) {
            final IntrinsicAbilityEvaluator.DefinitionEvaluation evaluation = evaluator
                    .evaluateDefinitionDetails(forge.StaticData.instance().getCommonCards()
                            .getCard(cardName), CardStateName.Original);
            Assert.assertTrue(evaluation.descriptions().stream().anyMatch(description ->
                    description.origin() == CardAbilityTraversal.Origin.TRIGGER
                            && "SpellCast".equals(description.parameters().get("Mode"))), cardName);
            Assert.assertTrue(evaluation.values().stream().anyMatch(value ->
                    value.triggerStatus() == IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED
                            && value.outcomeStatus() == IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED
                            && value.contribution().value() > 0),
                    cardName + ": " + evaluation);
        }
    }

    @Test
    public void manaTapTriggersReachIntrinsicEvaluationWithBoundedReferenceRates() {
        host();
        final IntrinsicAbilityEvaluator evaluator = new IntrinsicAbilityEvaluator(
                IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        for (final String cardName : List.of("Groundchuck & Dirtbag", "Zendikar Resurgent")) {
            final IntrinsicAbilityEvaluator.DefinitionEvaluation evaluation = evaluator
                    .evaluateDefinitionDetails(forge.StaticData.instance().getCommonCards()
                            .getCard(cardName), CardStateName.Original);

            Assert.assertTrue(evaluation.descriptions().stream().anyMatch(description ->
                    description.origin() == CardAbilityTraversal.Origin.TRIGGER
                            && "TapsForMana".equals(description.parameters().get("Mode"))),
                    evaluation.toString());
            Assert.assertTrue(evaluation.values().stream().anyMatch(value ->
                    value.triggerStatus() == IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED
                            && value.outcomeStatus() == IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED
                            && value.expectedOccurrences() > 0
                            && value.contribution().value() > 0), evaluation.toString());
        }

        final IntrinsicEventTrigger trigger = IntrinsicEventTriggerAdapter.describe(Map.of(
                "Mode", "TapsForMana", "ValidCard", "Land", "Activator", "You")).orElseThrow();
        Assert.assertEquals(trigger.eventType(), IntrinsicReferenceModel.EventType.MANA_ADDED_OR_SPENT);
        Assert.assertEquals(trigger.turnScope(), IntrinsicEventTrigger.TurnScope.CONTROLLER_TURN);
        Assert.assertTrue(trigger.occurrenceMultiplier() > 0);
        Assert.assertTrue(IntrinsicEventTriggerAdapter.describe(Map.of(
                "Mode", "TapsForMana", "ValidCard", "Card.AttachedBy")).isEmpty());
    }

    @Test
    public void selfCounterTriggersReachIntrinsicEvaluation() {
        host();
        final IntrinsicAbilityEvaluator evaluator = new IntrinsicAbilityEvaluator(
                IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        final IntrinsicAbilityEvaluator.DefinitionEvaluation evaluation = evaluator
                .evaluateDefinitionDetails(forge.StaticData.instance().getCommonCards()
                        .getCard("Dusk Legion Duelist"), CardStateName.Original);

        Assert.assertTrue(evaluation.descriptions().stream().anyMatch(description ->
                description.origin() == CardAbilityTraversal.Origin.TRIGGER
                        && "CounterAddedOnce".equals(description.parameters().get("Mode"))),
                evaluation.toString());
        Assert.assertTrue(evaluation.values().stream().anyMatch(value ->
                value.triggerStatus() == IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED
                        && value.outcomeStatus() == IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED
                        && value.contribution().value() > 0),
                evaluation.toString());
    }

    @Test
    public void controllerAndOpponentLifeTriggersReachIntrinsicEvaluation() {
        host();
        final IntrinsicAbilityEvaluator evaluator = new IntrinsicAbilityEvaluator(
                IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        for (final String cardName : List.of("Hallowed Priest", "Exquisite Blood")) {
            final IntrinsicAbilityEvaluator.DefinitionEvaluation evaluation = evaluator
                    .evaluateDefinitionDetails(forge.StaticData.instance().getCommonCards()
                            .getCard(cardName), CardStateName.Original);
            Assert.assertTrue(evaluation.descriptions().stream().anyMatch(description ->
                    description.origin() == CardAbilityTraversal.Origin.TRIGGER
                            && Set.of("LifeGained", "LifeLost").contains(
                                    description.parameters().get("Mode"))), cardName);
            Assert.assertTrue(evaluation.values().stream().anyMatch(value ->
                    value.triggerStatus() == IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED),
                    cardName + ": " + evaluation);
            if ("Hallowed Priest".equals(cardName)) {
                Assert.assertTrue(evaluation.values().stream().anyMatch(value ->
                        value.outcomeStatus() == IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED
                                && value.contribution().value() > 0),
                        cardName + ": " + evaluation);
            } else {
                // Exquisite Blood's "that much" amount is a trigger-dependent value, which the
                // first intrinsic life-event slice intentionally leaves unresolved.
                Assert.assertTrue(evaluation.values().stream().anyMatch(value ->
                        value.outcomeStatus() == IntrinsicAbilityEvaluator.SupportStatus.UNSUPPORTED),
                        cardName + ": " + evaluation);
            }
        }
    }

    @Test
    public void explicitDiscardScopesReachIntrinsicEvaluation() {
        host();
        final IntrinsicAbilityEvaluator evaluator = new IntrinsicAbilityEvaluator(
                IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        for (final String cardName : List.of("Ivora, Insatiable Heir", "Liliana's Caress",
                "Dying to Serve")) {
            final IntrinsicAbilityEvaluator.DefinitionEvaluation evaluation = evaluator
                    .evaluateDefinitionDetails(forge.StaticData.instance().getCommonCards()
                            .getCard(cardName), CardStateName.Original);
            Assert.assertTrue(evaluation.values().stream().anyMatch(value ->
                    value.triggerStatus() == IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED),
                    cardName + ": " + evaluation);
        }
    }

    @Test
    public void deathAndSacrificeTriggersReachIntrinsicEvaluation() {
        host();
        final IntrinsicAbilityEvaluator evaluator = new IntrinsicAbilityEvaluator(
                IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        for (final String cardName : List.of("Blood Artist", "Zulaport Cutthroat", "Zhao, Ruthless Admiral")) {
            final IntrinsicAbilityEvaluator.DefinitionEvaluation evaluation = evaluator
                    .evaluateDefinitionDetails(forge.StaticData.instance().getCommonCards()
                            .getCard(cardName), CardStateName.Original);
            Assert.assertTrue(evaluation.values().stream().anyMatch(value ->
                    value.triggerStatus() == IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED),
                    cardName + ": " + evaluation);
        }
        final IntrinsicAbilityEvaluator.DefinitionEvaluation bloodArtist = evaluator
                .evaluateDefinitionDetails(forge.StaticData.instance().getCommonCards()
                        .getCard("Blood Artist"), CardStateName.Original);
        Assert.assertTrue(bloodArtist.values().stream().anyMatch(value ->
                value.outcomeStatus() == IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED
                        && value.contribution().value() > 0), bloodArtist.toString());
    }

    @Test
    public void selfDamageTriggersReachIntrinsicEvaluation() {
        host();
        final IntrinsicAbilityEvaluator evaluator = new IntrinsicAbilityEvaluator(
                IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        for (final String cardName : List.of("Jin Sakai, Ghost of Tsushima", "Zuko, Seeking Honor")) {
            final IntrinsicAbilityEvaluator.DefinitionEvaluation evaluation = evaluator
                    .evaluateDefinitionDetails(forge.StaticData.instance().getCommonCards()
                            .getCard(cardName), CardStateName.Original);
            Assert.assertTrue(evaluation.values().stream().anyMatch(value ->
                    value.triggerStatus() == IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED),
                    cardName + ": " + evaluation);
            if ("Jin Sakai, Ghost of Tsushima".equals(cardName)) {
                Assert.assertTrue(evaluation.values().stream().anyMatch(value ->
                        value.outcomeStatus() == IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED
                                && value.contribution().value() > 0),
                        cardName + ": " + evaluation);
            } else {
                Assert.assertTrue(evaluation.values().stream().anyMatch(value ->
                        value.outcomeStatus() == IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED
                                && value.contribution().value() > 0),
                        cardName + ": " + evaluation);
            }
        }
    }

    @Test
    public void intrinsicBackendRejectsUnsupportedOutcomeFamilies() {
        final IntrinsicReferenceModel.PermanentProfile friendly = new IntrinsicReferenceModel.PermanentProfile(
                true, IntrinsicReferenceModel.PermanentKind.CREATURE, true, 2, 2, Set.of());
        final IntrinsicReferenceModel.PermanentProfile opposing = new IntrinsicReferenceModel.PermanentProfile(
                true, IntrinsicReferenceModel.PermanentKind.CREATURE, false, 3, 3, Set.of());
        final IntrinsicDrawOutcomeBackend.State state = new IntrinsicDrawOutcomeBackend.State(
                3, 3, 20, 20, 3, 3, 2, 2,
                new IntrinsicReferenceModel.CreatureProfile(true, 2, 2, Set.of(), false, false),
                new IntrinsicReferenceModel.CreatureProfile(true, 3, 3, Set.of(), false, false),
                friendly, opposing, friendly, null);
        final IntrinsicDrawOutcomeBackend backend = new IntrinsicDrawOutcomeBackend(
                IntrinsicEvaluationSettings.defaults(), friendly);
        final List<AbilityOutcomeDescription> outcomes = List.of(
                description("DamageAll", Map.of("NumDmg", "1", "Defined", "Opponent")),
                description("Token", Map.of("TokenScript", "Soldier", "TokenAmount", "X")),
                description("Pump", Map.of("NumAtt", "1", "NumDef", "1", "Defined", "YouCtrl")),
                description("Debuff", Map.of("Keywords", "Flying", "Defined", "OpponentCtrl")),
                description("Detain", Map.of("Defined", "OpponentCtrl")),
                description("Animate", Map.of("Power", "4", "Toughness", "4", "Defined", "YouCtrl")),
                description("Destroy", Map.of("Defined", "OpponentCtrl")),
                description("ChangeZone", Map.of("Origin", "Battlefield", "Destination", "Exile",
                        "Defined", "OpponentCtrl")),
                description("Sacrifice", Map.of("SacValid", "Creature")),
                description("GainControl", Map.of("NewController", "You", "Defined", "OpponentCtrl")),
                description("CopyPermanent", Map.of("Defined", "OpponentCtrl")),
                description("Attach", Map.of("Defined", "YouCtrl")),
                description("Unattach", Map.of("Defined", "YouCtrl")),
                description("SetState", Map.of("Mode", "Transform", "Defined", "Self")));
        for (final AbilityOutcomeDescription description : outcomes) {
            final Outcome<IntrinsicDrawOutcomeBackend.State> compiled = new OutcomeDescriptionCompiler<>(backend)
                    .compile(description);
            final OutcomePlan<IntrinsicDrawOutcomeBackend.State> plan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>()
                    .evaluate(compiled, state);
            Assert.assertEquals(plan.completeness(), OutcomePlan.Completeness.UNSUPPORTED,
                    description.api() + ": " + plan);
            Assert.assertEquals(plan.value(), 0.0);
        }
    }

    private static AbilityOutcomeDescription description(final String api,
            final Map<String, String> parameters) {
        return new AbilityOutcomeDescription(api, api, parameters, List.of(), null, "");
    }

    @Test
    public void scheduledDrawTraversesAndUsesReferenceHandDistribution() {
        final Card card = host();
        final Trigger trigger = TriggerHandler.parseTrigger(
                "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | TriggerZones$ Battlefield", card, true);
        trigger.setOverridingAbility(AbilityFactory.getAbility("DB$ Draw | Defined$ You | NumCards$ 1", card));
        card.getCurrentState().addTrigger(trigger);
        final List<CardAbilityTraversal.AbilityDescription> descriptions = CardAbilityTraversal.inspect(card.getCurrentState());
        final CardAbilityTraversal.AbilityDescription found = descriptions.stream()
                .filter(a -> a.origin() == CardAbilityTraversal.Origin.TRIGGER).findFirst().orElseThrow();
        final IntrinsicReferenceModel model = IntrinsicReferenceModel.defaults();
        final IntrinsicAbilityEvaluator.AbilityValue result = new IntrinsicAbilityEvaluator(model,
                IntrinsicEvaluationSettings.defaults()).evaluate(List.of(found),
                        new IntrinsicReferenceModel.PermanentProfile(true, IntrinsicReferenceModel.PermanentKind.CREATURE,
                                true, 2, 2, Set.of()), EntryTiming.NORMAL_SPEED).get(0);
        final double draw = model.handSizes().entries().stream().mapToDouble(e -> e.weight()
                * PlayerResourceValueEvaluator.evaluateCardDraw(e.value(), 1)).sum();
        Assert.assertTrue(result.contribution().complete());
        Assert.assertEquals(result.contribution().value(), draw * result.expectedOccurrences(), 0.0000001);
        Assert.assertEquals(trigger.getOverridingAbility().getParam("NumCards"), "1");
        Assert.assertFalse(CardAbilityTraversal.inspectDefinition(card.getPaperCard(), CardStateName.Original)
                .stream().anyMatch(a -> a.origin() == CardAbilityTraversal.Origin.TRIGGER));
    }

    @Test
    public void implicitSelfCounterAndSecondMainTappedTriggerAreIntrinsicallyEvaluated() {
        host();
        final IntrinsicAbilityEvaluator.DefinitionEvaluation evaluation = new IntrinsicAbilityEvaluator(
                IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults())
                .evaluateDefinitionDetails(forge.StaticData.instance().getCommonCards()
                        .getCard("Reluctant Role Model"), CardStateName.Original);
        boolean found = false;
        for (int i = 0; i < evaluation.descriptions().size(); i++) {
            final Map<String, String> parameters = evaluation.descriptions().get(i).parameters();
            if ("Phase".equals(parameters.get("Mode")) && "Main".equals(parameters.get("Phase"))
                    && "2".equals(parameters.get("PhaseCount"))
                    && "Card.tapped".equals(parameters.get("IsPresent"))) {
                found = true;
                final IntrinsicAbilityEvaluator.AbilityValue value = evaluation.values().get(i);
                Assert.assertTrue(value.contribution().complete(), value.toString());
                Assert.assertTrue(value.contribution().value() > 0, value.toString());
            }
        }
        Assert.assertTrue(found, evaluation.descriptions().toString());
    }

    @Test
    public void relationshipSupportedEventTriggersHaveIntrinsicAdapters() {
        final List<Map<String, String>> parameters = List.of(
                Map.of("Mode", "TokenCreated", "ValidPlayer", "You"),
                Map.of("Mode", "TokenCreatedOnce"),
                Map.of("Mode", "CounterAdded"),
                Map.of("Mode", "CounterAddedOnce"),
                Map.of("Mode", "LifeGained"),
                Map.of("Mode", "LifeLost"),
                Map.of("Mode", "LifeLostAll"),
                Map.of("Mode", "Drawn", "ValidPlayer", "Player"),
                Map.of("Mode", "Discarded"),
                Map.of("Mode", "DiscardedAll"),
                Map.of("Mode", "DamageDone"),
                Map.of("Mode", "DamageDoneOnce"),
                Map.of("Mode", "DamageDealtOnce"),
                Map.of("Mode", "ChangesZone", "Origin", "Battlefield", "Destination", "Graveyard"),
                Map.of("Mode", "ChangesZoneAll", "Origin", "Battlefield", "Destination", "Graveyard"),
                Map.of("Mode", "Exiled", "Origin", "Battlefield"),
                Map.of("Mode", "Sacrificed"),
                Map.of("Mode", "SacrificedOnce"),
                Map.of("Mode", "Attacks", "ValidCard", "Card.Self"),
                Map.of("Mode", "Blocks", "ValidCard", "Card.Self"),
                Map.of("Mode", "AttackerBlocked", "ValidCard", "Card.Self"),
                Map.of("Mode", "AttackerBlockedByCreature", "ValidCard", "Card.Self"),
                Map.of("Mode", "AttackerUnblocked", "ValidCard", "Card.Self"),
                Map.of("Mode", "Taps", "ValidCard", "Card.Self"),
                Map.of("Mode", "Phase", "Phase", "Main", "PhaseCount", "2",
                        "ValidPlayer", "You", "PresentDefined", "Self", "IsPresent", "Card.tapped"));

        for (final Map<String, String> parameter : parameters) {
            Assert.assertTrue(IntrinsicEventTriggerAdapter.describe(parameter).isPresent(), parameter.toString());
        }
    }

    @Test
    public void eventTriggerOccurrenceUsesItsReferenceRateAndSurvivalHorizon() {
        final Card card = host();
        final Map<String, String> parameters = Map.of("Mode", "Drawn", "ValidPlayer", "Player");
        final CardAbilityTraversal.AbilityDescription description =
                new CardAbilityTraversal.AbilityDescription("Original/trigger:draw",
                CardAbilityTraversal.Origin.TRIGGER, CardAbilityTraversal.Provenance.PRINTED,
                parameters, new AbilityOutcomeDescription("draw", "Draw",
                        Map.of("Defined", "You", "NumCards", "1"), List.of(), null, ""));
        final IntrinsicReferenceModel model = IntrinsicReferenceModel.defaults();
        final IntrinsicAbilityEvaluator.AbilityValue result = new IntrinsicAbilityEvaluator(model,
                IntrinsicEvaluationSettings.defaults()).evaluate(List.of(description),
                        new IntrinsicReferenceModel.PermanentProfile(true,
                                IntrinsicReferenceModel.PermanentKind.CREATURE, true, 2, 2, Set.of()),
                        EntryTiming.NORMAL_SPEED).get(0);

        Assert.assertTrue(result.expectedOccurrences() > 0, result.toString());
        Assert.assertTrue(result.contribution().complete(), result.toString());
        Assert.assertTrue(result.contribution().value() > 0, result.toString());
    }

    @Test
    public void sequenceProjectsHandChangesAndUnknownChoiceRemainsPartial() {
        final Card card = host();
        card.setSVar("SecondDraw", "DB$ Draw | NumCards$ 1");
        final SpellAbility ability = AbilityFactory.getAbility("DB$ Draw | NumCards$ 1 | SubAbility$ SecondDraw", card);
        final IntrinsicDrawOutcomeBackend backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults());
        final OutcomeDescriptionCompiler<IntrinsicDrawOutcomeBackend.State> compiler = new OutcomeDescriptionCompiler<>(backend);
        final AbilityOutcomeDescription parsed = AbilityOutcomeParser.parse(ability, "draw");
        final OutcomePlan<IntrinsicDrawOutcomeBackend.State> sequence = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>()
                .evaluate(compiler.compile(parsed), new IntrinsicDrawOutcomeBackend.State(0, 0));
        Assert.assertEquals(sequence.state().controllerHand(), 2);
        Assert.assertEquals(sequence.value(), (double) PlayerResourceValueEvaluator.evaluateCardDraw(0, 2));
        final AbilityOutcomeDescription choice = new AbilityOutcomeDescription("choice", "Charm",
                Map.of("CharmNum", "1"), List.of(parsed,
                        AbilityOutcomeDescription.unresolved("unknown", "Unsupported effect")), null, "");
        final OutcomePlan<IntrinsicDrawOutcomeBackend.State> partial = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>()
                .evaluate(compiler.compile(choice), new IntrinsicDrawOutcomeBackend.State(0, 0));
        Assert.assertEquals(partial.completeness(), OutcomePlan.Completeness.PARTIAL);
        Assert.assertEquals(partial.unresolvedProbability(), 0.0);
        Assert.assertFalse(partial.unresolvedAlternatives().isEmpty());
    }

    @Test
    public void sharedCompilerMatchesLiveDrawSequenceAndPreservesSource() {
        final Card card = host();
        card.setSVar("SecondDraw", "DB$ Draw | NumCards$ 1");
        final SpellAbility ability = AbilityFactory.getAbility("DB$ Draw | NumCards$ 1 | SubAbility$ SecondDraw", card);
        ability.setActivatingPlayer(card.getController());
        // Populate a library so the live draw cap doesn't make the effect unavailable.
        for (int i = 0; i < 4; i++) {
            final Card libraryCard = Card.fromPaperCard(card.getPaperCard(), card.getController());
            card.getController().getZone(forge.game.zone.ZoneType.Library).add(libraryCard);
        }
        final int hand = card.getController().getCardsIn(forge.game.zone.ZoneType.Hand).size();
        final Map<String, String> before = Map.copyOf(ability.getMapParams());
        final OutcomePlan<OutcomeState> result = SpellAbilityOutcomePlanner.evaluate(ability,
                card.getController().getOpponents().get(0));
        Assert.assertTrue(result.complete(), result.reason());
        Assert.assertEquals(result.value(), (double) PlayerResourceValueEvaluator.evaluateCardDraw(hand, 2));
        Assert.assertEquals(ability.getMapParams(), before);
        Assert.assertNotNull(ability.getSubAbility());
        Assert.assertEquals(card.getController().getCardsIn(forge.game.zone.ZoneType.Hand).size(), hand);
    }
}
