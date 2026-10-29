package forge.ai.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementType;
import forge.game.staticability.StaticAbilityCantExile;
import forge.game.zone.ZoneType;

/** Frozen public object lifetime, independent of intrinsic card value or removal targeting. */
public record PreparedCombatDepartures(Map<Integer, Departure> entries, List<String> unsupportedReasons,
        List<String> futureUnsupportedReasons) {
    public PreparedCombatDepartures {
        entries = Map.copyOf(entries);
        unsupportedReasons = List.copyOf(unsupportedReasons);
        futureUnsupportedReasons = List.copyOf(futureUnsupportedReasons);
    }

    public enum Provenance { UNEARTH }
    public enum Boundary { NEXT_END_STEP }
    public record Departure(long objectTimestamp, int effectId, long effectTimestamp, Provenance provenance,
            Boundary boundary, ZoneType destination, boolean beforeNextCombat) { }

    public static PreparedCombatDepartures empty() { return new PreparedCombatDepartures(Map.of(), List.of(), List.of()); }

    public Set<Integer> expiringIds() {
        return entries.entrySet().stream().filter(e -> e.getValue().beforeNextCombat()).map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    boolean exilesInsteadOfDying(final int id) { return entries.containsKey(id); }

    private static boolean mayIncludeZone(final String expression, final ZoneType zone) {
        if (expression == null) { return true; }
        final Set<String> names = java.util.Arrays.stream(expression.split(",")).map(String::trim)
                .collect(java.util.stream.Collectors.toSet());
        if (names.contains(zone.name()) || names.contains("Any")) { return true; }
        final Set<String> known = java.util.Arrays.stream(ZoneType.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet());
        return !known.containsAll(names); // Unknown selectors are not proof that an exit cannot match.
    }

    /** Recognize only the engine's standard active, remembered-object unearth replacement. */
    static boolean standardReplacement(final Card effect, final ReplacementEffect replacement) {
        final var source = effect.getEffectSourceAbility();
        final var ability = replacement.getOverridingAbility();
        return source != null && source.isKeyword(Keyword.UNEARTH) && !replacement.isSuppressed()
                && replacement.getMode() == ReplacementType.Moved && ability != null
                && Set.of("DB", "Defined", "Origin", "Destination").containsAll(ability.getMapParams().keySet())
                && Set.of("Event", "ValidCard", "Origin", "ExcludeDestination", "Description").containsAll(replacement.getMapParams().keySet())
                && "Card.IsRemembered".equals(replacement.getParam("ValidCard"))
                && "Battlefield".equals(replacement.getParam("Origin"))
                && "Exile".equals(replacement.getParam("ExcludeDestination"))
                && ability.getApi() == forge.game.ability.ApiType.ChangeZone
                && "ReplacedCard".equals(ability.getParam("Defined"))
                && "Exile".equals(ability.getParam("Destination"));
    }

    static Optional<PreparedCombatDepartures> prepare(final Player observer, final BooleanSupplier checkpoint) {
        final Map<Integer, Departure> entries = new LinkedHashMap<>();
        final List<String> unsupported = new ArrayList<>();
        final List<String> future = new ArrayList<>();
        final var game = observer.getGame();
        final var phase = game.getPhaseHandler().getPhase();
        final List<Card> objects = game.getCardsIn(ZoneType.Battlefield).stream()
                .filter(card -> !card.isFaceDown() && !card.isPhasedOut() && card.isCreature() && card.isUnearthed()).toList();
        if (objects.isEmpty()) { return Optional.of(empty()); }
        boolean ordinaryBoundary = phase != null && phase.isBefore(PhaseType.END_OF_TURN)
                && game.getPhaseHandler().getNumEndOfTurn() == 0
                && !game.getPhaseHandler().getPlayerTurn().isPhasesReversed();
        for (final PhaseType after : PhaseType.values()) {
            if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
            for (final PhaseType extra : PhaseType.values()) {
                if (game.getPhaseHandler().hasExtraPhaseAfter(after, extra)) { ordinaryBoundary = false; }
            }
        }
        for (final Card source : game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
            // Do not inspect hidden faces/zones to predict interference.
            if ((!source.isInPlay() && !source.isInZone(ZoneType.Command)) || source.isFaceDown() || source.isPhasedOut()) { continue; }
            if (source.getReplacementEffects().stream().anyMatch(re -> !re.isSuppressed()
                    && (re.getMode() == ReplacementType.BeginPhase || re.getMode() == ReplacementType.BeginTurn))) {
                ordinaryBoundary = false;
            }
            for (final var replacement : source.getReplacementEffects()) {
                if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
                if (replacement.isSuppressed() || replacement.getMode() != ReplacementType.Moved
                        || standardReplacement(source, replacement) || !mayIncludeZone(replacement.getParam("Origin"), ZoneType.Battlefield)
                        || objects.stream().noneMatch(object -> replacement.matchesValidParam("ValidCard", object))) { continue; }
                final String excluded = replacement.getParamOrDefault("ExcludeDestination", "");
                if (mayIncludeZone(replacement.getParam("Destination"), ZoneType.Graveyard) && !"Graveyard".equals(excluded)) {
                    unsupported.add("Unearth combat exit has competing replacement ordering: " + source.getId());
                }
                if (mayIncludeZone(replacement.getParam("Destination"), ZoneType.Exile) && !"Exile".equals(excluded)) {
                    future.add("Scheduled exile has competing replacement ordering: " + source.getId());
                }
            }
            for (final var trigger : source.getTriggers()) {
                if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
                if (!trigger.isSuppressed() && (trigger.getMode() == forge.game.trigger.TriggerType.ChangesZone
                        || trigger.getMode() == forge.game.trigger.TriggerType.ChangesZoneAll)
                        && mayIncludeZone(trigger.getParam("Origin"), ZoneType.Battlefield)
                        && mayIncludeZone(trigger.getParam("Destination"), ZoneType.Exile)) {
                    final String valid = trigger.getParamOrDefault("ValidCard", trigger.getParamOrDefault("ValidCards", ""));
                    if (Set.of("Card.Self", "Creature.Self").contains(valid)
                            && objects.stream().noneMatch(object -> object.equalsWithGameTimestamp(source))) { continue; }
                    future.add("Scheduled exile needs leave-trigger projection: " + source.getId());
                }
            }
        }
        boolean relevantObject = false;
        for (final Card object : objects) {
            if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
            final var pending = game.getTriggerHandler().hasPendingUnearthExile(object, checkpoint);
            if (pending.isEmpty()) { return Optional.empty(); }
            relevantObject |= pending.orElseThrow();
            Card provider = null;
            boolean associatedEffect = false;
            for (final Card effect : game.getCardsIn(ZoneType.Command)) {
                if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
                if (effect.getEffectSourceAbility() == null || !effect.getEffectSourceAbility().isKeyword(Keyword.UNEARTH)) { continue; }
                boolean remembered = false;
                for (final Object item : effect.getRemembered()) {
                    if (item instanceof Card card && card.equalsWithGameTimestamp(object)) { remembered = true; break; }
                }
                associatedEffect |= remembered;
                if (remembered && effect.getReplacementEffects().stream().anyMatch(re -> standardReplacement(effect, re))) { provider = effect; break; }
            }
            relevantObject |= associatedEffect;
            if (provider == null) {
                // The engine marker can survive a zone change; it is not proof of an active effect
                // on the new battlefield object. Never transfer the old object's lifetime by ID.
                if (associatedEffect) { unsupported.add("Unearth object has an unadmitted active exile replacement: " + object.getId()); }
                else if (pending.orElseThrow()) {
                    future.add("Pending unearth exile lacks an admitted lifetime provider: " + object.getId());
                }
                continue;
            }
            final boolean expires = ordinaryBoundary && pending.orElseThrow();
            for (final var replacement : provider.getReplacementEffects()) {
                if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
                if (!standardReplacement(provider, replacement)) { continue; }
                final var cause = replacement.getOverridingAbility().copy(provider, provider.getController(), false, true);
                cause.setReplacementEffect(replacement);
                if (StaticAbilityCantExile.cantExile(object, cause, true)) {
                    unsupported.add("Unearth combat exile is restricted for object: " + object.getId());
                }
            }
            if (!expires) { future.add("Unearth departure timing is not established before next combat: " + object.getId()); }
            for (final var ability : object.getStaticAbilities()) {
                if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
                final boolean protectionKeyword = ability.getKeyword() != null && ability.getKeyword().getKeyword() == Keyword.PROTECTION;
                if (!ability.isSuppressed() && !forge.ai.effect.CombatStaticProjectionPreparation.supportsFixedPowerToughness(ability)
                        && !protectionKeyword && !CombatProtectionPreparation.isPersistentSelfGrant(ability) && !CombatDamagePreventionRule.supported(ability)) {
                    future.add("Expiring static source needs recipient projection: " + object.getId());
                }
            }
            if (object.getAttachedCards().stream().anyMatch(Card::isAura)) {
                future.add("Scheduled departure needs attached Aura loss projection: " + object.getId());
            }
            entries.put(object.getId(), new Departure(object.getGameTimestamp(), provider.getId(), provider.getGameTimestamp(),
                    Provenance.UNEARTH, Boundary.NEXT_END_STEP, ZoneType.Exile, expires));
        }
        // TODO: Other delayed exile/sacrifice/return families, skipped/extra end steps, temporary control,
        // and chronological second-main/end-step uses. Unknown timing is never a certain disappearance.
        return Optional.of(relevantObject ? new PreparedCombatDepartures(entries, unsupported, future) : empty());
    }
}
