package forge.ai.effect;

import java.util.List;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.CardDefinitionValueEvaluator;
import forge.ai.ComputerUtilCard;
import forge.ai.CreatureBodyValue;
import forge.ai.CreatureEvaluator;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

/** Keeps live, printed, and theoretical creature values on one body scale. */
public class CreatureValueCalibrationTest extends AITest {
    @Test
    public void sharedBodyScaleMatchesTheAgreedSizeAnchors() {
        Assert.assertEquals(CreatureBodyValue.body(1, 1), 55);
        Assert.assertEquals(CreatureBodyValue.body(0, 1), 41);
        Assert.assertEquals(CreatureBodyValue.body(2, 2), 96);
        Assert.assertEquals(CreatureBodyValue.body(3, 3), 142);
        Assert.assertEquals(CreatureBodyValue.body(4, 4), 178);
        Assert.assertEquals(CreatureBodyValue.body(5, 5), 201);
        Assert.assertEquals(CreatureBodyValue.body(6, 6), 219);
        Assert.assertEquals(CreatureBodyValue.body(7, 7), 235);
        Assert.assertEquals(CreatureBodyValue.body(8, 8), 250);
        Assert.assertEquals(CreatureBodyValue.body(0, 0), 0);
    }

    @Test
    public void attackAccessHasAFivePointFloorOnlyWhenPowerAddsNoPressure() {
        final int zeroPowerDefender = CreatureBodyValue.body(
                0, 3, false, true, false, false, false, false);
        final int zeroPowerAttacker = CreatureBodyValue.body(
                0, 3, true, true, false, false, false, false);
        Assert.assertEquals(zeroPowerAttacker - zeroPowerDefender, 5);

        final int onePowerDefender = CreatureBodyValue.body(
                1, 3, false, true, false, false, false, false);
        final int onePowerAttacker = CreatureBodyValue.body(
                1, 3, true, true, false, false, false, false);
        Assert.assertEquals(onePowerAttacker - onePowerDefender, 10);
    }

    @Test
    public void survivingZeroPowerCreatureKeepsMostOfOneOneBodyValue() {
        final CardRules zeroOne = rules("Test Zero One", 0, 1);
        final CardRules oneOne = rules("Test One One", 1, 1);
        final CardDefinitionValueEvaluator definitions = new CardDefinitionValueEvaluator();
        Assert.assertEquals(definitions.evaluate(zeroOne).battlefieldValue(), 41);
        Assert.assertEquals(definitions.evaluate(oneOne).battlefieldValue(), 55);

        final IntrinsicOutcomeEvaluator reference = new IntrinsicOutcomeEvaluator();
        Assert.assertEquals(reference.evaluateCreature(new IntrinsicReferenceModel.CreatureProfile(
                true, 0, 1, Set.of(), false, false)), 41);

        final Game game = initAndCreateGame();
        final Player owner = game.getPlayers().get(0);
        final CreatureEvaluator unified = new CreatureEvaluator(CreatureEvaluator.ValuationScale.UNIFIED);
        Assert.assertEquals(unified.evaluateCreature(addDefinition(zeroOne, owner)), 42);
        Assert.assertEquals(unified.evaluateCreature(addDefinition(oneOne, owner)), 56);
    }

    @Test
    public void indestructibleOneOneOutvaluesVanillaTwoTwoAcrossRepresentations() {
        final CardRules indestructible = rules("Test Indestructible", 1, 1, "K:Indestructible");
        final CardRules vanilla = rules("Test Vanilla", 2, 2);
        final CardDefinitionValueEvaluator definitions = new CardDefinitionValueEvaluator();
        Assert.assertEquals(definitions.evaluate(indestructible).battlefieldValue(), 125);
        Assert.assertEquals(definitions.evaluate(vanilla).battlefieldValue(), 96);

        final IntrinsicOutcomeEvaluator reference = new IntrinsicOutcomeEvaluator();
        Assert.assertEquals(reference.evaluateCreature(new IntrinsicReferenceModel.CreatureProfile(
                true, 1, 1, Set.of(), false, true)), 125);
        Assert.assertEquals(reference.evaluateCreature(new IntrinsicReferenceModel.CreatureProfile(
                true, 2, 2, Set.of(), false, false)), 96);

        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        final Card protectedOneOne = addDefinition(indestructible, opponent);
        final Card plainTwoTwo = addDefinition(vanilla, opponent);
        final CreatureEvaluator unified = new CreatureEvaluator(CreatureEvaluator.ValuationScale.UNIFIED);
        Assert.assertEquals(unified.evaluateCreature(protectedOneOne), 126);
        Assert.assertEquals(unified.evaluateCreature(plainTwoTwo), 97);
        Assert.assertEquals(UnifiedCardValueEvaluator.evaluatePermanent(protectedOneOne,
                ValuationContext.forRemoval(ai, 0, 0)).currentPresenceValue(), 126);
        Assert.assertEquals(UnifiedCardValueEvaluator.evaluatePermanent(plainTwoTwo,
                ValuationContext.forRemoval(ai, 0, 0)).contextAdjustment(), 0);
    }

    @Test
    public void legacyCreatureScoreRemainsSeparateFromUnifiedScore() {
        final Game game = initAndCreateGame();
        final Player opponent = game.getPlayers().get(0);
        final Card oneOne = addDefinition(rules("Test One One", 1, 1), opponent);

        Assert.assertEquals(UnifiedPermanentValueEvaluator.evaluate(opponent, oneOne), 56);
        oneOne.setTapped(true);
        Assert.assertEquals(UnifiedPermanentValueEvaluator.evaluate(opponent, oneOne), 55);
        Assert.assertTrue(ComputerUtilCard.evaluateCreature(oneOne) > 55);
        Assert.assertEquals(ComputerUtilCard.evaluateCreature(oneOne),
                new CreatureEvaluator().evaluateCreature(oneOne));
    }

    @Test
    public void unifiedRemovalDoesNotAddTokenOrWholeBoardPriority() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        final Card token = addToken("w_1_1_soldier", opponent);
        final Card printed = addDefinition(rules("Test Printed One One", 1, 1), opponent);

        final CardValueBreakdown tokenValue = UnifiedCardValueEvaluator.evaluatePermanent(token,
                ValuationContext.forRemoval(ai, 0, 0));
        final CardValueBreakdown printedValue = UnifiedCardValueEvaluator.evaluatePermanent(printed,
                ValuationContext.forRemoval(ai, 0, 0));
        Assert.assertEquals(tokenValue.currentPresenceValue(), 56);
        Assert.assertEquals(printedValue.currentPresenceValue(), 56);
        Assert.assertEquals(tokenValue.contextAdjustment(), 0);
        Assert.assertEquals(printedValue.contextAdjustment(), 0);
    }

    @Test
    public void unsupportedTriggerGetsOneManaValueFallbackButVanillaDoesNot() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        final Card token = addToken("w_1_1_soldier", opponent);
        final Card vanilla = addDefinition(CardRules.fromScript(List.of(
                "Name:Test Vanilla Human", "ManaCost:1 W", "Types:Creature Human Soldier", "PT:1/1")),
                opponent);
        final Card engine = addDefinition(CardRules.fromScript(List.of(
                "Name:Test Human Engine", "ManaCost:1 W", "Types:Creature Human Soldier", "PT:1/1",
                "T:Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield | "
                        + "ValidCard$ Human.Other+YouCtrl | TriggerZones$ Battlefield | Execute$ TrigCounter",
                "SVar:TrigCounter:DB$ PutCounter | CounterType$ P1P1 | CounterNum$ 1",
                "T:Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield | "
                        + "ValidCard$ Human.Other+YouCtrl | TriggerZones$ Battlefield | Execute$ TrigCounterTwo",
                "SVar:TrigCounterTwo:DB$ PutCounter | CounterType$ P1P1 | CounterNum$ 1")), opponent);
        final ValuationContext context = ValuationContext.forRemoval(ai, 100, 100);

        Assert.assertEquals(UnifiedCardValueEvaluator.evaluatePermanent(token, context)
                .currentPresenceValue(), 56);
        Assert.assertEquals(UnifiedCardValueEvaluator.evaluatePermanent(vanilla, context)
                .currentPresenceValue(), 56);
        final CardValueBreakdown engineValue = UnifiedCardValueEvaluator.evaluatePermanent(engine, context);
        Assert.assertEquals(engineValue.currentPresenceValue(), 66);
        Assert.assertTrue(engineValue.reasons().stream()
                .anyMatch(reason -> reason.contains("Unevaluated ability fallback")), engineValue.toString());
    }

    private static CardRules rules(final String name, final int power, final int toughness,
            final String... keywords) {
        final List<String> lines = new java.util.ArrayList<>(List.of(
                "Name:" + name, "ManaCost:1", "Types:Creature", "PT:" + power + "/" + toughness));
        lines.addAll(List.of(keywords));
        return CardRules.fromScript(lines);
    }

    private static Card addDefinition(final CardRules rules, final Player owner) {
        final Card card = Card.fromPaperCard(
                new PaperCard(rules, CardEdition.UNKNOWN_CODE, CardRarity.Special), owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());
        owner.getZone(ZoneType.Battlefield).add(card);
        return card;
    }
}
