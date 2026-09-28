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
        Assert.assertEquals(CreatureBodyValue.body(2, 2), 110);
        Assert.assertEquals(CreatureBodyValue.body(3, 3), 145);
        Assert.assertEquals(CreatureBodyValue.body(4, 4), 180);
        Assert.assertEquals(CreatureBodyValue.body(5, 5), 205);
        Assert.assertEquals(CreatureBodyValue.body(6, 6), 230);
        Assert.assertEquals(CreatureBodyValue.body(0, 0), 0);
    }

    @Test
    public void indestructibleOneOneOutvaluesVanillaTwoTwoAcrossRepresentations() {
        final CardRules indestructible = rules("Test Indestructible", 1, 1, "K:Indestructible");
        final CardRules vanilla = rules("Test Vanilla", 2, 2);
        final CardDefinitionValueEvaluator definitions = new CardDefinitionValueEvaluator();
        Assert.assertEquals(definitions.evaluate(indestructible).battlefieldValue(), 125);
        Assert.assertEquals(definitions.evaluate(vanilla).battlefieldValue(), 110);

        final IntrinsicOutcomeEvaluator reference = new IntrinsicOutcomeEvaluator();
        Assert.assertEquals(reference.evaluateCreature(new IntrinsicReferenceModel.CreatureProfile(
                true, 1, 1, Set.of(), false, true)), 125);
        Assert.assertEquals(reference.evaluateCreature(new IntrinsicReferenceModel.CreatureProfile(
                true, 2, 2, Set.of(), false, false)), 110);

        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        final Card protectedOneOne = addDefinition(indestructible, opponent);
        final Card plainTwoTwo = addDefinition(vanilla, opponent);
        final CreatureEvaluator unified = new CreatureEvaluator(CreatureEvaluator.ValuationScale.UNIFIED);
        Assert.assertEquals(unified.evaluateCreature(protectedOneOne), 125);
        Assert.assertEquals(unified.evaluateCreature(plainTwoTwo), 110);
        Assert.assertEquals(UnifiedCardValueEvaluator.evaluatePermanent(protectedOneOne,
                ValuationContext.forRemoval(ai, 0, 0)).currentPresenceValue(), 125);
        Assert.assertEquals(UnifiedCardValueEvaluator.evaluatePermanent(plainTwoTwo,
                ValuationContext.forRemoval(ai, 0, 0)).contextAdjustment(), 0);
    }

    @Test
    public void legacyCreatureScoreRemainsSeparateFromUnifiedScore() {
        final Game game = initAndCreateGame();
        final Player opponent = game.getPlayers().get(0);
        final Card oneOne = addDefinition(rules("Test One One", 1, 1), opponent);

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
        Assert.assertEquals(tokenValue.currentPresenceValue(), 55);
        Assert.assertEquals(printedValue.currentPresenceValue(), 55);
        Assert.assertEquals(tokenValue.contextAdjustment(), 0);
        Assert.assertEquals(printedValue.contextAdjustment(), 0);
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
