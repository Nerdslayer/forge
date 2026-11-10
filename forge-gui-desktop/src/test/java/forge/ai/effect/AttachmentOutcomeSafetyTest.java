package forge.ai.effect;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import forge.ai.AITest;
import forge.ai.simulation.FlatMonteCarloPicker;
import forge.game.Game;
import forge.game.GameAction;
import forge.game.ability.AbilityFactory;
import forge.game.phase.PhaseType;
import forge.util.AITimeoutTracker;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Test;

public class AttachmentOutcomeSafetyTest extends AITest {
    @Test
    public void failedLiveRestorationIsNotAnUnsupportedOutcome() {
        verifyAbort(false, false);
    }

    @Test
    public void tieBreakDoesNotPreviewAnotherTargetAfterRestoreUsesRemainingTime() {
        verifyAbort(true, false);
    }

    @Test
    public void monteCarloPropagatesAFailedRestoreFromLiveRevalidation() {
        verifyAbort(false, true);
    }

    private void verifyAbort(final boolean expire, final boolean monteCarlo) {
        final Game game = initAndCreateGame();
        final var ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        addCards("Island", 2, ai);
        addCard("Grizzly Bears", ai);
        addCard("Runeclaw Bear", ai);
        final var equipment = Mockito.spy(addCard("Bonesplitter", ai));
        final var probeGame = Mockito.spy(game);
        final var action = Mockito.mock(GameAction.class);
        Mockito.doReturn(action).when(probeGame).getAction();
        Mockito.doReturn(probeGame).when(equipment).getGame();
        final var failure = new IllegalStateException("test restore failure");
        if (expire) {
            Mockito.doAnswer(invocation -> {
                AITimeoutTracker.arm(System.nanoTime() - 1, false);
                return null;
            }).when(action).checkStaticAbilities(false);
        } else {
            Mockito.doThrow(failure).when(action).checkStaticAbilities(false);
        }
        final var attach = AbilityFactory.getAbility("AB$ Attach | Cost$ 1 | ValidTgts$ Creature.YouCtrl", equipment);
        attach.setHostCard(equipment);
        attach.setActivatingPlayer(ai);

        final var probes = new AtomicInteger();
        final Runnable rank = () -> ActionValueTieBreaker.rankSupportedTie(List.of(1, 2),
                ValuationContext.forActivation(ai, true), candidate -> true, candidate -> {
                    probes.incrementAndGet();
                    return new ActivateValuationAction(equipment, attach);
                });
        final Runnable evaluate = monteCarlo ? () -> {
            final var config = new FlatMonteCarloPicker.Config();
            config.targetVariants = 1;
            FlatMonteCarloPicker.choose(ai, List.of(attach), config, candidate -> {
                rank.run();
                return true;
            });
        } : rank;
        final Object limit = AITimeoutTracker.arm(System.nanoTime() + 30_000_000_000L, false);
        try {
            if (expire) {
                Assert.expectThrows(AITimeoutTracker.Expired.class, evaluate::run);
            } else {
                final var abort = Assert.expectThrows(LiveGameRestorationException.class, evaluate::run);
                Assert.assertSame(abort.getCause(), failure);
            }
        } finally {
            AITimeoutTracker.disarm(limit);
        }
        Assert.assertEquals(probes.get(), 1);
        Mockito.verify(action).checkStaticAbilities(Mockito.eq(false), Mockito.anySet(), Mockito.any());
        Mockito.verify(action).checkStaticAbilities(false);
    }
}
