package forge.ai.effect;

import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.ability.SpellApiBased;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Regression coverage for the shared action valuation boundary. */
public class UnifiedActionValueEvaluatorTest extends AITest {
    @Test
    public void removalActionComposesTheExistingPermanentAndTransitionEvaluators() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        final Card target = addCard("Grizzly Bears", opponent);
        final ValuationContext context = ValuationContext.forRemoval(ai, 0, 0);

        final CardValueBreakdown expected = RemovalActionEvaluator.evaluate(ai, target,
                UnifiedCardValueEvaluator.evaluatePermanent(target, context),
                RemovalActionKind.BOUNCE);
        final CardValueBreakdown actual = UnifiedActionValueEvaluator.evaluate(
                new RemovalValuationAction(target, RemovalActionKind.BOUNCE), context);

        Assert.assertEquals(actual, expected);
    }

    @Test
    public void nullTraceUsesTheSameDisabledDiagnosticsBehavior() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card target = addCard("Grizzly Bears", game.getPlayers().get(0));
        final ValuationContext context = ValuationContext.forRemoval(ai, 0, 0);

        final CardValueBreakdown result = UnifiedCardValueEvaluator.evaluatePermanent(target,
                context, null);

        Assert.assertEquals(result, UnifiedCardValueEvaluator.evaluatePermanent(target, context));
    }

    @Test
    public void castActionValuesACompleteImmediateOutcomeAndChargesResources() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card source = addCardToZone("Divination", ai, ZoneType.Hand);
        addCardToZone("Forest", ai, ZoneType.Library);
        final SpellAbility draw = new SpellApiBased(ApiType.Draw, source, new Cost("1", false),
                null, Map.of("Defined", "You", "NumCards", "1"));
        draw.setActivatingPlayer(ai);

        final CardValueBreakdown result = UnifiedActionValueEvaluator.evaluate(
                new CastValuationAction(draw), ValuationContext.forCast(ai, true));

        Assert.assertTrue(result.isComplete(), result.toString());
        Assert.assertTrue(result.transitionValue() > 0, result.toString());
        Assert.assertEquals(result.accessCost(), 25 + 90);
        Assert.assertTrue(result.netValue() < result.transitionValue());
    }

    @Test
    public void removalActionRejectsAContextForAnotherDecision() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card target = addCard("Grizzly Bears", game.getPlayers().get(0));

        final CardValueBreakdown result = UnifiedActionValueEvaluator.evaluate(
                new RemovalValuationAction(target, RemovalActionKind.EXILE),
                ValuationContext.forCast(ai, true));

        Assert.assertEquals(result.completeness(), ValuationCompleteness.UNSUPPORTED);
    }
}
