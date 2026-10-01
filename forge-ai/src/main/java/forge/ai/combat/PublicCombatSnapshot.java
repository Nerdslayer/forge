package forge.ai.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import forge.ai.effect.ScheduledTriggerParser;
import forge.ai.effect.CombatTriggerDescription;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;

/** Public, frozen mechanics for one attack declaration; subsequent block search is game-free. */
public record PublicCombatSnapshot(int observingPlayerId, int attackingPlayerId, int defendingPlayerId,
        Map<Integer, Creature> creatures, Map<Integer, LifeState> players,
        Map<Integer, Integer> attackersToDefenders, Map<Integer, Set<Integer>> legalBlockers,
        List<String> unavailableReasons, List<String> unsupportedReasons, boolean legacyDamageOrder,
        Map<Integer, CombatPlayerResources> resources, List<CombatTriggerDescription> triggers,
        Set<Integer> observedAttackers, Set<Integer> observedBlockers, List<CombatDamagePreventionRule> preventionRules,
        PreparedCombatStaticWorlds staticWorlds) {
    public PublicCombatSnapshot {
        creatures = Map.copyOf(creatures);
        players = Map.copyOf(players);
        attackersToDefenders = Map.copyOf(attackersToDefenders);
        final Map<Integer, Set<Integer>> blocks = new LinkedHashMap<>();
        legalBlockers.forEach((id, group) -> blocks.put(id, Set.copyOf(group)));
        legalBlockers = Map.copyOf(blocks);
        unavailableReasons = List.copyOf(unavailableReasons);
        unsupportedReasons = List.copyOf(unsupportedReasons);
        resources = Map.copyOf(resources);
        triggers = List.copyOf(triggers);
        observedAttackers = Set.copyOf(observedAttackers);
        observedBlockers = Set.copyOf(observedBlockers);
        preventionRules = List.copyOf(preventionRules);
        if (staticWorlds == null) { throw new IllegalArgumentException("Explicit static coverage required"); }
    }

    public PublicCombatSnapshot(final int observingPlayerId, final int attackingPlayerId, final int defendingPlayerId,
            final Map<Integer, Creature> creatures, final Map<Integer, LifeState> players,
            final Map<Integer, Integer> attackersToDefenders, final Map<Integer, Set<Integer>> legalBlockers,
            final List<String> unavailableReasons, final List<String> unsupportedReasons, final boolean legacyDamageOrder,
            final Map<Integer, CombatPlayerResources> resources, final List<CombatTriggerDescription> triggers,
            final Set<Integer> observedAttackers, final Set<Integer> observedBlockers, final List<CombatDamagePreventionRule> preventionRules) {
        this(observingPlayerId, attackingPlayerId, defendingPlayerId, creatures, players, attackersToDefenders, legalBlockers,
                unavailableReasons, unsupportedReasons, legacyDamageOrder, resources, triggers, observedAttackers, observedBlockers,
                preventionRules, PreparedCombatStaticWorlds.empty());
    }

    public PublicCombatSnapshot(final int observingPlayerId, final int attackingPlayerId, final int defendingPlayerId,
            final Map<Integer, Creature> creatures, final Map<Integer, LifeState> players,
            final Map<Integer, Integer> attackersToDefenders, final Map<Integer, Set<Integer>> legalBlockers,
            final List<String> unavailableReasons, final List<String> unsupportedReasons, final boolean legacyDamageOrder,
            final Map<Integer, CombatPlayerResources> resources, final List<CombatTriggerDescription> triggers,
            final Set<Integer> observedAttackers, final Set<Integer> observedBlockers) {
        this(observingPlayerId, attackingPlayerId, defendingPlayerId, creatures, players, attackersToDefenders, legalBlockers,
                unavailableReasons, unsupportedReasons, legacyDamageOrder, resources, triggers, observedAttackers, observedBlockers, List.of());
    }

    public PublicCombatSnapshot(final int observingPlayerId, final int attackingPlayerId, final int defendingPlayerId,
            final Map<Integer, Creature> creatures, final Map<Integer, LifeState> players,
            final Map<Integer, Integer> attackersToDefenders, final Map<Integer, Set<Integer>> legalBlockers,
            final List<String> unavailableReasons, final List<String> unsupportedReasons, final boolean legacyDamageOrder) {
        this(observingPlayerId, attackingPlayerId, defendingPlayerId, creatures, players, attackersToDefenders,
                legalBlockers, unavailableReasons, unsupportedReasons, legacyDamageOrder, Map.of(), List.of(), Set.of(), Set.of());
    }

    public record Creature(int id, int controllerId, int combatDamage, int toughness,
            int markedDamage, boolean markedDeathtouch, boolean firstStrike, boolean doubleStrike,
            boolean deathtouch, boolean indestructible, boolean trample, boolean lifelink,
            boolean vigilance, boolean tapped, int minimumBlockers, int maximumBlockers, int stunCounters, int shieldCounters) {
        public Creature {
            if (minimumBlockers < 0 || maximumBlockers < 0) { throw new IllegalArgumentException("Nonnegative blocker bounds required"); }
            if (stunCounters < 0) { throw new IllegalArgumentException("Nonnegative stun count required"); }
            if (shieldCounters < 0) { throw new IllegalArgumentException("Nonnegative shield count required"); }
        }

        public Creature(final int id, final int controllerId, final int combatDamage, final int toughness,
                final int markedDamage, final boolean markedDeathtouch, final boolean firstStrike, final boolean doubleStrike,
                final boolean deathtouch, final boolean indestructible, final boolean trample, final boolean lifelink,
                final boolean vigilance, final boolean tapped, final int minimumBlockers, final int maximumBlockers, final int stunCounters) {
            this(id, controllerId, combatDamage, toughness, markedDamage, markedDeathtouch, firstStrike, doubleStrike,
                    deathtouch, indestructible, trample, lifelink, vigilance, tapped, minimumBlockers, maximumBlockers, stunCounters, 0);
        }

        public Creature(final int id, final int controllerId, final int combatDamage, final int toughness,
                final int markedDamage, final boolean markedDeathtouch, final boolean firstStrike, final boolean doubleStrike,
                final boolean deathtouch, final boolean indestructible, final boolean trample, final boolean lifelink,
                final boolean vigilance, final boolean tapped, final int minimumBlockers, final int maximumBlockers) {
            this(id, controllerId, combatDamage, toughness, markedDamage, markedDeathtouch, firstStrike, doubleStrike,
                    deathtouch, indestructible, trample, lifelink, vigilance, tapped, minimumBlockers, maximumBlockers, 0);
        }

        public Creature(final int id, final int controllerId, final int combatDamage, final int toughness,
                final int markedDamage, final boolean markedDeathtouch, final boolean firstStrike, final boolean doubleStrike,
                final boolean deathtouch, final boolean indestructible, final boolean trample, final boolean lifelink,
                final boolean vigilance, final boolean tapped) {
            this(id, controllerId, combatDamage, toughness, markedDamage, markedDeathtouch, firstStrike, doubleStrike,
                    deathtouch, indestructible, trample, lifelink, vigilance, tapped, 1, Integer.MAX_VALUE);
        }

        /** These bounds constrain declaration, not the surviving group after first strike. */
        public boolean permitsBlockerCount(final int count) {
            return count == 0 || count >= minimumBlockers && count <= maximumBlockers;
        }

        public Creature withShieldCounters(final int count) {
            return new Creature(id, controllerId, combatDamage, toughness, markedDamage, markedDeathtouch,
                    firstStrike, doubleStrike, deathtouch, indestructible, trample, lifelink, vigilance, tapped,
                    minimumBlockers, maximumBlockers, stunCounters, count);
        }

        public Creature withPowerToughness(final int damage, final int newToughness) {
            return new Creature(id, controllerId, damage, newToughness, markedDamage, markedDeathtouch,
                    firstStrike, doubleStrike, deathtouch, indestructible, trample, lifelink, vigilance, tapped,
                    minimumBlockers, maximumBlockers, stunCounters, shieldCounters);
        }
    }

    public Creature creatureAfterLosses(final int id, final Set<Integer> losses) {
        final Creature original = creatures.get(id);
        final var changed = staticWorlds.afterLosses(losses).get(id);
        return changed == null ? original : original.withPowerToughness(changed.combatDamage(), changed.toughness());
    }

    public record LifeState(int life, boolean canLoseLife, boolean canGainLife,
            boolean cannotLoseAtZero, boolean cannotWin) { }

    private static final Set<Keyword> SUPPORTED_KEYWORDS = Set.of(
            Keyword.FLYING, Keyword.REACH, Keyword.FIRST_STRIKE, Keyword.DOUBLE_STRIKE,
            Keyword.DEATHTOUCH, Keyword.INDESTRUCTIBLE, Keyword.TRAMPLE, Keyword.LIFELINK,
            Keyword.VIGILANCE, Keyword.HASTE, Keyword.DEFENDER, Keyword.HEXPROOF, Keyword.SHROUD, Keyword.MENACE);

    /** Capture reads only public zones/current characteristics, never a controller's predictions. */
    public static PublicCombatSnapshot capture(final Player observer, final Combat declaration) {
        return capture(observer, declaration, false);
    }

    /** Revalidation only: does not admit new search decisions after combat damage has started. */
    static PublicCombatSnapshot captureForExecution(final Player observer, final Combat declaration) {
        return capture(observer, declaration, true);
    }

    /** Cancellation never publishes a partial mechanical/static snapshot. */
    public static Optional<PublicCombatSnapshot> capture(final Player observer, final Combat declaration,
            final BooleanSupplier checkpoint) {
        return Optional.ofNullable(capture(observer, declaration, false, checkpoint));
    }

    static Optional<PublicCombatSnapshot> captureForExecution(final Player observer, final Combat declaration,
            final BooleanSupplier checkpoint) {
        return Optional.ofNullable(capture(observer, declaration, true, checkpoint));
    }

    private static PublicCombatSnapshot capture(final Player observer, final Combat declaration, final boolean execution) {
        return capture(observer, declaration, execution, () -> true);
    }

    private static PublicCombatSnapshot capture(final Player observer, final Combat declaration, final boolean execution,
            final BooleanSupplier checkpoint) {
        if (checkpoint == null) { throw new IllegalArgumentException("An explicit capture checkpoint is required"); }
        if (observer == null || declaration == null || observer.getGame() != declaration.getAttackingPlayer().getGame()) {
            throw new IllegalArgumentException("An observer and declaration in the same game are required");
        }
        if (!checkpoint.getAsBoolean()) { return null; }
        final List<String> reasons = new ArrayList<>();
        final List<String> unavailable = new ArrayList<>();
        final Map<Integer, Creature> creatures = new LinkedHashMap<>();
        final Map<Integer, LifeState> players = new LinkedHashMap<>();
        final Map<Integer, Integer> attacks = new LinkedHashMap<>();
        final Map<Integer, Set<Integer>> blocks = new LinkedHashMap<>();
        final Player attacking = declaration.getAttackingPlayer();
        Player defending = null;
        for (final Player player : observer.getGame().getPlayers()) {
            if (!checkpoint.getAsBoolean()) { return null; }
            players.put(player.getId(), new LifeState(player.getLife(), player.canLoseLife(), player.canGainLife(),
                    player.cantLoseForZeroOrLessLife(), player.cantWin()));
            if (player != attacking) { defending = player; }
        }
        if (players.size() != 2 || defending == null || !attacking.isOpponentOf(defending)) {
            reasons.add("Only opposing two-player combat is supported");
        }
        if (!observer.getGame().getStack().isEmpty()) { reasons.add("Unresolved stack effects"); }
        if (!execution && observer.getGame().getPhaseHandler().getPhase().isAfter(PhaseType.COMBAT_DECLARE_BLOCKERS)) {
            reasons.add("Projection must begin before combat damage");
        }
        for (final Player player : observer.getGame().getPlayers()) {
            if (player.getPreventNextDamageTotalShields() > 0 || player.cantWin()) {
                reasons.add("Unprojected player prevention or win restriction: " + player.getId());
            }
        }
        for (final Card card : observer.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            if (!checkpoint.getAsBoolean()) { return null; }
            if (card.isPhasedOut()) { continue; }
            auditPublicEffects(card, reasons);
            if (card.isInPlay() && card.isCreature()) {
                final Player recipient = observer.getGame().getPlayers().stream()
                        .filter(player -> player.isOpponentOf(card.getController())).findFirst().orElse(null);
                final var bounds = card.isFaceDown() ? org.apache.commons.lang3.tuple.Pair.of(1, Integer.MAX_VALUE)
                        : forge.game.staticability.StaticAbilityCantAttackBlock.getMinMaxBlocker(card, recipient);
                creatures.put(card.getId(), new Creature(card.getId(), card.getController().getId(),
                        Math.max(0, card.getNetCombatDamage()), card.getNetToughness(), card.getDamage(),
                        card.hasBeenDealtDeathtouchDamage(), card.hasFirstStrike(), card.hasDoubleStrike(),
                        card.hasKeyword(Keyword.DEATHTOUCH), card.hasKeyword(Keyword.INDESTRUCTIBLE),
                        card.hasKeyword(Keyword.TRAMPLE), card.hasKeyword(Keyword.LIFELINK),
                        card.attackVigilance(), card.isTapped(), Math.max(0, bounds.getLeft()), Math.max(0, bounds.getRight()),
                        card.getCounters(CounterEnumType.STUN), card.getCounters(CounterEnumType.SHIELD)));
                if (card.getNetToughness() <= 0 || !card.hasKeyword(Keyword.INDESTRUCTIBLE)
                        && (card.getDamage() >= card.getNetToughness() || card.hasBeenDealtDeathtouchDamage())) {
                    reasons.add("Pending state-based creature loss: " + card.getId());
                }
            }
        }
        // An already declared live attack may have tapped attackers. Proposed detached attacks
        // must pass engine declaration validation before their legality is frozen.
        if (declaration != observer.getGame().getPhaseHandler().getCombat()) {
            // Combat caches constraints at construction. Refresh on a detached declaration so
            // adding creatures since construction cannot make an illegal proposal look legal.
            final Combat currentDeclaration = new Combat(attacking);
            for (final Card card : declaration.getAttackers()) {
                if (!checkpoint.getAsBoolean()) { return null; }
                currentDeclaration.addAttackerForValidation(card, declaration.getDefenderByAttacker(card));
            }
            if (!CombatUtil.validateAttackers(currentDeclaration)) { unavailable.add("Invalid attack declaration"); }
        }
        for (final Card attacker : declaration.getAttackers()) {
            if (!checkpoint.getAsBoolean()) { return null; }
            if (defending == null || declaration.getDefenderByAttacker(attacker) != defending
                    || attacker.getController() != attacking || !creatures.containsKey(attacker.getId())) {
                reasons.add("Only creature attacks against the opposing player are supported");
                continue;
            }
            attacks.put(attacker.getId(), defending.getId());
            if (!execution && declaration.isBlocked(attacker) && declaration.getBlockers(attacker).isEmpty()) {
                reasons.add("Previously blocked attacker with no remaining blocker: " + attacker.getId());
            }
            if (CombatUtil.getAttackCost(observer.getGame(), attacker, defending) != null) {
                reasons.add("Unprojected attack payment: " + attacker.getId());
            }
            final Set<Integer> eligible = new LinkedHashSet<>();
            for (final Card blocker : defending.getCreaturesInPlay()) {
                if (!checkpoint.getAsBoolean()) { return null; }
                // Pair eligibility and group-size legality are different. In particular, a
                // creature may be a legal member of a menace gang but not a legal solo block.
                if (CombatUtil.canBlock(attacker, blocker)) {
                    if (CombatUtil.getBlockCost(observer.getGame(), blocker, attacker) != null) {
                        reasons.add("Unprojected block payment: " + blocker.getId());
                    } else { eligible.add(blocker.getId()); }
                }
                if (!blocker.getMustBlockCards().isEmpty()) { reasons.add("Unprojected required block: " + blocker.getId()); }
            }
            blocks.put(attacker.getId(), eligible);
        }
        // TODO: Capture richer whole-declaration constraints and planeswalker destinations,
        // special group restrictions, static recipient transitions, concrete triggers and prevention/replacements.
        final Map<Integer, CombatPlayerResources> resources = new LinkedHashMap<>();
        for (final Player player : observer.getGame().getPlayers()) {
            resources.put(player.getId(), new CombatPlayerResources(player.getCardsIn(ZoneType.Hand).size(),
                    player.getCardsIn(ZoneType.Library).size()));
        }
        final List<CombatTriggerDescription> triggers = new ArrayList<>();
        final List<CombatDamagePreventionRule> preventionRules = new ArrayList<>();
        for (final Card card : observer.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            if (!checkpoint.getAsBoolean()) { return null; }
            if (card.isPhasedOut() || card.isFaceDown()) { continue; }
            card.getStaticAbilities().forEach(ability -> CombatDamagePreventionRule.parse(ability).ifPresent(preventionRules::add));
        }
        for (final Card card : observer.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (!checkpoint.getAsBoolean()) { return null; }
            if (card.isPhasedOut() || card.isFaceDown()) { continue; }
            card.getTriggers().forEach(trigger -> CombatTriggerDescription.parse(trigger).ifPresent(triggers::add));
        }
        // A fixed declaration on the real combat has already generated its triggers. Additional
        // detached alternatives must not redraw those cards from the now-updated hand/library.
        final Combat live = observer.getGame().getPhaseHandler().getCombat();
        final Set<Integer> observedAttacks = new LinkedHashSet<>();
        final Set<Integer> observedBlocks = new LinkedHashSet<>();
        if (live != null && live.getAttackingPlayer() == attacking) {
            live.getAttackers().forEach(card -> observedAttacks.add(card.getId()));
            live.getAllBlockers().forEach(card -> observedBlocks.add(card.getId()));
        }
        PreparedCombatStaticWorlds staticWorlds = PreparedCombatStaticWorlds.empty();
        final boolean needsStaticWorld = observer.getGame().getCardsIn(ZoneType.Battlefield).stream()
                .filter(card -> !card.isFaceDown() && !card.isPhasedOut())
                .anyMatch(card -> card.getStaticAbilities().stream().anyMatch(ability -> !ability.isSuppressed()
                        && forge.ai.effect.CombatStaticProjectionPreparation.supportsFixedPowerToughness(ability)));
        if (needsStaticWorld) {
            final var prepared = forge.ai.effect.CombatStaticProjectionPreparation.prepare(observer, checkpoint);
            if (prepared.isEmpty()) { return null; }
            staticWorlds = prepared.orElseThrow();
            if (!staticWorlds.supported()) { reasons.addAll(staticWorlds.reasons()); }
        }
        if (!checkpoint.getAsBoolean()) { return null; }
        return new PublicCombatSnapshot(observer.getId(), attacking.getId(), defending == null ? -1 : defending.getId(),
                creatures, players, attacks, blocks, unavailable, reasons, observer.getGame().getRules().hasOrderCombatants(),
                resources, triggers, observedAttacks, observedBlocks, preventionRules, staticWorlds);
    }

    private static void auditPublicEffects(final Card card, final List<String> reasons) {
        if (card.isFaceDown()) {
            // Never inspect the underlying face/activation costs to decide whether a hidden
            // combat trick exists. TODO: Admit known public face-down mechanics explicitly.
            reasons.add("Face-down combat mechanics need public-only handling: " + card.getId());
            return;
        }
        if (card.getPreventNextDamageTotalShields() > 0 || card.isCommander() || card.isGoaded()) {
            reasons.add("Unprojected prevention, commander damage or goad: " + card.getId());
        }
        for (final var keyword : card.getKeywords()) {
            if (!SUPPORTED_KEYWORDS.contains(keyword.getKeyword())) {
                reasons.add("Unprojected public keyword: " + keyword.getKeyword() + " on " + card.getId());
            }
        }
        for (final var entry : card.getCounters().entrySet()) {
            if (entry.getElement() != CounterEnumType.P1P1 && entry.getElement() != CounterEnumType.M1M1
                    && entry.getElement() != CounterEnumType.STUN && entry.getElement() != CounterEnumType.SHIELD) {
                reasons.add("Unprojected counter mechanic: " + entry.getElement() + " on " + card.getId());
            }
        }
        for (final var ability : card.getSpellAbilities()) {
            if (ability.isActivatedAbility() && !ability.isManaAbility()) {
                // TODO: Distinguish irrelevant activations and project affordable public combat
                // abilities with coupled state changes, repeated uses and a shared mana budget.
                reasons.add("Unprojected public activation: " + card.getId());
            }
        }
        for (final var trigger : card.getTriggers()) {
            if (!trigger.isSuppressed() && trigger.zonesCheck(card.getZone())
                    && ScheduledTriggerParser.parse(trigger.getMapParams()).isEmpty()
                    && CombatTriggerDescription.parse(trigger).isEmpty()) {
                reasons.add("Unprojected public trigger: " + trigger.getMode() + " on " + card.getId());
            }
        }
        for (final var ability : card.getStaticAbilities()) {
            if (!ability.isSuppressed() && (ability.getKeyword() == null
                    || !SUPPORTED_KEYWORDS.contains(ability.getKeyword().getKeyword())) && !fixedSelfBlockerBounds(ability)
                    && !CombatDamagePreventionRule.supported(ability)
                    && !forge.ai.effect.CombatStaticProjectionPreparation.supportsFixedPowerToughness(ability)) {
                reasons.add("Unprojected static effect: " + card.getId());
            }
        }
        for (final var ability : card.getHiddenStaticAbilities()) {
            if (!ability.isSuppressed()) { reasons.add("Unprojected hidden static effect: " + card.getId()); }
        }
        if (card.getReplacementEffects().stream().anyMatch(effect -> !fixedSelfStunReplacement(card, effect)
                && !fixedSelfShieldReplacement(card, effect))) {
            reasons.add("Unprojected replacement: " + card.getId());
        }
    }

    private static boolean fixedSelfShieldReplacement(final Card card,
            final forge.game.replacement.ReplacementEffect effect) {
        final var params = effect.getMapParams();
        final var outcome = effect.getOverridingAbility();
        final boolean damage = "DamageDone".equals(params.get("Event"))
                && "Card.Self".equals(params.get("ValidTarget")) && "True".equals(params.get("PreventionEffect"))
                && "True".equals(params.get("AlwaysReplace"))
                && Set.of("Event", "ActiveZones", "ValidTarget", "PreventionEffect", "AlwaysReplace", "Secondary", "Description")
                        .containsAll(params.keySet());
        final boolean destroy = "Destroy".equals(params.get("Event")) && "Card.Self".equals(params.get("ValidCard"))
                && "SpellAbility".equals(params.get("ValidCause")) && "True".equals(params.get("ShieldCounter"))
                && Set.of("Event", "ActiveZones", "ValidCard", "ValidCause", "ShieldCounter", "Secondary", "Description")
                        .containsAll(params.keySet());
        // TODO: Other prevention/replacement effects and shield-dependent statics need their
        // own audited transitions. Shield counters do not rescue an already pending SBA death.
        return card.isCreature() && card.getCounters(CounterEnumType.SHIELD) > 0 && !effect.isSuppressed()
                && "Battlefield".equals(params.get("ActiveZones")) && "True".equals(params.get("Secondary"))
                && (damage || destroy) && outcome != null && outcome.getApi() == forge.game.ability.ApiType.RemoveCounter
                && outcome.getSubAbility() == null && !outcome.usesTargeting()
                && "Self".equals(outcome.getParam("Defined")) && "Shield".equals(outcome.getParam("CounterType"))
                && "1".equals(outcome.getParam("CounterNum"))
                && Set.of("DB", "Defined", "CounterType", "CounterNum", "SpellDescription")
                        .containsAll(outcome.getMapParams().keySet());
    }

    private static boolean fixedSelfStunReplacement(final Card card,
            final forge.game.replacement.ReplacementEffect effect) {
        final var params = effect.getMapParams();
        final var outcome = effect.getOverridingAbility();
        // Only the engine's counter-generated self untap replacement is mechanically admitted.
        // TODO: General untap replacements, optional untaps and counter-changing triggers need
        // chronological event projection rather than ignoring all replacement text on this card.
        return card.isCreature() && card.getCounters(CounterEnumType.STUN) > 0
                && !effect.isSuppressed() && "Untap".equals(params.get("Event"))
                && "Battlefield".equals(params.get("ActiveZones")) && "Card.Self".equals(params.get("ValidCard"))
                && "True".equals(params.get("Secondary"))
                && Set.of("Event", "ActiveZones", "ValidCard", "Secondary", "Description").containsAll(params.keySet())
                && outcome != null && outcome.getApi() == forge.game.ability.ApiType.RemoveCounter
                && outcome.getSubAbility() == null && !outcome.usesTargeting()
                && "Self".equals(outcome.getParam("Defined")) && "Stun".equals(outcome.getParam("CounterType"))
                && "1".equals(outcome.getParam("CounterNum"))
                && Set.of("DB", "Defined", "CounterType", "CounterNum", "SpellDescription")
                        .containsAll(outcome.getMapParams().keySet());
    }

    private static boolean fixedSelfBlockerBounds(final forge.game.staticability.StaticAbility ability) {
        final var params = ability.getMapParams();
        // TODO: Conditional, all-recipient and expression/"All" blocker bounds need survivor
        // static recalculation. Only unconditional fixed bounds on the source survive unchanged.
        return "MinMaxBlocker".equals(params.get("Mode"))
                && Set.of("Card.Self", "Creature.Self").contains(params.getOrDefault("ValidCard", ""))
                && Set.of("Mode", "ValidCard", "Min", "Max", "Description", "Secondary").containsAll(params.keySet())
                && (params.containsKey("Min") || params.containsKey("Max"))
                && params.getOrDefault("Min", "1").matches("[0-9]{1,4}")
                && params.getOrDefault("Max", "1").matches("[0-9]{1,4}");
    }
}
