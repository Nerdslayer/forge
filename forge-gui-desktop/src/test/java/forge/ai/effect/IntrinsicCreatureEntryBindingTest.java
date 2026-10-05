package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.CardStateName;
import forge.item.PaperCard;
import forge.model.FModel;

/** Entering-object quantities and source identity remain separate from host survival/targets. */
public class IntrinsicCreatureEntryBindingTest extends AITest {
    private static IntrinsicReferenceModel model(final IntrinsicReferenceModel.CreatureProfile... profiles) {
        final var base = IntrinsicReferenceModel.defaults();
        final var entries = java.util.Arrays.stream(profiles)
                .map(profile -> new WeightedValue<>(profile, 1.0 / profiles.length)).toList();
        return new IntrinsicReferenceModel(base.lifeTotals(), base.handSizes(), base.availableMana(),
                base.friendlyCreatureCounts(), base.opposingCreatureCounts(), new WeightedDistribution<>(entries),
                base.permanentProfiles(), java.util.Arrays.stream(IntrinsicReferenceModel.PermanentKind.values())
                        .filter(kind -> base.targetAvailability(kind) != null)
                        .collect(java.util.stream.Collectors.toMap(kind -> kind, base::targetAvailability)),
                java.util.Arrays.stream(IntrinsicReferenceModel.EventType.values())
                        .filter(type -> base.eventRates(type) != null)
                        .collect(java.util.stream.Collectors.toMap(type -> type, base::eventRates)));
    }

    private static PaperCard definition(final String filter, final String outcome, final String variable) {
        return new PaperCard(CardRules.fromScript(List.of("Name:Entering Creature Probe", "ManaCost:2 G",
                "Types:Enchantment", "T:Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield | ValidCard$ "
                        + filter + " | TriggerZones$ Battlefield | Execute$ Benefit",
                "SVar:Benefit:" + outcome, "SVar:X:" + variable, "Oracle:Entering creature benefit.")),
                CardEdition.UNKNOWN_CODE, CardRarity.Special);
    }

    private static IntrinsicAbilityEvaluator.AbilityValue evaluate(final IntrinsicReferenceModel model,
            final PaperCard definition) {
        return new IntrinsicAbilityEvaluator(model, IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(definition, CardStateName.Original).get(0);
    }

    @Test
    public void enteringPowerAndToughnessUseTheSameQualifyingCreatureNotTheHost() {
        final var small = new IntrinsicReferenceModel.CreatureProfile(true, 1, 4, Set.of(), false, false);
        final var large = new IntrinsicReferenceModel.CreatureProfile(true, 4, 1, Set.of(), false, false);
        final var card = definition("Creature.YouCtrl+powerGE4", "DB$ Draw | Defined$ You | NumCards$ X",
                "TriggeredCard$CardPower");
        final var mixed = evaluate(model(small, large), card);
        final var allLarge = evaluate(model(large), card);
        Assert.assertEquals(mixed.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(mixed.contribution().value() > 0);
        Assert.assertEquals(mixed.contribution().value(), allLarge.contribution().value() * .5, 1e-9);
        Assert.assertEquals(mixed.expectedOccurrences(), allLarge.expectedOccurrences() * .5, 1e-9);
        final var toughness = evaluate(model(large), definition("Creature.YouCtrl",
                "DB$ Draw | Defined$ TriggeredCardController | NumCards$ X", "TriggeredCard$CardToughness"));
        Assert.assertEquals(toughness.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(allLarge.contribution().value() > toughness.contribution().value());
        final var opponent = evaluate(model(large), definition("Creature.OppCtrl",
                "DB$ Draw | Defined$ TriggeredCardController | NumCards$ X", "TriggeredCard$CardToughness"));
        Assert.assertEquals(opponent.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(opponent.contribution().value() < 0);
        final var unavailable = evaluate(model(small, large), definition("Creature.YouCtrl+powerGE99",
                "DB$ Draw | NumCards$ X", "TriggeredCard$CardPower"));
        Assert.assertEquals(unavailable.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(unavailable.contribution().unavailableCaseProbability(), 1.0);
        Assert.assertEquals(unavailable.expectedOccurrences(), 0.0);
    }

    @Test
    public void realTypedRemovalAndWatchedDamageDefinitionsReachTheGenericAdapters() {
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        for (final String name : List.of("Aether Flash", "Abrade", "Aura Shards", "Naturalize", "Disenchant")) {
            final var card = FModel.getMagicDb().getCommonCards().getCard(name);
            final var values = evaluator.evaluateDefinition(card, CardStateName.Original);
            Assert.assertFalse(values.isEmpty(), name);
            Assert.assertEquals(values.get(0).outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED, name);
            Assert.assertTrue(values.get(0).contribution().complete(), name);
        }
    }

    @Test
    public void realEnteringDamageUsesBoundPowerAndLifelink() {
        final var vanilla = new IntrinsicReferenceModel.CreatureProfile(true, 3, 3, Set.of(), false, false);
        final var lifelink = new IntrinsicReferenceModel.CreatureProfile(true, 3, 3, Set.of("Lifelink"), false, false);
        final var card = FModel.getMagicDb().getCommonCards().getCard("Warstorm Surge");
        final var supported = new IntrinsicAbilityEvaluator(model(vanilla), IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(card, CardStateName.Original).get(0);
        Assert.assertEquals(supported.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(supported.contribution().value() > 0);
        final var complete = new IntrinsicAbilityEvaluator(model(vanilla, lifelink), IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(card, CardStateName.Original).get(0);
        Assert.assertTrue(complete.contribution().complete());
        Assert.assertEquals(complete.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(complete.contribution().value() > 0);
    }

    @Test
    public void damageKeywordsBelongToEnteringCreatureRatherThanTriggerHost() {
        final var host = new IntrinsicReferenceModel.PermanentProfile(true,
                IntrinsicReferenceModel.PermanentKind.ENCHANTMENT, true, 0, 0, Set.of());
        final var victim = new IntrinsicReferenceModel.CreatureProfile(true, 3, 4, Set.of(), false, false);
        final var state = new IntrinsicDrawOutcomeBackend.State(3, 3).withLife(true, 15).withLife(false, 15)
                .withSourcePermanent(host).withCreatures(false, victim).withCreatureCount(false, 1);
        final var damage = new AbilityOutcomeDescription("enter-damage", "DealDamage",
                Map.of("ValidTgts", "Creature.OppCtrl", "NumDmg", "1", "DamageSource", "TriggeredCard"),
                List.of(), null, "");
        for (final boolean deathtouch : List.of(false, true)) {
            final var entering = new IntrinsicReferenceModel.PermanentProfile(true,
                    IntrinsicReferenceModel.PermanentKind.CREATURE, true, 1, 1,
                    deathtouch ? Set.of("Deathtouch") : Set.of());
            final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults(), host,
                    script -> java.util.Optional.empty(), IntrinsicLibraryReference.defaults(), entering);
            final var plan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>(100)
                    .evaluate(new OutcomeDescriptionCompiler<>(backend).compile(damage), state);
            Assert.assertTrue(plan.supported());
            Assert.assertEquals(plan.value() > 0, deathtouch);
            Assert.assertEquals(plan.state().sourcePermanent(), host);
        }
    }

    @Test
    public void watchedDamageUsesProjectedKeywordsAndRetainsThemAfterDeparture() {
        final var host = new IntrinsicReferenceModel.PermanentProfile(true,
                IntrinsicReferenceModel.PermanentKind.ENCHANTMENT, true, 0, 0, Set.of());
        final var entrant = new IntrinsicReferenceModel.PermanentProfile(true,
                IntrinsicReferenceModel.PermanentKind.CREATURE, true, 2, 2, Set.of());
        final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults(), host,
                script -> java.util.Optional.empty(), IntrinsicLibraryReference.defaults(), entrant);
        final var damage = new AbilityOutcomeDescription("damage", "DealDamage", Map.of("ValidTgts", "Creature.OppCtrl",
                "NumDmg", "1", "DamageSource", "TriggeredCard"), List.of(), null, "");
        final var grant = new AbilityOutcomeDescription("grant", "Pump", Map.of("Defined", "TriggeredCard",
                "KW", "Deathtouch & Lifelink", "Duration", "Permanent"), List.of(), null, "");
        final var state = new IntrinsicDrawOutcomeBackend.State(3, 3).withSourcePermanent(host).withWatchedCreature(entrant)
                .withLife(true, 10).withCreatures(false, new IntrinsicReferenceModel.CreatureProfile(true, 4, 4, Set.of(), false, false));
        final var compiler = new OutcomeDescriptionCompiler<>(backend);
        final var granted = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>().evaluate(compiler.compile(grant), state);
        Assert.assertTrue(granted.complete());
        final var removed = new AbilityOutcomeDescription("remove", "Destroy", Map.of("Defined", "TriggeredCard"), List.of(), null, "");
        final var departed = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>().evaluate(compiler.compile(removed), granted.state());
        Assert.assertTrue(departed.complete());
        for (final var projected : List.of(granted.state(), departed.state())) {
            final var result = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>().evaluate(compiler.compile(damage), projected);
            Assert.assertTrue(result.complete());
            Assert.assertFalse(result.state().opponentCreature().present());
            Assert.assertEquals(result.state().controllerLife(), 11);
        }
        final var sequence = new AbilityOutcomeDescription(grant.path(), grant.api(), grant.parameters(), List.of(), damage, "");
        Assert.assertTrue(new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>().evaluate(compiler.compile(sequence), state).complete());
    }

    @Test
    public void watchedDamageAndRemovalUseOneDistinctProjectedRecipient() {
        final var host = new IntrinsicReferenceModel.PermanentProfile(true,
                IntrinsicReferenceModel.PermanentKind.ENCHANTMENT, true, 0, 0, Set.of());
        final var unrelated = new IntrinsicReferenceModel.CreatureProfile(true, 5, 5, Set.of(), false, false);
        for (final boolean friendly : List.of(false, true)) {
            for (final String api : List.of("DealDamage", "Destroy", "ChangeZone")) {
                final var entrant = new IntrinsicReferenceModel.PermanentProfile(true,
                        IntrinsicReferenceModel.PermanentKind.CREATURE, friendly, 2, 2, Set.of("Hexproof"));
                final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults(), host,
                        script -> java.util.Optional.empty(), IntrinsicLibraryReference.defaults(), entrant);
                final var parameters = new java.util.HashMap<String, String>();
                parameters.put("Defined", "TriggeredCardLKICopy");
                if ("DealDamage".equals(api)) { parameters.put("NumDmg", "2"); }
                if ("ChangeZone".equals(api)) {
                    parameters.put("Origin", "Battlefield");
                    parameters.put("Destination", "Exile");
                }
                final var draw = new AbilityOutcomeDescription("followup", "Draw",
                        Map.of("Defined", "You", "NumCards", "0"), List.of(), null, "");
                final var outcome = new AbilityOutcomeDescription("watched", api, parameters, List.of(), draw, "");
                final var state = new IntrinsicDrawOutcomeBackend.State(3, 3).withSourcePermanent(host)
                        .withCreatures(false, unrelated).withCreatureCount(false, 3).withWatchedCreature(entrant);
                final var plan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>(100)
                        .evaluate(new OutcomeDescriptionCompiler<>(backend).compile(outcome), state);
                Assert.assertTrue(plan.complete(), api);
                Assert.assertEquals(plan.value() > 0, !friendly, api);
                Assert.assertFalse(plan.state().watchedCreature().present(), api);
                Assert.assertEquals(plan.state().sourcePermanent(), host);
                Assert.assertEquals(plan.state().opponentCreature(), unrelated);
                Assert.assertEquals(plan.state().opponentCreatureCount(), 3);
            }
        }
    }

    @Test
    public void absentWatchedRecipientsDoNotEraseIndependentFollowupEffects() {
        final var host = new IntrinsicReferenceModel.PermanentProfile(true,
                IntrinsicReferenceModel.PermanentKind.ENCHANTMENT, true, 0, 0, Set.of());
        final var departed = new IntrinsicReferenceModel.PermanentProfile(true,
                IntrinsicReferenceModel.PermanentKind.CREATURE, false, 2, 2, Set.of());
        final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults(), host,
                script -> java.util.Optional.empty(), IntrinsicLibraryReference.defaults(), departed);
        final var draw = new AbilityOutcomeDescription("followup", "Draw",
                Map.of("Defined", "You", "NumCards", "1"), List.of(), null, "");
        for (final String api : List.of("Destroy", "DealDamage")) {
            final Map<String, String> parameters = "Destroy".equals(api)
                    ? Map.of("Defined", "TriggeredCard") : Map.of("Defined", "TriggeredCard", "NumDmg", "2");
            final var outcome = new AbilityOutcomeDescription("absent", api, parameters, List.of(), draw, "");
            final var state = new IntrinsicDrawOutcomeBackend.State(3, 3).withSourcePermanent(host);
            final var plan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>(100)
                    .evaluate(new OutcomeDescriptionCompiler<>(backend).compile(outcome), state);
            Assert.assertTrue(plan.complete());
            Assert.assertEquals(plan.state().controllerHand(), 4);
            Assert.assertTrue(plan.value() > 0);
        }
        final var damage = new AbilityOutcomeDescription("damage", "DealDamage",
                Map.of("Defined", "TriggeredCard", "NumDmg", "1"), List.of(), null, "");
        final var twice = new AbilityOutcomeDescription("first", "DealDamage", damage.parameters(), List.of(), damage, "");
        final var state = new IntrinsicDrawOutcomeBackend.State(3, 3).withWatchedCreature(departed);
        Assert.assertFalse(new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>(100)
                .evaluate(new OutcomeDescriptionCompiler<>(backend).compile(twice), state).complete());
    }

    @Test
    public void watchedCountersAndPersistentChangesReuseProjectedCharacteristics() {
        final var host = new IntrinsicReferenceModel.PermanentProfile(true,
                IntrinsicReferenceModel.PermanentKind.ENCHANTMENT, true, 0, 0, Set.of());
        for (final boolean friendly : List.of(true, false)) {
            final var entrant = new IntrinsicReferenceModel.PermanentProfile(true,
                    IntrinsicReferenceModel.PermanentKind.CREATURE, friendly, 2, 2, Set.of("Hexproof"));
            final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults(), host,
                    script -> java.util.Optional.empty(), IntrinsicLibraryReference.defaults(), entrant);
            final var pump = new AbilityOutcomeDescription("pump", "Pump", Map.of("Defined", "TriggeredCard",
                    "NumAtt", "1", "NumDef", "1", "Duration", "Permanent", "KW", "Flying"), List.of(), null, "");
            final var counter = new AbilityOutcomeDescription("counter", "PutCounter", Map.of("Defined", "TriggeredCard",
                    "CounterType", "P1P1", "CounterNum", "1"), List.of(), pump, "");
            final var state = new IntrinsicDrawOutcomeBackend.State(3, 3).withSourcePermanent(host).withWatchedCreature(entrant);
            final var plan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>(100)
                    .evaluate(new OutcomeDescriptionCompiler<>(backend).compile(counter), state);
            Assert.assertTrue(plan.complete());
            Assert.assertEquals(plan.value() > 0, friendly);
            Assert.assertEquals(plan.state().watchedCreature().power(), 4);
            Assert.assertEquals(plan.state().watchedCreature().toughness(), 4);
            Assert.assertTrue(plan.state().watchedCreature().keywords().stream().anyMatch("Flying"::equalsIgnoreCase));
            Assert.assertEquals(plan.state().p1p1(IntrinsicDrawOutcomeBackend.TargetRef.WATCHED_CREATURE), 1);
            Assert.assertEquals(plan.state().sourcePermanent(), host);
            final var removeFlying = new AbilityOutcomeDescription("loss", "Debuff", Map.of("Defined", "TriggeredCard",
                    "Keywords", "Flying", "Duration", "Permanent"), List.of(), null, "");
            final var loss = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>(100)
                    .evaluate(new OutcomeDescriptionCompiler<>(backend).compile(removeFlying), plan.state());
            Assert.assertTrue(loss.complete());
            Assert.assertEquals(loss.value() < 0, friendly);
            Assert.assertFalse(loss.state().watchedCreature().keywords().stream().anyMatch("Flying"::equalsIgnoreCase));
        }
        final var small = new IntrinsicReferenceModel.CreatureProfile(true, 2, 2, Set.of(), false, false);
        final var supported = evaluate(model(small), definition("Creature.Other+YouCtrl",
                "DB$ PutCounter | Defined$ TriggeredCard | CounterType$ P1P1 | CounterNum$ 1", "Number$1"));
        Assert.assertEquals(supported.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(supported.contribution().value() > 0);
        final var mutable = evaluate(model(small), definition("Creature.Other+YouCtrl",
                "DB$ PutCounter | Defined$ TriggeredCard | CounterType$ P1P1 | CounterNum$ X", "TriggeredCard$CardPower"));
        Assert.assertFalse(mutable.contribution().complete());
    }

    @Test
    public void watchedControllerTokenRecipientsHaveConsistentOwnershipAcrossEvents() {
        final var token = new AbilityOutcomeDescription("token", "Token",
                Map.of("TokenOwner", "TriggeredCardController", "TokenScript", "reference"), List.of(), null, "");
        final var tokenProfile = new IntrinsicReferenceModel.PermanentProfile(true,
                IntrinsicReferenceModel.PermanentKind.TOKEN, true, 1, 1, Set.of());
        final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults(),
                IntrinsicReferenceModel.PermanentProfile.absent(), script -> java.util.Optional.of(tokenProfile));
        for (final boolean friendly : List.of(true, false)) {
            final var eventObject = new IntrinsicReferenceModel.PermanentProfile(true,
                    IntrinsicReferenceModel.PermanentKind.CREATURE, friendly, 2, 2, Set.of());
            for (final boolean stillPresent : List.of(true, false)) {
                final var binding = new IntrinsicWatchedCreatureBinding(eventObject, stillPresent);
                final var plan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>()
                        .evaluate(new OutcomeDescriptionCompiler<>(backend).compile(binding.bindOutcome(token)),
                                new IntrinsicDrawOutcomeBackend.State(3, 3).withCreatureCount(true, 0).withCreatureCount(false, 0));
                Assert.assertTrue(plan.complete());
                Assert.assertEquals(plan.value() > 0, friendly);
                Assert.assertEquals(plan.state().creatureCount(friendly), 1);
                Assert.assertEquals(plan.state().creatureCount(!friendly), 0);
            }
        }
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        final var thallid = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard("Deathbloom Thallid"),
                CardStateName.Original).stream().filter(value -> value.path().endsWith("trigger:0")).findFirst().orElseThrow();
        Assert.assertEquals(thallid.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(thallid.contribution().value() > 0);
    }

    @Test
    public void watchedCounterInventoryIsSharedAcrossPlacementMultiplicationAndRemoval() {
        final var host = new IntrinsicReferenceModel.PermanentProfile(true,
                IntrinsicReferenceModel.PermanentKind.ENCHANTMENT, true, 0, 0, Set.of());
        final var watched = IntrinsicDrawOutcomeBackend.TargetRef.WATCHED_CREATURE;
        for (final boolean friendly : List.of(true, false)) {
            final var entrant = new IntrinsicReferenceModel.PermanentProfile(true,
                    IntrinsicReferenceModel.PermanentKind.CREATURE, friendly, 2, 2, Set.of("Hexproof"));
            final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults(), host,
                    script -> java.util.Optional.empty(), IntrinsicLibraryReference.defaults(), entrant);
            final var state = new IntrinsicDrawOutcomeBackend.State(3, 3).withSourcePermanent(host)
                    .withWatchedCreature(entrant).withP1p1(watched, 0);
            final var remove = new AbilityOutcomeDescription("remove", "RemoveCounter",
                    Map.of("Defined", "TriggeredCard", "CounterType", "P1P1", "CounterNum", "3"), List.of(), null, "");
            final var multiply = new AbilityOutcomeDescription("multiply", "MultiplyCounter",
                    Map.of("Defined", "TriggeredCardLKICopy", "CounterType", "P1P1", "Multiplier", "2"), List.of(), remove, "");
            final var put = new AbilityOutcomeDescription("put", "PutCounter",
                    Map.of("Defined", "TriggeredCard", "CounterType", "P1P1", "CounterNum", "2"), List.of(), multiply, "");
            final var compiler = new OutcomeDescriptionCompiler<>(backend);
            final var plan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>(100).evaluate(compiler.compile(put), state);
            Assert.assertTrue(plan.complete());
            Assert.assertEquals(plan.value() > 0, friendly);
            Assert.assertEquals(plan.state().watchedCreature().power(), 3);
            Assert.assertEquals(plan.state().watchedCreature().toughness(), 3);
            Assert.assertEquals(plan.state().p1p1(watched), 1);
            Assert.assertEquals(plan.state().sourcePermanent(), host);
            Assert.assertFalse(backend.referenceDimensions(multiply).contains(IntrinsicDrawOutcomeBackend.SOURCE_P1P1));
            final var all = new AbilityOutcomeDescription("all", "RemoveCounter",
                    Map.of("Defined", "TriggeredCard", "CounterType", "P1P1", "CounterNum", "All"), List.of(), null, "");
            final var loss = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>(100).evaluate(compiler.compile(all), plan.state());
            Assert.assertTrue(loss.complete());
            Assert.assertEquals(loss.state().watchedCreature(), entrant);
            Assert.assertEquals(loss.state().p1p1(watched), 0);
            Assert.assertEquals(plan.value() + loss.value(), 0.0, 1e-9);
            final var excess = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>(100)
                    .evaluate(compiler.compile(remove), loss.state());
            Assert.assertTrue(excess.complete());
            Assert.assertEquals(excess.value(), 0.0);
        }
    }

    @Test
    public void watchedCounterUnknownInventoryAndUnsafeStateBasedActionsStayUnresolved() {
        final var entrant = new IntrinsicReferenceModel.PermanentProfile(true,
                IntrinsicReferenceModel.PermanentKind.CREATURE, false, 2, 2, Set.of());
        final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults(),
                IntrinsicReferenceModel.PermanentProfile.absent(), script -> java.util.Optional.empty(),
                IntrinsicLibraryReference.defaults(), entrant);
        final var compiler = new OutcomeDescriptionCompiler<>(backend);
        final var draw = new AbilityOutcomeDescription("followup", "Draw", Map.of("NumCards", "1"), List.of(), null, "");
        for (final String api : List.of("RemoveCounter", "MultiplyCounter")) {
            final var node = new AbilityOutcomeDescription("unknown", api,
                    Map.of("Defined", "TriggeredCard", "CounterType", "P1P1"), List.of(), draw, "");
            final var state = new IntrinsicDrawOutcomeBackend.State(3, 3).withWatchedCreature(entrant);
            Assert.assertFalse(new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>(100)
                    .evaluate(compiler.compile(node), state).complete());
            final var absent = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>(100)
                    .evaluate(compiler.compile(node), new IntrinsicDrawOutcomeBackend.State(3, 3));
            Assert.assertTrue(absent.complete());
            Assert.assertEquals(absent.state().controllerHand(), 4);
        }
        final var zeroBase = new IntrinsicReferenceModel.PermanentProfile(true,
                IntrinsicReferenceModel.PermanentKind.CREATURE, true, 1, 1, Set.of());
        final var remove = new AbilityOutcomeDescription("remove", "RemoveCounter",
                Map.of("Defined", "TriggeredCard", "CounterType", "P1P1"), List.of(), draw, "");
        final var unsafe = new IntrinsicDrawOutcomeBackend.State(3, 3).withWatchedCreature(zeroBase)
                .withP1p1(IntrinsicDrawOutcomeBackend.TargetRef.WATCHED_CREATURE, 1);
        Assert.assertFalse(new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>(100)
                .evaluate(compiler.compile(remove), unsafe).complete());
        Assert.assertTrue(IntrinsicWatchedCreatureBinding.unresolvedMutableCharacteristics(remove,
                Map.of("X", "TriggeredCard$CardToughness")));
    }

    @Test
    public void enteringDamageConditionsRecipientSizeAndController() {
        final var small = new IntrinsicReferenceModel.CreatureProfile(true, 2, 2, Set.of(), false, false);
        final var large = new IntrinsicReferenceModel.CreatureProfile(true, 3, 3, Set.of(), false, false);
        final var indestructible = new IntrinsicReferenceModel.CreatureProfile(true, 2, 2, Set.of(), false, true);
        final String damage = "DB$ DealDamage | Defined$ TriggeredCardLKICopy | NumDmg$ 2";
        final var opposing = evaluate(model(small), definition("Creature.OppCtrl", damage, "Number$1"));
        Assert.assertEquals(opposing.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(opposing.contribution().value() > 0);
        final var friendly = evaluate(model(small), definition("Creature.YouCtrl", damage, "Number$1"));
        Assert.assertEquals(friendly.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(friendly.contribution().value() < 0);
        for (final var survivor : List.of(large, indestructible)) {
            final var result = evaluate(model(survivor), definition("Creature.OppCtrl", damage, "Number$1"));
            Assert.assertTrue(result.contribution().complete());
            Assert.assertEquals(result.contribution().value(), 0.0);
        }
    }

    @Test
    public void unknownWatchedObjectsAndNewEventScopesAreNotReinterpretedAsHost() {
        final var vanilla = new IntrinsicReferenceModel.CreatureProfile(true, 3, 3, Set.of(), false, false);
        final var model = model(vanilla);
        final var generic = evaluate(model, definition("Creature", "DB$ Draw | NumCards$ X", "TriggeredCard$CardPower"));
        Assert.assertTrue(generic.contribution().complete());
        Assert.assertTrue(generic.contribution().value() > 0);
        final var unknown = evaluate(model, definition("Creature", "DB$ Draw | NumCards$ X", "TriggeredCard$UnknownCharacteristic"));
        Assert.assertFalse(unknown.contribution().complete());
        final var host = new IntrinsicReferenceModel.PermanentProfile(true,
                IntrinsicReferenceModel.PermanentKind.ENCHANTMENT, true, 0, 0, Set.of());
        final var ability = new CardAbilityTraversal.AbilityDescription("entry", CardAbilityTraversal.Origin.TRIGGER,
                CardAbilityTraversal.Provenance.PRINTED, Map.of("Mode", "ChangesZone", "Origin", "Any",
                        "Destination", "Battlefield", "ValidCard", "Creature.YouCtrl"), null);
        final var binding = IntrinsicWatchedCreatureBinding.cases(ability, model, host).orElseThrow().get(0).value();
        final var delayed = new AbilityOutcomeDescription("delayed", "DelayedTrigger",
                Map.of("Defined", "TriggeredCardController"), List.of(), null, "");
        Assert.assertSame(binding.bindOutcome(delayed), delayed);
    }
}
