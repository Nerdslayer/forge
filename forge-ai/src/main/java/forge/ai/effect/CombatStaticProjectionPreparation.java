package forge.ai.effect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import forge.ai.combat.PreparedCombatStaticWorlds;
import forge.card.CardType;
import forge.game.StaticEffect;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.player.Player;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.zone.ZoneType;

/** Public preparation only; combined copies reuse the removal analyzer's tracked-change primitive. */
public final class CombatStaticProjectionPreparation {
    private static final int MAX_PROVIDERS = 4;
    private CombatStaticProjectionPreparation() { }

    private record Tracked(StaticAbility ability, StaticEffect effect, Set<Integer> recipients) { }

    /** Only tracked permanent boosts are safe to retain across cleanup; temporary pumps are not. */
    public static boolean hasOnlyPersistentBoosts(final Card card) {
        for (final var cell : card.getPTBoostTable().cellSet()) {
            boolean recognized = false;
            for (final Card source : card.getGame().getCardsIn(ZoneType.Battlefield)) {
                if (source.isFaceDown() || source.isPhasedOut()) { continue; }
                for (final StaticAbility ability : source.getStaticAbilities()) {
                    if (ability.isSuppressed() || !supportsFixedPowerToughness(ability)) { continue; }
                    final StaticEffect effect = card.getGame().getStaticEffects().findStaticEffect(ability);
                    if (effect != null && effect.getTimestamp() == cell.getRowKey()
                            && ability.getId() == cell.getColumnKey() && effect.getAffectedCards().contains(card)) {
                        recognized = true;
                    }
                }
            }
            if (!recognized) { return false; }
        }
        return true;
    }

    public static boolean supportsFixedPowerToughness(final StaticAbility ability) {
        final var params = ability.getMapParams();
        if (!"Continuous".equals(params.get("Mode"))
                || !Set.of("Mode", "Affected", "AddPower", "AddToughness", "Description", "Secondary", "EffectZone")
                        .containsAll(params.keySet())
                || !"Battlefield".equals(params.getOrDefault("EffectZone", "Battlefield"))
                || !params.containsKey("AddPower") && !params.containsKey("AddToughness")
                || !params.getOrDefault("AddPower", "0").matches("-?[0-9]{1,4}")
                || !params.getOrDefault("AddToughness", "0").matches("-?[0-9]{1,4}")) { return false; }
        final String affected = params.getOrDefault("Affected", "");
        if (Set.of("Card.Self", "Creature.Self").contains(affected)) { return true; }
        if (!affected.startsWith("Creature")) { return false; }
        if ("Creature".equals(affected)) { return true; }
        if (!affected.startsWith("Creature.")) { return false; }
        // Controller/identity and positive creature subtypes are invariant in this P/T-only
        // domain: type-changing layers are rejected before any world is published.
        // TODO: Other type predicates, supported keywords, dynamic amounts, attachments, hints,
        // other characteristics and conditions need dependency/layer-aware applicability checks.
        final String[] filters = affected.substring("Creature.".length()).split("\\+", -1);
        for (final String filter : filters) {
            if (!Set.of("YouCtrl", "OppCtrl", "Other").contains(filter) && !CardType.isACreatureType(filter)) { return false; }
        }
        return true;
    }

    public static Optional<PreparedCombatStaticWorlds> prepare(final Player observer, final BooleanSupplier checkpoint) {
        if (observer == null || checkpoint == null) { throw new IllegalArgumentException("Observer and checkpoint required"); }
        if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
        final List<Card> creatures = new ArrayList<>();
        final Map<Integer, List<Tracked>> byProvider = new LinkedHashMap<>();
        for (final Card source : observer.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
            if (source.isPhasedOut()) { continue; }
            if (source.isFaceDown()) { return Optional.of(PreparedCombatStaticWorlds.unsupported("Hidden static source or recipient")); }
            if (source.isCreature()) { creatures.add(source); }
            for (final StaticAbility ability : source.getStaticAbilities()) {
                if (ability.isSuppressed() || !"Continuous".equals(ability.getMapParams().get("Mode"))) { continue; }
                if (!supportsFixedPowerToughness(ability)) {
                    return Optional.of(PreparedCombatStaticWorlds.unsupported("Static applicability/layers need projection: " + source.getId()));
                }
                if (!ability.checkConditions(StaticAbilityMode.Continuous)) { continue; }
                final var effect = source.getGame().getStaticEffects().findStaticEffect(ability);
                if (effect == null) {
                    boolean hasRecipient = false;
                    for (final Card candidate : observer.getGame().getCardsIn(ZoneType.Battlefield)) {
                        if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
                        if (!candidate.isFaceDown() && !candidate.isPhasedOut() && candidate.isCreature()
                                && candidate.isValid(ability.getMapParams().get("Affected"), source.getController(), source, ability)) {
                            hasRecipient = true;
                        }
                    }
                    // Forge need not track an empty layer. Its absence is safe only when
                    // the audited invariant predicate has no current combat recipients.
                    if (!hasRecipient) {
                        // Preserve source identity even if its final recipient just died: fresh
                        // execution capture must agree with the rebased frozen provider domain.
                        byProvider.computeIfAbsent(source.getId(), key -> new ArrayList<>());
                        continue;
                    }
                }
                if (effect == null || effect.getTimestamp() < 0) {
                    return Optional.of(PreparedCombatStaticWorlds.unsupported("Static tracked changes are not current: " + source.getId()));
                }
                final Set<Integer> recipients = new LinkedHashSet<>();
                for (final Card card : effect.getAffectedCards()) {
                    if (card.isInPlay() && card.isCreature()) { recipients.add(card.getId()); }
                }
                byProvider.computeIfAbsent(source.getId(), key -> new ArrayList<>()).add(new Tracked(ability, effect, recipients));
            }
            if (source.getHiddenStaticAbilities().stream().anyMatch(ability -> !ability.isSuppressed())) {
                return Optional.of(PreparedCombatStaticWorlds.unsupported("Hidden static layers need projection: " + source.getId()));
            }
        }
        if (byProvider.size() > MAX_PROVIDERS) {
            return Optional.of(PreparedCombatStaticWorlds.unsupported("Combined static source-loss preparation exceeds four providers"));
        }
        final List<Integer> providers = byProvider.keySet().stream().sorted().toList();
        final Map<Integer, Integer> originalValues = new LinkedHashMap<>();
        for (final Card creature : creatures) {
            if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
            originalValues.put(creature.getId(), UnifiedPermanentValueEvaluator.evaluate(observer, creature));
        }
        final Map<Set<Integer>, Map<Integer, PreparedCombatStaticWorlds.CreatureState>> worlds = new LinkedHashMap<>();
        for (int mask = 0; mask < (1 << providers.size()); mask++) {
            if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
            final Set<Integer> losses = new LinkedHashSet<>();
            for (int bit = 0; bit < providers.size(); bit++) {
                if ((mask & (1 << bit)) != 0) { losses.add(providers.get(bit)); }
            }
            final Map<Integer, PreparedCombatStaticWorlds.CreatureState> world = new LinkedHashMap<>();
            for (final Card creature : creatures) {
                if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
                final Card projected = CardCopyService.getLKICopy(creature);
                if (creature.getZone() != null) { projected.setZone(creature.getZone()); }
                for (final int provider : losses) {
                    for (final Tracked change : byProvider.get(provider)) {
                        if (change.recipients().contains(creature.getId())) {
                            StaticAbilityAnalyzer.removeTrackedChanges(projected, change.effect(), change.ability());
                        }
                    }
                }
                world.put(creature.getId(), new PreparedCombatStaticWorlds.CreatureState(projected.getNetPower(),
                        projected.getNetToughness(), Math.max(0, projected.getNetCombatDamage()),
                        EffectMath.subtract(UnifiedPermanentValueEvaluator.evaluate(observer, projected), originalValues.get(creature.getId()))));
            }
            worlds.put(Set.copyOf(losses), world);
        }
        if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
        // TODO: Extend the bounded combined worlds to other characteristic layers only when
        // strike mechanics, state-based actions, survivor values and execution agree on them.
        return Optional.of(new PreparedCombatStaticWorlds(true, byProvider.keySet(), worlds, List.of()));
    }
}
