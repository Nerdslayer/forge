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
