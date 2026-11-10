package forge.ai;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Test;

public class EtbCounterProbeTest extends AITest {
    @Test
    public void counterProbeSkipsUnrelatedSagaChaptersButCastAssessmentStillChecksThem() {
        final var game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final AiController controller = Mockito.spy(new AiController(ai, game));
        Mockito.doReturn(true).when(controller).doTrigger(Mockito.any(), Mockito.eq(false));

        for (final String name : new String[] {"History of Benalia", "Elspeth Conquers Death"}) {
            final Card saga = addCardToZone(name, ai, ZoneType.Hand);
            final SpellAbility spell = saga.getFirstSpellAbility();
            spell.setActivatingPlayer(ai);
            final ApiType firstChapter = saga.getTriggers().stream().filter(Trigger::isChapter)
                    .findFirst().orElseThrow().ensureAbility().getApi();
            Mockito.clearInvocations(controller);
            Assert.assertFalse(controller.checkETBEffects(saga, spell, ApiType.Counter));
            Mockito.verify(controller, Mockito.never()).doTrigger(
                    Mockito.argThat(sa -> sa.getApi() == firstChapter), Mockito.eq(false));
            Assert.assertTrue(controller.checkETBEffects(saga, spell, null));
            Mockito.verify(controller).doTrigger(
                    Mockito.argThat(sa -> sa.getApi() == firstChapter), Mockito.eq(false));
            Assert.assertNull(saga.getCastSA());
        }
    }
}
