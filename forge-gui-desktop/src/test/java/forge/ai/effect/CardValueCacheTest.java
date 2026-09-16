package forge.ai.effect;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** Regression coverage for per-decision card-value reuse. */
public class CardValueCacheTest extends AITest {
    @Test
    public void cacheUsesCardIdentityWithinOneContext() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card card = addCardToZone("Grizzly Bears", ai, ZoneType.Hand);
        final CardValueCache cache = new CardValueCache(ValuationContext.forHandSelection(ai, true));

        final CardValueBreakdown first = cache.evaluate(card);
        final CardValueBreakdown second = cache.evaluate(card);

        Assert.assertSame(second, first);
        Assert.assertEquals(cache.size(), 1);
    }
}
