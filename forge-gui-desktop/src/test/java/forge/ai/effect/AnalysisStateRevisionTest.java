package forge.ai.effect;

import forge.ai.AITest;
import forge.card.CardStateName;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CounterEnumType;
import forge.game.card.CardCollection;
import forge.game.combat.Combat;
import forge.game.event.GameEventCardStatsChanged;
import forge.game.zone.CostPaymentStack;
import forge.game.StaticEffects;
import forge.game.ability.AbilityFactory;
import forge.game.staticability.StaticAbilityLayer;

import com.google.common.collect.HashMultiset;

import org.testng.Assert;
import org.testng.annotations.Test;

/** Model revision behavior, including silent mutations and projection isolation. */
public class AnalysisStateRevisionTest extends AITest {
    @Test
    public void trackingIsOptInAndSeparateBetweenGames() {
        final var game = initAndCreateGame();
        final Card card = addCard("Grizzly Bears", game.getPlayers().get(1));
        card.setTapped(true);
        game.fireEvent(new GameEventCardStatsChanged(card));
        Assert.assertEquals(game.getAnalysisStateRevision(), 0L);
        game.enableAnalysisStateTracking();
        final long enabled = game.getAnalysisStateRevision();
        Assert.assertTrue(enabled > 0);
        game.enableAnalysisStateTracking();
        Assert.assertEquals(game.getAnalysisStateRevision(), enabled);
        card.setTapped(false);
        Assert.assertTrue(game.getAnalysisStateRevision() > enabled);
        Assert.assertEquals(initAndCreateGame().getAnalysisStateRevision(), 0L);
    }

    @Test
    public void silentMutationsAdvanceEvenWithFrozenViews() {
        final var game = initAndCreateGame();
        final var ai = game.getPlayers().get(1);
        final Card card = addCard("Grizzly Bears", ai);
        game.enableAnalysisStateTracking();
        game.getTracker().freeze();
        try {
            for (final Runnable change : java.util.List.<Runnable>of(
                    () -> card.setTapped(true),
                    () -> card.setController(game.getPlayers().get(0), game.getNextTimestamp()),
                    () -> card.addTempController(ai, game.getNextTimestamp()),
                    () -> card.clearTempControllers(),
                    () -> card.setPhasedOut(ai),
                    () -> card.setPhasedOut(null),
                    () -> card.setState(CardStateName.FaceDown, false),
                    () -> card.setState(CardStateName.Original, false),
                    () -> game.getPhaseHandler().setPlayerTurn(game.getPlayers().get(0)))) {
                final long before = game.getAnalysisStateRevision();
                change.run();
                Assert.assertTrue(game.getAnalysisStateRevision() > before);
            }
        } finally {
            game.getTracker().unfreeze();
        }
    }

    @Test
    public void projectedCopiesDoNotInvalidateLiveState() {
        final var game = initAndCreateGame();
        final Card card = addCard("Grizzly Bears", game.getPlayers().get(1));
        game.enableAnalysisStateTracking();
        final long before = game.getAnalysisStateRevision();
        final Card copy = CardCopyService.getLKICopy(card);
        copy.setTapped(true);
        copy.setPhasedOut(game.getPlayers().get(1));
        copy.setController(game.getPlayers().get(0), game.getNextTimestamp());
        copy.setState(CardStateName.FaceDown, true);
        copy.setDamage(1);
        game.fireEvent(new GameEventCardStatsChanged(copy));
        Assert.assertEquals(game.getAnalysisStateRevision(), before);
        Assert.assertFalse(card.isTapped());
        Assert.assertEquals(card.getDamage(), 0);
        game.fireEvent(new GameEventCardStatsChanged(card));
        Assert.assertTrue(game.getAnalysisStateRevision() > before);
    }

    @Test
    public void countersAttachmentsAndDeathtouchTrackLiveEntitiesOnly() {
        final var game = initAndCreateGame();
        final var ai = game.getPlayers().get(1);
        final Card card = addCard("Grizzly Bears", ai);
        final Card attachment = addCard("Short Sword", ai);
        game.enableAnalysisStateTracking();
        for (final Runnable change : java.util.List.<Runnable>of(
                () -> card.setCounters(CounterEnumType.P1P1, 2),
                () -> card.setCounters(HashMultiset.create(java.util.List.of(CounterEnumType.M1M1))),
                card::clearCounters,
                () -> ai.setCounters(CounterEnumType.ENERGY, 1),
                () -> attachment.setEntityAttachedTo(card),
                () -> card.addAttachedCard(attachment),
                () -> card.removeAttachedCard(attachment),
                () -> card.setAttachedCards(java.util.List.of(attachment)),
                card::clearAttachedCards,
                () -> card.addAttachedCard(attachment),
                card::clearAttachedCards,
                () -> attachment.setEntityAttachedTo(null),
                () -> card.setHasBeenDealtDeathtouchDamage(true))) {
            final long before = game.getAnalysisStateRevision();
            change.run();
            Assert.assertTrue(game.getAnalysisStateRevision() > before);
        }
        final long before = game.getAnalysisStateRevision();
        final Card copy = CardCopyService.getLKICopy(card);
        copy.setCounters(CounterEnumType.P1P1, 3);
        copy.setCounters(HashMultiset.create(java.util.List.of(CounterEnumType.P1P1)));
        copy.clearCounters();
        copy.setEntityAttachedTo(attachment);
        copy.setAttachedCards(java.util.List.of(attachment));
        copy.clearAttachedCards();
        copy.addAttachedCard(attachment);
        copy.removeAttachedCard(attachment);
        copy.clearAttachedCards();
        copy.clearAttachedCards();
        copy.setHasBeenDealtDeathtouchDamage(false);
        Assert.assertEquals(game.getAnalysisStateRevision(), before);
        Assert.assertFalse(card.hasCounters());
        Assert.assertTrue(card.hasBeenDealtDeathtouchDamage());
        Assert.assertFalse(card.hasCardAttachments());
    }

    @Test
    public void onlyCurrentCombatMutationsInvalidate() {
        final var game = initAndCreateGame();
        final var ai = game.getPlayers().get(1);
        final var opponent = game.getPlayers().get(0);
        final Card attacker = addCard("Grizzly Bears", ai);
        final Card blocker = addCard("Grizzly Bears", opponent);
        game.enableAnalysisStateTracking();
        long before = game.getAnalysisStateRevision();
        final Combat prediction = new Combat(ai);
        prediction.addAttackerForValidation(attacker, opponent);
        prediction.addBlockerForValidation(attacker, blocker);
        prediction.setBlocked(attacker, true);
        Assert.assertEquals(game.getAnalysisStateRevision(), before);
        final Combat combat = new Combat(ai);
        game.getPhaseHandler().setCombat(combat);
        Assert.assertTrue(game.getAnalysisStateRevision() > before);
        for (final Runnable change : java.util.List.<Runnable>of(
                () -> combat.addAttacker(attacker, opponent),
                () -> combat.addBlocker(attacker, blocker),
                () -> combat.setBlocked(attacker, true),
                () -> combat.orderBlockersForDamageAssignment(attacker, new CardCollection(blocker)),
                () -> combat.removeBlockAssignment(attacker, blocker),
                () -> combat.addBlocker(attacker, blocker),
                () -> combat.undoBlockingAssignment(blocker),
                () -> combat.removeFromCombat(attacker),
                () -> game.getPhaseHandler().endCombat())) {
            before = game.getAnalysisStateRevision();
            change.run();
            Assert.assertTrue(game.getAnalysisStateRevision() > before);
        }
        Assert.assertNull(game.getCombat());
    }

    @Test
    public void trackedStaticEffectsInvalidateWhileLookupsAndStandaloneEffectsDoNot() {
        final var game = initAndCreateGame();
        final var ai = game.getPlayers().get(1);
        final var opponent = game.getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        final Card source = addCard("Grizzly Bears", opponent);
        final Card recipient = addCard("Grizzly Bears", opponent);
        final var ability = source.addStaticAbility("Mode$ Continuous | Affected$ Creature.YouCtrl | AddPower$ 1");
        game.enableAnalysisStateTracking();
        long before = game.getAnalysisStateRevision();
        Assert.assertNull(game.getStaticEffects().findStaticEffect(ability));
        Assert.assertTrue(StaticAbilityAnalyzer.evaluateContributions(ai, java.util.List.of(source),
                EffectAnalysisTrace.disabled()).isEmpty());
        Assert.assertNull(game.getStaticEffects().findStaticEffect(ability));
        Assert.assertEquals(game.getAnalysisStateRevision(), before);
        final var effect = game.getStaticEffects().getStaticEffect(ability);
        Assert.assertTrue(game.getAnalysisStateRevision() > before);
        before = game.getAnalysisStateRevision();
        Assert.assertSame(game.getStaticEffects().getStaticEffect(ability), effect);
        Assert.assertEquals(game.getAnalysisStateRevision(), before);
        for (final Runnable change : java.util.List.<Runnable>of(
                () -> effect.setTimestamp(game.getNextTimestamp()),
                () -> effect.setParams(java.util.Map.of()),
                () -> effect.setAffectedCards(new CardCollection(recipient)),
                () -> effect.setAffectedPlayers(java.util.List.of()),
                () -> game.getStaticEffects().removeStaticEffect(ability, StaticAbilityLayer.RULES, true))) {
            before = game.getAnalysisStateRevision();
            change.run();
            Assert.assertTrue(game.getAnalysisStateRevision() > before);
        }
        before = game.getAnalysisStateRevision();
        final var standalone = new StaticEffects().getStaticEffect(ability);
        standalone.setTimestamp(7);
        standalone.setParams(java.util.Map.of());
        standalone.setAffectedCards(new CardCollection(recipient));
        Assert.assertEquals(game.getAnalysisStateRevision(), before);
        game.getStaticEffects().getStaticEffect(ability);
        before = game.getAnalysisStateRevision();
        game.getStaticEffects().clearStaticEffects(new java.util.HashSet<>(), new java.util.HashMap<>());
        Assert.assertTrue(game.getAnalysisStateRevision() > before);
        before = game.getAnalysisStateRevision();
        game.getAction().checkStaticAbilities(false);
        Assert.assertTrue(game.getAnalysisStateRevision() > before);
        Assert.assertEquals(recipient.getNetPower(), 3);
    }

    @Test
    public void activationResolutionAndModeHistoryTrackLiveCardOwnership() {
        final var game = initAndCreateGame();
        final var ai = game.getPlayers().get(1);
        final Card card = addCard("Grizzly Bears", ai);
        final var ability = AbilityFactory.getAbility("AB$ Draw | Cost$ T | NumCards$ 1", card);
        ability.setActivatingPlayer(ai);
        card.addSpellAbility(ability);
        game.enableAnalysisStateTracking();
        for (final Runnable change : java.util.List.<Runnable>of(
                () -> card.addAbilityActivated(ability),
                card::resetActivationsPerTurn,
                () -> card.addAbilityResolved(ability),
                card::resetAbilityResolvedThisTurn,
                () -> card.getAbilityActivatedThisTurn().put(ability, java.util.Optional.empty(), HashMultiset.create(java.util.List.of(ai))),
                () -> card.getAbilityActivatedThisTurn().remove(ability, java.util.Optional.empty()),
                () -> card.addChosenModes(ability, "Draw", false),
                card::resetChosenModeTurn,
                card::addPlaneswalkerAbilityActivated,
                card::resetActivationsPerTurn)) {
            final long before = game.getAnalysisStateRevision();
            change.run();
            Assert.assertTrue(game.getAnalysisStateRevision() > before);
        }
        final long before = game.getAnalysisStateRevision();
        final Card copy = CardCopyService.getLKICopy(card);
        copy.addAbilityActivated(ability);
        copy.resetActivationsPerTurn();
        copy.addAbilityResolved(ability);
        copy.resetAbilityResolvedThisTurn();
        copy.addChosenModes(ability, "Draw", false);
        copy.resetChosenModeTurn();
        copy.addPlaneswalkerAbilityActivated();
        copy.resetActivationsPerTurn();
        Assert.assertEquals(game.getAnalysisStateRevision(), before);
    }

    @Test
    public void paymentBoundariesInvalidateWithoutChangingStandaloneStackBehavior() {
        final var game = initAndCreateGame();
        game.enableAnalysisStateTracking();
        long before = game.getAnalysisStateRevision();
        game.costPaymentStack.push(null, null);
        Assert.assertTrue(game.getAnalysisStateRevision() > before);
        before = game.getAnalysisStateRevision();
        game.costPaymentStack.pop();
        Assert.assertTrue(game.getAnalysisStateRevision() > before);
        final CostPaymentStack standalone = new CostPaymentStack();
        standalone.push(null, null);
        Assert.assertNotNull(standalone.pop());
    }
}
