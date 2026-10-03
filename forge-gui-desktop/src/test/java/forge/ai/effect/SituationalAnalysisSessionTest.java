package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import forge.ai.AITest;
import forge.ai.AiCardMemory;
import forge.ai.ComputerUtilCard;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.AbilitySub;
import forge.game.trigger.TriggerHandler;

import org.testng.Assert;
import org.testng.annotations.Test;

public class SituationalAnalysisSessionTest extends AITest {
    private Player mastermind() {
        final Player ai = initAndCreateGame().getPlayers().get(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Mastermind");
        return ai;
    }

    @Test
    public void reusesCompatibleBaselinesAndPreservesActionDependentValues() {
        final Player ai = mastermind();
        final Card source = addCard("Quilled Sliver", ai.getGame().getPlayers().get(0));
        final Card recipient = addCard("Leeching Sliver", source.getController());
        ai.getGame().getAction().checkStateEffects(true);
        final var controller = ((PlayerControllerAi) ai.getController()).getAi();
        final List<Card> candidates = List.of(source, recipient);
        final Map<Card, List<AbilityValueContribution>> uncached = EffectRelationshipEvaluator
                .evaluateRemovalContributions(ai, candidates, EffectAnalysisTrace.disabled());
        controller.withSituationalAnalysis(() -> {
            final SituationalAnalysisSession session = controller.getSituationalAnalysisSession();
            final var first = EffectRelationshipEvaluator.evaluateRemovalContributions(ai, candidates, EffectAnalysisTrace.disabled());
            final var second = EffectRelationshipEvaluator.evaluateRemovalContributions(ai, candidates, EffectAnalysisTrace.disabled());
            Assert.assertEquals(first, uncached);
            Assert.assertEquals(second, first);
            Assert.assertTrue(session.cacheHitCount() >= 2);
            return null;
        });
        Assert.assertNull(controller.getSituationalAnalysisSession());
    }

    @Test
    public void targetingEventsAreNotReusedBetweenDifferentActions() {
        final Player ai = mastermind();
        final Player opponent = ai.getGame().getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        final Card target = addCard("Grizzly Bears", opponent);
        final Card creature = addCard("Grizzly Bears", opponent);
        creature.setSVar("TargetOutcome", "DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1");
        creature.addTrigger(TriggerHandler.parseTrigger("Mode$ BecomesTarget | ValidSource$ SpellAbility.OppCtrl"
                + " | ValidTarget$ Card.Self | Execute$ TargetOutcome | TriggerZones$ Battlefield", creature, false));
        final Card host = addCard("Grizzly Bears", ai);
        final SpellAbility ability = AbilityFactory.getAbility("AB$ Destroy | Cost$ 3 | ValidTgts$ Creature", host);
        ability.setActivatingPlayer(ai);
        ai.getGame().getAction().checkStateEffects(true);
        final List<Card> candidates = List.of(creature, target);
        final var noAction = EffectRelationshipEvaluator.evaluateRemovalContributions(ai, candidates,
                null, EffectAnalysisTrace.disabled());
        final var withAction = EffectRelationshipEvaluator.evaluateRemovalContributions(ai, candidates,
                ability, EffectAnalysisTrace.disabled());
        Assert.assertNotEquals(withAction, noAction);
        final var controller = ((PlayerControllerAi) ai.getController()).getAi();
        controller.withSituationalAnalysis(() -> {
            Assert.assertEquals(EffectRelationshipEvaluator.evaluateRemovalContributions(ai, candidates,
                    null, EffectAnalysisTrace.disabled()), noAction);
            Assert.assertEquals(EffectRelationshipEvaluator.evaluateRemovalContributions(ai, candidates,
                    ability, EffectAnalysisTrace.disabled()), withAction);
            Assert.assertEquals(EffectRelationshipEvaluator.evaluateRemovalContributions(ai, candidates,
                    null, EffectAnalysisTrace.disabled()), noAction);
            return null;
        });
    }

    @Test
    public void probeChangesInvalidateAndExceptionsDoNotPoisonEntries() {
        final Player ai = mastermind();
        final Card target = addCard("Grizzly Bears", ai.getGame().getPlayers().get(0));
        final var controller = ((PlayerControllerAi) ai.getController()).getAi();
        final AtomicInteger calls = new AtomicInteger();
        controller.withSituationalAnalysis(() -> {
            final SituationalAnalysisSession session = controller.getSituationalAnalysisSession();
            final var section = SituationalAnalysisSession.Section.CURRENT_STATIC;
            final var trace = EffectAnalysisTrace.disabled();
            session.baseline(section, List.of(target), trace, () -> { calls.incrementAndGet(); return Map.of(); });
            session.baseline(section, List.of(target), trace, () -> { calls.incrementAndGet(); return Map.of(); });
            Assert.assertEquals(calls.get(), 1);
            AiCardMemory.rememberCard(ai, target, AiCardMemory.MemorySet.PAYS_TAP_COST);
            session.baseline(section, List.of(target), trace, () -> { calls.incrementAndGet(); return Map.of(); });
            Assert.assertEquals(calls.get(), 2);
            session.invalidate();
            Assert.expectThrows(IllegalStateException.class, () -> session.baseline(section, List.of(target), trace,
                    () -> { throw new IllegalStateException("failed preparation"); }));
            session.baseline(section, List.of(target), trace, () -> { calls.incrementAndGet(); return Map.of(); });
            Assert.assertEquals(calls.get(), 3);
            return null;
        });
    }

    @Test
    public void projectedCandidatesAndChangedScriptInputsDoNotReuseLiveResults() {
        final Player ai = mastermind();
        final Card target = addCard("Grizzly Bears", ai.getGame().getPlayers().get(0));
        final var controller = ((PlayerControllerAi) ai.getController()).getAi();
        final AtomicInteger calls = new AtomicInteger();
        controller.withSituationalAnalysis(() -> {
            final SituationalAnalysisSession session = controller.getSituationalAnalysisSession();
            final var section = SituationalAnalysisSession.Section.CURRENT_STATIC;
            final var trace = EffectAnalysisTrace.disabled();
            session.baseline(section, List.of(target), trace, () -> { calls.incrementAndGet(); return Map.of(); });
            final Card projected = CardCopyService.getLKICopy(target);
            session.baseline(section, List.of(projected), trace, () -> { calls.incrementAndGet(); return Map.of(); });
            session.baseline(section, List.of(projected), trace, () -> { calls.incrementAndGet(); return Map.of(); });
            Assert.assertEquals(calls.get(), 3);
            session.baseline(section, List.of(target), trace, () -> { calls.incrementAndGet(); return Map.of(); });
            Assert.assertEquals(calls.get(), 3);
            target.setSVar("ProbeValue", "2");
            session.baseline(section, List.of(target), trace, () -> { calls.incrementAndGet(); return Map.of(); });
            Assert.assertEquals(calls.get(), 4);
            return null;
        });
    }

    @Test
    public void futureReusePreservesLiveReferenceAndRemovalDecisions() {
        final Player ai = mastermind();
        final Player opponent = ai.getGame().getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        final Card engine = addCard("Staff of Nin", opponent);
        addCard("Grim Lavamancer", ai);
        final Card other = addCard("Akroma's Memorial", opponent);
        ai.getGame().getAction().checkStateEffects(true);
        final var trace = EffectAnalysisTrace.disabled();
        final var liveMode = PermanentAbilityValueEvaluator.FutureAbilityMode.LIVE_OUTCOMES;
        final var referenceMode = PermanentAbilityValueEvaluator.FutureAbilityMode.REFERENCE_ONLY;
        final var live = PermanentAbilityValueEvaluator.evaluateFutureAbilities(ai, engine, List.of(), trace, liveMode);
        final var reference = PermanentAbilityValueEvaluator.evaluateFutureAbilities(ai, engine, List.of(), trace, referenceMode);
        Assert.assertFalse(live.contributions().isEmpty());
        final var uncached = PermanentAbilityValueEvaluator.evaluateRemovalAbilities(ai, List.of(engine, other), trace);
        final Card uncachedTarget = ComputerUtilCard.getBestRemovalTargetAI(ai, List.of(engine, other));
        final var controller = ((PlayerControllerAi) ai.getController()).getAi();
        controller.withSituationalAnalysis(() -> {
            final SituationalAnalysisSession session = controller.getSituationalAnalysisSession();
            Assert.assertEquals(PermanentAbilityValueEvaluator.evaluateFutureAbilities(ai, engine, List.of(), trace, liveMode), live);
            final int hits = session.cacheHitCount();
            Assert.assertEquals(PermanentAbilityValueEvaluator.evaluateFutureAbilities(ai, engine, List.of(), trace, liveMode), live);
            Assert.assertTrue(session.cacheHitCount() > hits);
            final int preparations = session.preparationCount();
            Assert.assertEquals(PermanentAbilityValueEvaluator.evaluateFutureAbilities(ai, engine, List.of(), trace, referenceMode), reference);
            Assert.assertEquals(session.preparationCount(), preparations);
            Assert.assertEquals(PermanentAbilityValueEvaluator.evaluateRemovalAbilities(ai, List.of(engine, other), trace), uncached);
            Assert.assertEquals(PermanentAbilityValueEvaluator.evaluateRemovalAbilities(ai, List.of(engine, other), trace), uncached);
            Assert.assertSame(ComputerUtilCard.getBestRemovalTargetAI(ai, List.of(engine, other)), uncachedTarget);
            Assert.assertEquals(PermanentAbilityValueEvaluator.evaluateRemovalAbilities(ai, List.of(engine, other),
                    EffectAnalysisTrace.create(ai)), uncached);
            return null;
        });
    }

    @Test
    public void futureRelationshipCreditHasItsOwnKey() {
        final Player ai = mastermind();
        final Player opponent = ai.getGame().getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        final Card engine = addCard("Mardu Strike Leader", opponent);
        final var trace = EffectAnalysisTrace.disabled();
        final var mode = PermanentAbilityValueEvaluator.FutureAbilityMode.LIVE_OUTCOMES;
        final var uncredited = PermanentAbilityValueEvaluator.evaluateFutureAbilities(ai, engine, List.of(), trace, mode);
        final var opportunity = uncredited.contributions().stream()
                .filter(entry -> entry.kind() == AbilityValueKind.INTRINSIC_SELF_OPPORTUNITY && entry.counted())
                .findFirst().orElseThrow();
        final var relationship = AbilityValueContribution.counted(engine, engine, opportunity.sourceAbility(),
                engine, opportunity.sourceAbility(), AbilityValueKind.KNOWN_RELATIONSHIP, 10, "current-attack", "Current attack credit");
        final var credited = PermanentAbilityValueEvaluator.evaluateFutureAbilities(ai, engine, List.of(relationship), trace, mode);
        Assert.assertNotEquals(credited, uncredited);
        final var controller = ((PlayerControllerAi) ai.getController()).getAi();
        controller.withSituationalAnalysis(() -> {
            Assert.assertEquals(PermanentAbilityValueEvaluator.evaluateFutureAbilities(ai, engine, List.of(), trace, mode), uncredited);
            Assert.assertEquals(PermanentAbilityValueEvaluator.evaluateFutureAbilities(ai, engine, List.of(relationship), trace, mode), credited);
            Assert.assertEquals(PermanentAbilityValueEvaluator.evaluateFutureAbilities(ai, engine, List.of(), trace, mode), uncredited);
            return null;
        });
    }

    @Test
    public void futureCoverageIsRetainedButRecursiveAndFailedPreparationIsNot() {
        final Player ai = mastermind();
        final Card card = addCard("Grizzly Bears", ai.getGame().getPlayers().get(0));
        final var partial = new PermanentAbilityValueEvaluator.FutureAbilityEvaluation(List.of(), true,
                List.of("An unsupported ability was fully inventoried"));
        final AtomicInteger calls = new AtomicInteger();
        final var controller = ((PlayerControllerAi) ai.getController()).getAi();
        controller.withSituationalAnalysis(() -> {
            final var session = controller.getSituationalAnalysisSession();
            final var mode = PermanentAbilityValueEvaluator.FutureAbilityMode.LIVE_OUTCOMES;
            final var trace = EffectAnalysisTrace.disabled();
            Assert.assertEquals(session.futureAbilities(card, List.of(), trace, mode,
                    () -> { calls.incrementAndGet(); return partial; }), partial);
            Assert.assertEquals(session.futureAbilities(card, List.of(), trace, mode,
                    () -> { calls.incrementAndGet(); return partial; }), partial);
            Assert.assertEquals(calls.get(), 1);
            session.invalidate();
            Assert.expectThrows(IllegalStateException.class, () -> session.futureAbilities(card, List.of(), trace, mode,
                    () -> { throw new IllegalStateException("failed preparation"); }));
            session.futureAbilities(card, List.of(), trace, mode, () -> {
                calls.incrementAndGet();
                final var recursive = session.futureAbilities(card, List.of(), trace, mode, () -> partial);
                Assert.assertTrue(recursive.hasUnevaluatedAbility());
                Assert.assertNotEquals(recursive.reasons(), partial.reasons());
                return partial;
            });
            session.futureAbilities(card, List.of(), trace, mode, () -> { calls.incrementAndGet(); return partial; });
            Assert.assertEquals(calls.get(), 3);
            final Card projected = CardCopyService.getLKICopy(card);
            session.futureAbilities(projected, List.of(), trace, mode, () -> { calls.incrementAndGet(); return partial; });
            session.futureAbilities(projected, List.of(), trace, mode, () -> { calls.incrementAndGet(); return partial; });
            Assert.assertEquals(calls.get(), 5);
            session.futureAbilities(card, List.of(), trace, mode, () -> { calls.incrementAndGet(); return partial; });
            Assert.assertEquals(calls.get(), 5);
            card.setSVar("ChangedFutureOutcome", "3");
            session.futureAbilities(card, List.of(), trace, mode, () -> { calls.incrementAndGet(); return partial; });
            Assert.assertEquals(calls.get(), 6);
            session.invalidate();
            session.futureAbilities(card, List.of(), trace, mode, () -> {
                calls.incrementAndGet();
                try {
                    session.baseline(SituationalAnalysisSession.Section.CURRENT_STATIC, List.of(card), trace,
                            () -> { throw new IllegalStateException("nested failed preparation"); });
                } catch (final IllegalStateException expected) {
                    // Preserve an understood subtotal, but it must not publish a cached success.
                }
                return partial;
            });
            session.futureAbilities(card, List.of(), trace, mode, () -> { calls.incrementAndGet(); return partial; });
            Assert.assertEquals(calls.get(), 8);
            return null;
        });
    }

    @Test
    public void liveInventoryIsImmutableAndTracksNestedAndGrantedChanges() {
        final Player ai = mastermind();
        final Card card = addCard("Grizzly Bears", ai.getGame().getPlayers().get(0));
        card.setSVar("DrawChoice", "DB$ Draw | Defined$ You | NumCards$ 1");
        card.setSVar("LifeChoice", "DB$ GainLife | Defined$ You | LifeAmount$ 2");
        final SpellAbility choice = AbilityFactory.getAbility("AB$ Charm | Cost$ T | Choices$ DrawChoice,LifeChoice", card);
        card.addSpellAbility(choice);
        final var trigger = TriggerHandler.parseTrigger("Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You", card, false);
        final SpellAbility outcome = AbilityFactory.getAbility("DB$ Draw | Defined$ You | NumCards$ 1", card);
        outcome.setSubAbility((AbilitySub) AbilityFactory.getAbility("DB$ GainLife | Defined$ You | LifeAmount$ 1", card));
        trigger.setOverridingAbility(outcome);
        card.addTrigger(trigger);
        final var controller = ((PlayerControllerAi) ai.getController()).getAi();
        controller.withSituationalAnalysis(() -> {
            final var session = controller.getSituationalAnalysisSession();
            final var trace = EffectAnalysisTrace.disabled();
            final var first = CardAbilityTraversal.inspectLive(ai, card, trace);
            Assert.assertEquals(first, CardAbilityTraversal.inspect(card.getCurrentState()));
            Assert.expectThrows(UnsupportedOperationException.class, () -> first.clear());
            Assert.expectThrows(UnsupportedOperationException.class, () -> first.get(0).parameters().put("Cost", "0"));
            final int preparations = session.preparationCount();
            Assert.assertEquals(CardAbilityTraversal.inspectLive(ai, card, trace), first);
            Assert.assertEquals(session.preparationCount(), preparations);
            for (final Runnable change : List.<Runnable>of(
                    () -> choice.getAdditionalAbilityList("Choices").get(0).putParam("NumCards", "3"),
                    () -> outcome.getSubAbility().putParam("LifeAmount", "4"),
                    () -> trigger.putParam("Phase", "EndOfTurn"),
                    () -> trigger.setOverridingAbility(AbilityFactory.getAbility("DB$ GainLife | Defined$ You | LifeAmount$ 5", card)),
                    () -> choice.setIntrinsic(true),
                    () -> card.addSpellAbility(AbilityFactory.getAbility("AB$ Draw | Cost$ T | NumCards$ 1", card)),
                    () -> card.addStaticAbility("Mode$ Continuous | Affected$ Creature.YouCtrl | AddPower$ 1"))) {
                final int before = session.preparationCount();
                change.run();
                Assert.assertEquals(CardAbilityTraversal.inspectLive(ai, card, trace), CardAbilityTraversal.inspect(card.getCurrentState()));
                Assert.assertTrue(session.preparationCount() > before);
            }
            return null;
        });
    }

    @Test
    public void liveInventoriesDoNotPopulateFromProjectedCards() {
        final Player ai = mastermind();
        final Card card = addCard("Staff of Nin", ai.getGame().getPlayers().get(0));
        final Card projected = CardCopyService.getLKICopy(card);
        final var controller = ((PlayerControllerAi) ai.getController()).getAi();
        controller.withSituationalAnalysis(() -> {
            final var session = controller.getSituationalAnalysisSession();
            final var trace = EffectAnalysisTrace.disabled();
            final var live = CardAbilityTraversal.inspectLive(ai, card, trace);
            final int preparations = session.preparationCount();
            Assert.assertEquals(CardAbilityTraversal.inspectLive(ai, projected, trace), CardAbilityTraversal.inspect(projected.getCurrentState()));
            Assert.assertEquals(session.preparationCount(), preparations);
            final int hits = session.cacheHitCount();
            Assert.assertEquals(CardAbilityTraversal.inspectLive(ai, card, trace), live);
            Assert.assertEquals(session.cacheHitCount(), hits + 1);
            return null;
        });
    }

    @Test
    public void consequenceRoutingReusesDescriptorsButBindsFreshOutcomesAndActivity() {
        final Player ai = mastermind();
        final Player opponent = ai.getGame().getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        final Card card = addCard("Grizzly Bears", opponent);
        card.setSVar("TargetOutcome", "DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1");
        final var trigger = TriggerHandler.parseTrigger("Mode$ BecomesTarget | ValidSource$ SpellAbility.OppCtrl"
                + " | ValidTarget$ Card.Self | Execute$ TargetOutcome | TriggerZones$ Battlefield", card, false);
        card.addTrigger(trigger);
        final var controller = ((PlayerControllerAi) ai.getController()).getAi();
        controller.withSituationalAnalysis(() -> {
            final var session = controller.getSituationalAnalysisSession();
            final var trace = EffectAnalysisTrace.disabled();
            final var index = session.consequenceIndex(List.of(card), List.of(opponent), trace);
            final var references = index.forType(EffectType.BECAME_TARGET);
            Assert.assertEquals(references.size(), 1);
            Assert.assertTrue(index.forType(EffectType.LIFE_GAINED).isEmpty());
            Assert.expectThrows(UnsupportedOperationException.class, () -> references.clear());
            final var first = references.get(0).bind();
            Assert.assertNotNull(first);
            first.outcome().putParam("CounterNum", "9");
            final var second = references.get(0).bind();
            Assert.assertNotSame(second.outcome(), first.outcome());
            Assert.assertEquals(second.outcome().getParam("CounterNum"), "1");
            final int hits = session.cacheHitCount();
            Assert.assertSame(session.consequenceIndex(List.of(card), List.of(opponent), trace), index);
            Assert.assertEquals(session.cacheHitCount(), hits + 1);
            trigger.setSuppressed(true);
            Assert.assertNull(references.get(0).bind());
            final var changed = session.consequenceIndex(List.of(card), List.of(opponent), trace);
            Assert.assertNotSame(changed, index);
            Assert.assertNull(changed.forType(EffectType.BECAME_TARGET).get(0).bind());
            Assert.assertTrue(session.consequenceIndex(List.of(), List.of(), trace)
                    .forType(EffectType.BECAME_TARGET).isEmpty());
            return null;
        });
    }

    @Test
    public void engineRevisionsInvalidateWithoutTreatingProjectedMutationsAsLive() {
        final Player ai = mastermind();
        final Card card = addCard("Grizzly Bears", ai.getGame().getPlayers().get(0));
        final var controller = ((PlayerControllerAi) ai.getController()).getAi();
        final AtomicInteger calls = new AtomicInteger();
        controller.withSituationalAnalysis(() -> {
            final var session = controller.getSituationalAnalysisSession();
            final Runnable prepare = () -> session.baseline(SituationalAnalysisSession.Section.CURRENT_STATIC,
                    List.of(card), EffectAnalysisTrace.disabled(), () -> {
                        calls.incrementAndGet();
                        return Map.of();
                    });
            prepare.run();
            prepare.run();
            Assert.assertEquals(calls.get(), 1);
            final Card projected = CardCopyService.getLKICopy(card);
            projected.setTapped(true);
            prepare.run();
            Assert.assertEquals(calls.get(), 1);
            card.setTapped(true);
            prepare.run();
            Assert.assertEquals(calls.get(), 2);
            prepare.run();
            Assert.assertEquals(calls.get(), 2);
            ai.getGame().costPaymentStack.push(null, null);
            prepare.run();
            Assert.assertEquals(calls.get(), 3);
            ai.getGame().costPaymentStack.pop();
            prepare.run();
            Assert.assertEquals(calls.get(), 4);
            card.setCounters(CounterEnumType.P1P1, 1);
            prepare.run();
            Assert.assertEquals(calls.get(), 5);
            projected.setCounters(CounterEnumType.P1P1, 2);
            prepare.run();
            Assert.assertEquals(calls.get(), 5);
            return null;
        });
    }

    @Test
    public void separateDecisionsRetainStructureButRebuildNumericValues() {
        final Player ai = mastermind();
        final Player opponent = ai.getGame().getPlayers().get(0);
        final Card card = addCard("Staff of Nin", opponent);
        final var controller = ((PlayerControllerAi) ai.getController()).getAi();
        final AtomicInteger numericCalls = new AtomicInteger();
        final Object[] previous = new Object[2];
        for (int decision = 0; decision < 2; decision++) {
            final boolean first = decision == 0;
            controller.withSituationalAnalysis(() -> {
                final var session = controller.getSituationalAnalysisSession();
                final var trace = EffectAnalysisTrace.disabled();
                final var inventory = CardAbilityTraversal.inspectLive(ai, card, trace);
                final var index = session.consequenceIndex(List.of(card), List.of(opponent), trace);
                if (first) {
                    previous[0] = inventory;
                    previous[1] = index;
                } else {
                    Assert.assertSame(inventory, previous[0]);
                    Assert.assertSame(index, previous[1]);
                    Assert.assertEquals(session.cacheHitCount(), 2);
                }
                session.baseline(SituationalAnalysisSession.Section.CURRENT_STATIC, List.of(card), trace,
                        () -> { numericCalls.incrementAndGet(); return Map.of(); });
                session.futureAbilities(card, List.of(), trace,
                        PermanentAbilityValueEvaluator.FutureAbilityMode.LIVE_OUTCOMES, () -> {
                            numericCalls.incrementAndGet();
                            return new PermanentAbilityValueEvaluator.FutureAbilityEvaluation(List.of(), false, List.of());
                        });
                return null;
            });
        }
        Assert.assertEquals(numericCalls.get(), 4);
    }

    @Test
    public void changedInputsAndFailedDecisionsDiscardRetainedStructure() {
        final Player ai = mastermind();
        final Card card = addCard("Staff of Nin", ai.getGame().getPlayers().get(0));
        final var controller = ((PlayerControllerAi) ai.getController()).getAi();
        final Object[] inventory = new Object[1];
        final Runnable inspect = () -> controller.withSituationalAnalysis(() -> {
            final var next = CardAbilityTraversal.inspectLive(ai, card, EffectAnalysisTrace.disabled());
            if (inventory[0] != null) { Assert.assertNotSame(next, inventory[0]); }
            inventory[0] = next;
            return null;
        });
        inspect.run();
        card.setTapped(true);
        inspect.run();
        card.getTriggers().get(0).putParam("Phase", "EndOfTurn");
        inspect.run();
        Assert.expectThrows(IllegalStateException.class, () -> controller.withSituationalAnalysis(() -> {
            Assert.assertSame(CardAbilityTraversal.inspectLive(ai, card, EffectAnalysisTrace.disabled()), inventory[0]);
            throw new IllegalStateException("aborted chooser");
        }));
        inspect.run();
        controller.withSituationalAnalysis(() -> {
            Assert.assertSame(CardAbilityTraversal.inspectLive(ai, card, EffectAnalysisTrace.disabled()), inventory[0]);
            Thread.currentThread().interrupt();
            return null;
        });
        Assert.assertTrue(Thread.interrupted());
        inspect.run();
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        controller.withSituationalAnalysis(() -> { Assert.assertNull(controller.getSituationalAnalysisSession()); return null; });
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Mastermind");
        inspect.run();
    }

    @Test
    public void olderWorkerCannotOverwriteNewerRetainedStructure() throws Exception {
        final Player ai = mastermind();
        final Card card = addCard("Staff of Nin", ai.getGame().getPlayers().get(0));
        final var controller = ((PlayerControllerAi) ai.getController()).getAi();
        final var ready = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        final var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        final Thread older = new Thread(() -> {
            try {
                controller.withSituationalAnalysis(() -> {
                    CardAbilityTraversal.inspectLive(ai, card, EffectAnalysisTrace.disabled());
                    ready.countDown();
                    try {
                        Assert.assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    } catch (final InterruptedException interrupted) {
                        throw new IllegalStateException(interrupted);
                    }
                    return null;
                });
            } catch (final Throwable problem) { failure.set(problem); }
        });
        older.start();
        final Object[] newer = new Object[1];
        try {
            Assert.assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            controller.withSituationalAnalysis(() -> {
                newer[0] = CardAbilityTraversal.inspectLive(ai, card, EffectAnalysisTrace.disabled());
                return null;
            });
        } finally {
            release.countDown();
            older.join(5000);
        }
        Assert.assertFalse(older.isAlive());
        Assert.assertNull(failure.get());
        controller.withSituationalAnalysis(() -> {
            Assert.assertSame(CardAbilityTraversal.inspectLive(ai, card, EffectAnalysisTrace.disabled()), newer[0]);
            return null;
        });
    }

    @Test
    public void defaultAndSeparateDecisionsDoNotShareSessions() {
        final Player ai = mastermind();
        final var controller = ((PlayerControllerAi) ai.getController()).getAi();
        final SituationalAnalysisSession[] first = new SituationalAnalysisSession[1];
        controller.withSituationalAnalysis(() -> { first[0] = controller.getSituationalAnalysisSession(); return null; });
        controller.withSituationalAnalysis(() -> {
            Assert.assertNotSame(controller.getSituationalAnalysisSession(), first[0]);
            Assert.assertFalse(first[0].isOwnedByCurrentThread());
            return null;
        });
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        controller.withSituationalAnalysis(() -> { Assert.assertNull(controller.getSituationalAnalysisSession()); return null; });
    }
}
