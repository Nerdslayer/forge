package forge.ai.effect;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import forge.ai.AiCardMemory;
import forge.ai.PlayerControllerAi;
import forge.game.GameObject;
import forge.game.CardTraitBase;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import org.tinylog.Logger;

/** Decision-local values with validated cross-decision retention of immutable structural facts. */
public final class SituationalAnalysisSession implements AutoCloseable {
    enum Section { TRIGGERED_BASELINE, CURRENT_STATIC }
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final int MAX_ENTRIES = 64;
    private final long id = NEXT_ID.incrementAndGet();
    private final Player ai;
    private final Thread owner = Thread.currentThread();
    private final Map<Key, Map<Card, List<AbilityValueContribution>>> baselines = new HashMap<>();
    private final Map<FutureKey, PermanentAbilityValueEvaluator.FutureAbilityEvaluation> futureValues = new HashMap<>();
    private final Map<InventoryKey, List<CardAbilityTraversal.AbilityDescription>> inventories = new HashMap<>();
    private final Map<List<Player>, PreparedConsequenceIndex> consequenceIndexes = new HashMap<>();
    private final Set<Object> building = new HashSet<>();
    private List<Object> inputs;
    private boolean closed;
    private long failures;
    private int preparations;
    private int hits;
    private int invalidations;
    private long preparationNanos;
    private int retainedEntries;

    private record Key(Section section, Set<Player> controllers) { }
    private record FutureKey(Card candidate, long timestamp,
            PermanentAbilityValueEvaluator.FutureAbilityMode mode, List<AbilityValueContribution> relationships) { }
    private record InventoryKey(Card card, long timestamp, Object state) { }
    private record CardInputs(Card card, long timestamp, Object state, Integer number,
            String type, String color, Player controller, Map<String, String> variables) { }
    private record AbilityInputs(SpellAbility ability, Integer x, List<GameObject> targets) { }
    private record TraitInputs(CardTraitBase trait, Map<String, String> parameters,
            Map<String, String> variables, boolean intrinsic, boolean keyword, boolean suppressed) { }
    private record AbilityLink(String path, SpellAbility child) { }
    private record AbilityShape(SpellAbility ability, Object api, boolean activated, List<AbilityLink> children) { }

    /** Opaque immutable transfer; never shares mutable session maps or numeric contributions. */
    public static final class RetainedAnalysis {
        private final Player owner;
        private final List<Object> inputs;
        private final Map<InventoryKey, List<CardAbilityTraversal.AbilityDescription>> inventories;
        private final Map<List<Player>, PreparedConsequenceIndex> consequenceIndexes;

        private RetainedAnalysis(final SituationalAnalysisSession session) {
            owner = session.ai;
            inputs = List.copyOf(session.inputs);
            inventories = Map.copyOf(session.inventories);
            consequenceIndexes = Map.copyOf(session.consequenceIndexes);
        }
    }

    public SituationalAnalysisSession(final Player ai) {
        this(ai, null);
    }

    public SituationalAnalysisSession(final Player ai, final RetainedAnalysis retained) {
        this.ai = ai;
        ai.getGame().enableAnalysisStateTracking();
        if (retained != null && retained.owner == ai && !ai.getGame().isGameOver()) {
            try {
                if (retained.inputs.equals(probeInputs())) {
                    inputs = retained.inputs;
                    inventories.putAll(retained.inventories);
                    consequenceIndexes.putAll(retained.consequenceIndexes);
                    retainedEntries = inventories.size() + consequenceIndexes.size();
                }
            } catch (final RuntimeException unavailable) {
                // Retention is optional; an incomplete validation must not affect legacy play.
                invalidate();
            }
        }
    }

    public RetainedAnalysis retainStructuralAnalysis() {
        if (!isOwnedByCurrentThread() || Thread.currentThread().isInterrupted() || ai.getGame().isGameOver()
                || failures != 0 || !building.isEmpty() || inputs == null
                || inventories.isEmpty() && consequenceIndexes.isEmpty()) { return null; }
        try {
            if (inputs.equals(probeInputs())) { return new RetainedAnalysis(this); }
        } catch (final RuntimeException unavailable) {
            // A failed/unstable end-of-decision validation publishes no reusable entries.
        }
        return null;
    }

    public boolean isOwnedByCurrentThread() {
        return !closed && owner == Thread.currentThread();
    }

    /** Narrow live-controller boundary; no thread-local or process-global analysis cache. */
    static SituationalAnalysisSession current(final Player ai) {
        return ai != null && ai.getController() instanceof PlayerControllerAi controller
                ? controller.getAi().getSituationalAnalysisSession() : null;
    }

    static void noteFailure(final Player ai) {
        final SituationalAnalysisSession session = current(ai);
        if (session != null) {
            session.failures++;
        }
    }

    Map<Card, List<AbilityValueContribution>> baseline(final Section section,
            final List<Card> candidates, final EffectAnalysisTrace trace,
            final Supplier<Map<Card, List<AbilityValueContribution>>> prepare) {
        if (!isOwnedByCurrentThread() || !areLiveCandidates(candidates)) {
            return prepare.get();
        }
        final Set<Player> controllers = new HashSet<>();
        for (final Card card : candidates) {
            if (card.getController() != null && card.getController().isOpponentOf(ai)) {
                controllers.add(card.getController());
            }
        }
        final Key key = new Key(section, Set.copyOf(controllers));
        return prepared(baselines, key, section.name(), trace, () -> {
            final Map<Card, List<AbilityValueContribution>> values = new LinkedHashMap<>();
            prepare.get().forEach((card, entries) -> values.put(card, List.copyOf(entries)));
            return Map.copyOf(values);
        }, Map.of());
    }

    PermanentAbilityValueEvaluator.FutureAbilityEvaluation futureAbilities(final Card candidate,
            final List<AbilityValueContribution> relationships, final EffectAnalysisTrace trace,
            final PermanentAbilityValueEvaluator.FutureAbilityMode mode,
            final Supplier<PermanentAbilityValueEvaluator.FutureAbilityEvaluation> prepare) {
        // Reference-only combat preparation has different callback/coverage guarantees. Projected
        // and reference requests must never acquire or populate this live outcome cache.
        if (!isOwnedByCurrentThread() || mode != PermanentAbilityValueEvaluator.FutureAbilityMode.LIVE_OUTCOMES
                || !areLiveCandidates(List.of(candidate))) {
            return prepare.get();
        }
        final FutureKey key = new FutureKey(candidate, candidate.getGameTimestamp(), mode, List.copyOf(relationships));
        return prepared(futureValues, key, "FUTURE_ABILITIES card=" + candidate.getId(), trace, prepare,
                new PermanentAbilityValueEvaluator.FutureAbilityEvaluation(List.of(), true,
                        List.of("Future ability preparation already in progress; no recursive credit")));
    }

    List<CardAbilityTraversal.AbilityDescription> inventory(final Card card, final EffectAnalysisTrace trace) {
        if (!isOwnedByCurrentThread() || card.isFaceDown() || !areLiveCandidates(List.of(card))) {
            return CardAbilityTraversal.inspect(card.getCurrentState());
        }
        final InventoryKey key = new InventoryKey(card, card.getGameTimestamp(), card.getCurrentState());
        return prepared(inventories, key, "ABILITY_INVENTORY card=" + card.getId(), trace,
                () -> List.copyOf(CardAbilityTraversal.inspect(card.getCurrentState())), null);
    }

    PreparedConsequenceIndex consequenceIndex(final List<Card> candidates, final List<Player> controllers,
            final EffectAnalysisTrace trace) {
        if (!isOwnedByCurrentThread() || !areLiveCandidates(candidates)) {
            return PreparedConsequenceIndex.prepare(ai, controllers);
        }
        return prepared(consequenceIndexes, List.copyOf(controllers), "CONSEQUENCE_INDEX", trace,
                () -> PreparedConsequenceIndex.prepare(ai, controllers), null);
    }

    private <K, V> V prepared(final Map<K, V> cache, final K key, final String section,
            final EffectAnalysisTrace trace, final Supplier<V> prepare, final V reentrantFallback) {
        final List<Object> nextInputs = probeInputs();
        if (inputs != null && !inputs.equals(nextInputs)) {
            invalidate();
        }
        inputs = nextInputs;
        final V cached = cache.get(key);
        if (cached != null) {
            hits++;
            trace.sharedBaseline(id, section, true);
            return cached;
        }
        if (!building.add(key)) {
            // Reentrant valuation has no complete relationship result. Fail closed and prevent
            // the surrounding partial preparation from becoming a reusable successful entry.
            failures++;
            if (reentrantFallback == null) {
                throw new IllegalStateException("Live structural preparation already in progress");
            }
            return reentrantFallback;
        }
        final long failuresBefore = failures;
        final int invalidationsBefore = invalidations;
        final long started = System.nanoTime();
        final boolean outermostPreparation = building.size() == 1;
        preparations++;
        try {
            final V result = prepare.get();
            if (failures == failuresBefore && invalidations == invalidationsBefore && !Thread.currentThread().isInterrupted()
                    && baselines.size() + futureValues.size() + inventories.size() + consequenceIndexes.size() < MAX_ENTRIES
                    && inputs != null
                    && nextInputs.equals(inputs) && nextInputs.equals(probeInputs())) {
                cache.put(key, result);
            }
            trace.sharedBaseline(id, section, false);
            return result;
        } catch (final RuntimeException | Error failure) {
            // An enclosing preparation may catch this failure and return a subtotal. It must
            // not then publish that partial build as a successful reusable result.
            failures++;
            throw failure;
        } finally {
            if (outermostPreparation) {
                preparationNanos += System.nanoTime() - started;
            }
            building.remove(key);
        }
    }

    private boolean areLiveCandidates(final List<Card> candidates) {
        for (final Card candidate : candidates) {
            boolean found = false;
            for (final Card live : ai.getGame().getCardsIn(ZoneType.Battlefield)) {
                if (candidate == live) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private List<Object> probeInputs() {
        // These transient inputs can change even without executing an action: legacy probes
        // select targets/X and reserve payment sources. Preserve their existing effects.
        final List<Object> result = new ArrayList<>();
        result.add(ai.getGame().getAnalysisStateRevision());
        for (final AiCardMemory.MemorySet set : AiCardMemory.MemorySet.values()) {
            final Set<Card> memory = AiCardMemory.getMemorySet(ai, set);
            result.add(memory == null ? Set.of() : Set.copyOf(memory));
        }
        final List<Card> cards = new ArrayList<>();
        ai.getGame().getCardsIn(ZoneType.Battlefield).forEach(cards::add);
        ai.getCardsIn(ZoneType.Hand).forEach(cards::add);
        for (final Card card : cards) {
            if (card.isFaceDown() && card.getController() != ai) {
                continue;
            }
            result.add(new CardInputs(card, card.getGameTimestamp(), card.getCurrentState(),
                    card.getChosenNumber(), card.getChosenType(), card.getChosenColor(), card.getController(), Map.copyOf(card.getSVars())));
            appendTraits(card, result);
        }
        return List.copyOf(result);
    }

    private static void appendTrait(final CardTraitBase trait, final List<Object> result) {
        result.add(new TraitInputs(trait, Map.copyOf(trait.getMapParams()), Map.copyOf(trait.getSVars()),
                trait.isIntrinsic(), trait.getKeyword() != null, trait.isSuppressed()));
    }

    private static void appendTraits(final Card card, final List<Object> result) {
        final ArrayDeque<SpellAbility> pending = new ArrayDeque<>();
        card.getCurrentState().getSpellAbilities().forEach(pending::add);
        for (final var trigger : card.getCurrentState().getTriggers()) {
            appendTrait(trigger, result);
            if (trigger.getOverridingAbility() != null) { pending.add(trigger.getOverridingAbility()); }
        }
        card.getCurrentState().getStaticAbilities().forEach(trait -> appendTrait(trait, result));
        card.getHiddenStaticAbilities().forEach(trait -> appendTrait(trait, result));
        card.getCurrentState().getReplacementEffects().forEach(trait -> appendTrait(trait, result));
        final Set<SpellAbility> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!pending.isEmpty()) {
            final SpellAbility ability = pending.removeFirst();
            if (!visited.add(ability)) { continue; }
            if (visited.size() > 1024 || !hasBoundedParents(ability)) {
                // TODO: Dependency revisions can avoid inspecting every nested node. For an
                // unaudited oversized graph, a fresh marker deliberately prevents cache reuse.
                result.add(new Object());
                return;
            }
            appendTrait(ability, result);
            final List<GameObject> targets = new ArrayList<>();
            ability.getTargets().forEach(targets::add);
            result.add(new AbilityInputs(ability, ability.getXManaCostPaid(), List.copyOf(targets)));
            final List<AbilityLink> children = new ArrayList<>();
            if (ability.getSubAbility() != null) { children.add(new AbilityLink("next", ability.getSubAbility())); }
            new TreeMap<>(ability.getAdditionalAbilities()).forEach((name, child) -> children.add(new AbilityLink(name, child)));
            new TreeMap<>(ability.getAdditionalAbilityLists()).forEach((name, list) -> {
                for (int i = 0; i < list.size(); i++) { children.add(new AbilityLink(name + ":" + i, list.get(i))); }
            });
            result.add(new AbilityShape(ability, ability.getApi(), ability.isActivatedAbility(), List.copyOf(children)));
            children.forEach(link -> pending.add(link.child()));
        }
    }

    private static boolean hasBoundedParents(final SpellAbility ability) {
        // getSVars resolves parent fallbacks, and AbilitySub consults getRootAbility. Check the
        // ancestry without those recursive getters before reading a malformed nested graph.
        final Set<SpellAbility> parents = Collections.newSetFromMap(new IdentityHashMap<>());
        SpellAbility current = ability;
        while (current != null) {
            if (!parents.add(current) || parents.size() > 64) { return false; }
            current = current.getParent();
        }
        return true;
    }

    public void invalidate() {
        baselines.clear();
        futureValues.clear();
        inventories.clear();
        consequenceIndexes.clear();
        inputs = null;
        invalidations++;
    }

    public int preparationCount() { return preparations; }
    public int cacheHitCount() { return hits; }

    @Override
    public void close() {
        if (closed) { return; }
        closed = true;
        if (Boolean.parseBoolean(System.getProperty(EffectAnalysisTrace.ENABLE_PROPERTY, "true"))) {
            Logger.info("[AI Effect Analysis] Shared analysis session: id={}, preparations={}, hits={}, "
                    + "invalidations={}, retainedEntries={}, preparationMs={}", id, preparations, hits, invalidations, retainedEntries,
                    preparationNanos / 1_000_000.0);
        }
        baselines.clear();
        futureValues.clear();
        inventories.clear();
        consequenceIndexes.clear();
        inputs = null;
        // TODO: Numeric cross-decision retention still requires complete static/history/limit and
        // concurrent mutation coverage. Add normalized production facts and compatible combat preparation;
        // reference/projected evaluations and action overlays must keep independent lifetimes.
    }
}
