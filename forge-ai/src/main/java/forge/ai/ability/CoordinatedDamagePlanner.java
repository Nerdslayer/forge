package forge.ai.ability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.ai.ComputerUtil;
import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilCombat;
import forge.ai.ComputerUtilCost;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CounterEnumType;
import forge.game.cost.CostTap;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMustTarget;
import forge.game.zone.ZoneType;

import org.tinylog.Logger;

/** Bounded lethal fallback for fixed damage activations with only a source-tap cost. */
public final class CoordinatedDamagePlanner {
    private static final int MAX_ABILITIES = 64;
    private static final int MAX_TARGETS = 32;
    private static final Set<String> SIMPLE_PARAMS = Set.of("AB", "Cost", "NumDmg", "ValidTgts",
            "TgtPrompt", "SpellDescription", "Description", "ActivationZone");

    // Store only target intent, never mutable ability targets or an executable future action list.
    // Every call rebuilds a lethal remainder against the actual board after costs/resolution.
    private Card preferredTarget;
    private long preferredTimestamp;
    private PhaseType preferredPhase;
    private int preferredTurn;

    private record Ping(SpellAbility ability, int damage) { }
    private record Group(Card target, List<Ping> pings, int damage) { }

    /** Sets only the current activation's target, and only after proving a payable lethal group. */
    public boolean prepare(final Player ai, final SpellAbility current) {
        if (!AiProfileUtil.getBoolProperty(ai, AiProps.ENABLE_COORDINATED_TAP_DAMAGE)) {
            clear("profile_disabled");
            return false;
        }
        if (!ai.getGame().getStack().isEmpty()) {
            return false;
        }
        final SpellAbility first = availableCopy(ai, current);
        if (first == null || hasUnmodeledInteractions(ai)) {
            clear("activation_unavailable_or_interaction_unmodeled");
            return false;
        }
        final List<SpellAbility> available = new ArrayList<>();
        for (final Card source : ai.getCardsIn(ZoneType.Battlefield)) {
            for (final SpellAbility ability : source.getSpellAbilities()) {
                final SpellAbility copy = availableCopy(ai, ability);
                if (copy != null) {
                    available.add(copy);
                    if (available.size() > MAX_ABILITIES) {
                        clear("ability_limit");
                        return false;
                    }
                }
            }
        }
        final CardCollection targets = new CardCollection();
        for (final Player opponent : ai.getOpponents()) {
            for (final Card target : opponent.getCreaturesInPlay()) {
                if (first.canTarget(target) && safeTarget(ai, target)) {
                    targets.add(target);
                }
            }
        }
        if (targets.size() > MAX_TARGETS) {
            clear("target_limit");
            return false;
        }
        StaticAbilityMustTarget.filterMustTargetCards(ai, targets, first);
        final Map<Card, Group> groups = new LinkedHashMap<>();
        for (final Card target : targets) {
            final Group group = lethalGroup(ai, first, available, target);
            if (group != null && (group.pings().size() > 1 || isPreferredTarget(ai, target))) {
                groups.put(target, group);
            }
        }
        final boolean continuing = isPreferredTarget(ai, preferredTarget)
                && groups.containsKey(preferredTarget);
        final Card selected = continuing ? preferredTarget
                : ComputerUtilCard.getBestRemovalTargetAI(ai, groups.keySet(), current);
        if (selected == null) {
            clear("no_feasible_lethal_remainder");
            return false;
        }
        preferredTarget = selected;
        preferredTimestamp = selected.getGameTimestamp();
        preferredPhase = ai.getGame().getPhaseHandler().getPhase();
        preferredTurn = ai.getGame().getPhaseHandler().getTurn();
        current.resetTargets();
        current.getTargets().add(selected);
        if (Boolean.parseBoolean(System.getProperty("forge.ai.effectAnalysisTrace", "true"))) {
            final Group group = groups.get(selected);
            Logger.info("[AI Effect Analysis] Coordinated tap damage: source={}, target={}, "
                    + "activations={}, predictedDamage={}, continuing={}", current.getHostCard(),
                    selected, group.pings().stream().map(p -> p.ability().getHostCard().getName()
                            + "#" + p.ability().getHostCard().getId()).toList(), group.damage(), continuing);
        }
        return true;
    }

    private boolean isPreferredTarget(final Player ai, final Card target) {
        return target != null && target == preferredTarget
                && target.getGameTimestamp() == preferredTimestamp
                && ai.getGame().getPhaseHandler().getPhase() == preferredPhase
                && ai.getGame().getPhaseHandler().getTurn() == preferredTurn;
    }

    private static SpellAbility availableCopy(final Player ai, final SpellAbility ability) {
        // TODO: Support mana-paying groups, spells, repeatable activations, additional costs,
        // complex targeting/subabilities and projected damage-triggered changes separately.
        if (ability == null || !ability.isActivatedAbility() || ability.getApi() != ApiType.DealDamage
                || ability.getSubAbility() != null || ability.getParent() != null
                || !ability.usesTargeting() || ability.isDividedAsYouChoose()
                || !SIMPLE_PARAMS.containsAll(ability.getMapParams().keySet())
                || !ability.getParamOrDefault("NumDmg", "").matches("[1-9][0-9]{0,3}")
                || ability.getPayCosts() == null
                || ability.getPayCosts().getCostParts().size() != 1
                || !(ability.getPayCosts().getCostParts().get(0) instanceof CostTap)) {
            return null;
        }
        final Card source = ability.getHostCard();
        if (source.getController() != ai || !source.isInPlay() || source.isPhasedOut()
                || source.isTapped() || source.isWitherDamage() || source.hasKeyword(Keyword.DEATHTOUCH)
                || source.hasKeyword(Keyword.LIFELINK)) {
            return null;
        }
        final SpellAbility copy = ability.copy(source, ai, false);
        copy.setActivatingPlayer(ai);
        copy.resetTargets();
        if (copy.getMinTargets() != 1 || copy.getMaxTargets() != 1 || !copy.canPlay()
                || !copy.canCastTiming(ai) || !ComputerUtilCost.canPayCost(copy, ai, false)) {
            return null;
        }
        return copy;
    }

    private static boolean safeTarget(final Player ai, final Card target) {
        return !target.isPhasedOut() && !target.hasKeyword(Keyword.INDESTRUCTIBLE)
                && target.getCounters(CounterEnumType.SHIELD) == 0 && target.getShieldCount() == 0
                && target.getPreventNextDamageTotalShields() == 0
                && !target.hasSVar("DestroyWhenDamaged") && !ComputerUtil.canRegenerate(ai, target)
                && ComputerUtilCombat.getDamageToKill(target, false) > 0;
    }

    private static Group lethalGroup(final Player ai, final SpellAbility first,
            final List<SpellAbility> available, final Card target) {
        final int firstDamage = damage(first, target);
        if (firstDamage <= 0) {
            return null;
        }
        final Map<Card, Ping> partners = new LinkedHashMap<>();
        for (final SpellAbility ability : available) {
            final Card source = ability.getHostCard();
            if (source == first.getHostCard() || !ability.canTarget(target)) {
                continue;
            }
            final CardCollection legal = new CardCollection();
            for (final Player opponent : ai.getOpponents()) {
                for (final Card creature : opponent.getCreaturesInPlay()) {
                    if (ability.canTarget(creature)) {
                        legal.add(creature);
                    }
                }
            }
            StaticAbilityMustTarget.filterMustTargetCards(ai, legal, ability);
            if (!legal.contains(target)) {
                continue;
            }
            final int predicted = damage(ability, target);
            final Ping existing = partners.get(source);
            if (predicted > 0 && (existing == null || predicted > existing.damage())) {
                partners.put(source, new Ping(ability, predicted));
            }
        }
        final List<Ping> ordered = new ArrayList<>(partners.values());
        // With independent positive damage and identical tap costs, descending damage gives the
        // smallest group anchored to the current source. No combinatorial action search is needed.
        ordered.sort(Comparator.comparingInt(Ping::damage).reversed()
                .thenComparingInt(p -> ComputerUtilCard.evaluatePermanent(ai, p.ability().getHostCard()))
                .thenComparingInt(p -> p.ability().getHostCard().getId()));
        final List<Ping> used = new ArrayList<>();
        used.add(new Ping(first, firstDamage));
        int total = firstDamage;
        final int needed = ComputerUtilCombat.getDamageToKill(target, false);
        for (final Ping partner : ordered) {
            if (total >= needed) {
                break;
            }
            used.add(partner);
            total += partner.damage();
        }
        return total >= needed ? new Group(target, List.copyOf(used), total) : null;
    }

    private static int damage(final SpellAbility ability, final Card target) {
        return ComputerUtilCombat.predictDamageTo(target, Integer.parseInt(ability.getParam("NumDmg")),
                ability.getHostCard(), false);
    }

    private static boolean hasUnmodeledInteractions(final Player ai) {
        // TODO: Match relevance and project replacements/triggers instead of rejecting a board
        // with any such public interaction. Independent pings are not a full resolution simulator.
        for (final ZoneType zone : List.of(ZoneType.Battlefield, ZoneType.Command)) {
            for (final Card card : ai.getGame().getCardsIn(zone)) {
                if (card.isPhasedOut() || card.isFaceDown()) {
                    continue;
                }
                if (card.getReplacementEffects().stream().anyMatch(r -> r.getMode() == ReplacementType.DamageDone
                        || r.getMode() == ReplacementType.DealtDamage || r.getMode() == ReplacementType.AssignDealDamage
                        || r.getMode() == ReplacementType.Tap)) {
                    return true;
                }
                // Tapping or marking damage may change conditional static characteristics
                // before the remainder resolves. Do not project those changes optimistically.
                if (card.getStaticAbilities().stream().anyMatch(s -> s.getMapParams().entrySet().stream()
                        .anyMatch(e -> e.getKey().startsWith("Condition") || e.getKey().startsWith("CheckSVar")
                                || e.getKey().startsWith("IsPresent")
                                || e.getValue().matches("(?is).*(tapped|damaged|dealtdamage).*")))) {
                    return true;
                }
                if (card.getTriggers().stream().anyMatch(t -> t.getMode().name().startsWith("Damage")
                        || t.getMode().name().startsWith("ExcessDamage")
                        || Set.of("Taps", "TapAll", "BecomesTarget", "BecomesTargetOnce", "AbilityCast",
                                "SpellAbilityCast", "AbilityResolves").contains(t.getMode().name()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void clear(final String reason) {
        if (preferredTarget != null
                && Boolean.parseBoolean(System.getProperty("forge.ai.effectAnalysisTrace", "true"))) {
            Logger.info("[AI Effect Analysis] Coordinated tap damage canceled: target={}, reason={}",
                    preferredTarget, reason);
        }
        preferredTarget = null;
    }
}
