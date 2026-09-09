package forge.ai.effect;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Semantic checks for bounded activated-ability opportunity estimates. */
public class ActivatedAbilityUseEvaluatorTest extends AITest {
    @Test
    public void additionalLandProbabilityUsesOnlyPublicHandSize() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        Assert.assertEquals(ActivatedAbilityUseEvaluator.estimateAdditionalLandProbability(ai), 0.0);

        addCardToZone("Forest", ai, ZoneType.Hand);
        Assert.assertEquals(ActivatedAbilityUseEvaluator.estimateAdditionalLandProbability(ai), 0.4);

        for (int i = 1; i < 7; i++) {
            addCardToZone("Forest", ai, ZoneType.Hand);
        }
        Assert.assertEquals(ActivatedAbilityUseEvaluator.estimateAdditionalLandProbability(ai),
                1 - Math.pow(0.6, 7));
    }

    @Test
    public void futureLandProbabilityRetainsAnOtherwiseUnaffordableActivation() {
        final Card source = setupSource(1, 1);
        final ActivationUseEstimate estimate = estimate(source, "2");

        Assert.assertTrue(estimate.supported(), estimate.reason());
        Assert.assertEquals(estimate.currentUses(), 0);
        Assert.assertEquals(estimate.nextTurnUses(), 1);
        Assert.assertEquals(estimate.nextLandProbability(), 0.4);
        Assert.assertEquals(estimate.expectedUses(), 0.3 * estimate.willingness(), 0.0001);
        Assert.assertTrue(estimate.outcomeSupported());
    }

    @Test
    public void repeatableManaActivationIsCappedAndNextTurnIsDiscounted() {
        final Card source = setupSource(8, 0);
        final ActivationUseEstimate estimate = estimate(source, "2");

        Assert.assertTrue(estimate.supported(), estimate.reason());
        Assert.assertEquals(estimate.currentUses(), 4);
        Assert.assertEquals(estimate.nextTurnUses(), 4);
        Assert.assertEquals(estimate.expectedUses(), 7.0 * estimate.willingness(), 0.0001);
    }

    @Test
    public void tapActivationGetsOneCurrentAndOneFutureOpportunity() {
        final Card source = setupSource(0, 0);
        final ActivationUseEstimate estimate = estimate(source, "T");

        Assert.assertTrue(estimate.supported(), estimate.reason());
        Assert.assertEquals(estimate.currentUses(), 1);
        Assert.assertEquals(estimate.nextTurnUses(), 1);
        Assert.assertEquals(estimate.expectedUses(), 1.75 * estimate.willingness(), 0.0001);
    }

    @Test
    public void strongOutcomeIsMoreLikelyThanWeakOutcomeAtSameCost() {
        final Card source = setupSource(2, 1);
        final ActivationUseEstimate weak = estimate(source, "2", "1");
        final ActivationUseEstimate strong = estimate(source, "2", "20");

        Assert.assertTrue(strong.outcomeSupported());
        Assert.assertTrue(strong.outcomeValue() > strong.averageCardPlayValue(),
                strong.toString());
        Assert.assertTrue(strong.willingness() > weak.willingness(),
                strong + " should be more attractive than " + weak);
    }

    @Test
    public void largerHandAddsCompetitionForAnOtherwiseEqualActivation() {
        final ActivationUseEstimate smallHand = estimate(setupSource(2, 1), "2", "20");
        final ActivationUseEstimate largeHand = estimate(setupSource(2, 7), "2", "20");

        Assert.assertTrue(smallHand.willingness() > largeHand.willingness(),
                smallHand + " should face less competition than " + largeHand);
    }

    @Test
    public void unsupportedNonManaCostsAreExplicit() {
        final Card source = setupSource(0, 0);
        final ActivationUseEstimate estimate = estimate(source, "Sac<1/Creature>");

        Assert.assertFalse(estimate.supported());
        Assert.assertEquals(estimate.expectedUses(), 0.0);
    }

    private Card setupSource(final int lands, final int handSize) {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        for (int i = 0; i < lands; i++) {
            addCard("Forest", ai);
        }
        for (int i = 0; i < handSize; i++) {
            addCardToZone("Forest", ai, ZoneType.Hand);
        }
        final Card source = addCard("Grizzly Bears", ai);
        source.setSickness(false);
        return source;
    }

    private ActivationUseEstimate estimate(final Card source, final String cost) {
        return estimate(source, cost, "1");
    }

    private ActivationUseEstimate estimate(final Card source, final String cost,
            final String lifeAmount) {
        final SpellAbility ability = AbilityFactory.getAbility(
                "AB$ GainLife | Cost$ " + cost + " | Defined$ You | LifeAmount$ " + lifeAmount, source);
        ability.setActivatingPlayer(source.getController());
        return ActivatedAbilityUseEvaluator.estimate(source, ability);
    }
}
