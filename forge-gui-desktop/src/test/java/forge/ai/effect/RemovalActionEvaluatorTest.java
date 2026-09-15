package forge.ai.effect;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.PlayerResourceValueEvaluator;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/** Regression coverage for the bounded action distinction used by removal selection. */
public class RemovalActionEvaluatorTest extends AITest {
    @Test
    public void destroyAndExileRetainPermanentRemovalValue() {
        final Game game = initAndCreateGame();
        final Player opponent = game.getPlayers().get(0);
        final Card target = addCard("Grizzly Bears", opponent);
        final CardValueBreakdown base = baseValue();

        Assert.assertSame(RemovalActionEvaluator.evaluate(target, base, RemovalActionKind.DESTROY), base);
        Assert.assertSame(RemovalActionEvaluator.evaluate(target, base, RemovalActionKind.EXILE), base);
        Assert.assertSame(RemovalActionEvaluator.evaluate(target, base,
                RemovalActionKind.PERMANENT_REMOVAL), base);
    }

    @Test
    public void bounceUsesKnownCardValueWhenDefinitionIsSupported() {
        final Game game = initAndCreateGame();
        final Player opponent = game.getPlayers().get(0);
        final Card target = addCard("Grizzly Bears", opponent);

        final CardValueBreakdown emptyHand = RemovalActionEvaluator.evaluate(target, baseValue(),
                RemovalActionKind.BOUNCE);
        Assert.assertEquals(emptyHand.transitionValue(), -80);

        addCardToZone("Forest", opponent, forge.game.zone.ZoneType.Hand);
        addCardToZone("Forest", opponent, forge.game.zone.ZoneType.Hand);
        final CardValueBreakdown largerHand = RemovalActionEvaluator.evaluate(target, baseValue(),
                RemovalActionKind.BOUNCE);
        Assert.assertEquals(largerHand.transitionValue(), emptyHand.transitionValue());
    }

    @Test
    public void bounceUsesPublicHandSizeForUnsupportedKnownCard() {
        final Game game = initAndCreateGame();
        final Player opponent = game.getPlayers().get(0);
        final Card target = addCard("Sol Ring", opponent);

        final CardValueBreakdown emptyHand = RemovalActionEvaluator.evaluate(target, baseValue(),
                RemovalActionKind.BOUNCE);
        Assert.assertEquals(emptyHand.transitionValue(), -PlayerResourceValueEvaluator.evaluateNextCard(0));

        addCardToZone("Forest", opponent, forge.game.zone.ZoneType.Hand);
        addCardToZone("Forest", opponent, forge.game.zone.ZoneType.Hand);
        final CardValueBreakdown largerHand = RemovalActionEvaluator.evaluate(target, baseValue(),
                RemovalActionKind.BOUNCE);
        Assert.assertEquals(largerHand.transitionValue(), -PlayerResourceValueEvaluator.evaluateNextCard(2));
        Assert.assertTrue(largerHand.transitionValue() > emptyHand.transitionValue());
        Assert.assertEquals(emptyHand.completeness(), ValuationCompleteness.PARTIAL);
    }

    @Test
    public void bouncingTokenDoesNotCreateAHandCard() {
        final Game game = initAndCreateGame();
        final Player opponent = game.getPlayers().get(0);
        final Card token = addToken("c_a_treasure_sac", opponent);

        final CardValueBreakdown result = RemovalActionEvaluator.evaluate(token, baseValue(),
                RemovalActionKind.BOUNCE);

        Assert.assertEquals(result.transitionValue(), 0);
    }

    @Test
    public void actionKindRecognizesDestroyExileAndBounce() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card source = addCard("Sol Ring", ai);
        final SpellAbility destroy = AbilityFactory.getAbility(
                "AB$ Destroy | Cost$ 0 | ValidTgts$ Creature.OppCtrl", source);
        final SpellAbility exile = AbilityFactory.getAbility(
                "AB$ ChangeZone | Cost$ 0 | ValidTgts$ Creature.OppCtrl | Origin$ Battlefield"
                        + " | Destination$ Exile", source);
        final SpellAbility bounce = AbilityFactory.getAbility(
                "AB$ ChangeZone | Cost$ 0 | ValidTgts$ Creature.OppCtrl | Origin$ Battlefield"
                        + " | Destination$ Hand", source);

        Assert.assertEquals(RemovalActionKind.from(destroy), RemovalActionKind.DESTROY);
        Assert.assertEquals(RemovalActionKind.from(exile), RemovalActionKind.EXILE);
        Assert.assertEquals(RemovalActionKind.from(bounce), RemovalActionKind.BOUNCE);
        Assert.assertEquals(destroy.getApi(), ApiType.Destroy);
    }

    private static CardValueBreakdown baseValue() {
        return new CardValueBreakdown(200, 40, 0, 0, 0,
                ValuationCompleteness.COMPLETE, List.of());
    }
}
