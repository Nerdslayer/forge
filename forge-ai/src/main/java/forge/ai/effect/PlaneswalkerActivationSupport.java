package forge.ai.effect;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.tinylog.Logger;

import forge.game.GameEntity;
import forge.game.ability.ApiType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;

/** Narrow bridge from a complete loyalty valuation plan to a live activation. */
public final class PlaneswalkerActivationSupport {
    private PlaneswalkerActivationSupport() {
    }

    public static boolean isLoyaltyAction(final SpellAbility ability) {
        return ability != null && ability.isActivatedAbility() && ability.isPwAbility()
                && ability.getHostCard() != null && ability.getHostCard().isPlaneswalker();
    }

    /**
     * Re-evaluates a complete plan and copies only its announcement-time targets to the live
     * activation. Choices that happen while resolving the ability remain with the normal resolver.
     */
    public static boolean prepareAnnouncementTargets(final SpellAbility ability,
            final Player ai) {
        if (!isLoyaltyAction(ability) || ai == null
                || ability.getApi() == ApiType.Charm
                || ability.getApi() == ApiType.GenericChoice
                || ability.getApi() == ApiType.Vote) {
            // TODO(planeswalker activation): Transfer announcement-time mode/number choices
            // when those can be distinguished from choices made during resolution.
            return reject(ability, "unsupported announcement-time choice form");
        }
        ability.setActivatingPlayer(ai);
        final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(ability, ai);
        if (!plan.complete()) {
            return reject(ability, "outcome plan " + plan.completeness() + ": " + plan.reason());
        }

        final List<SpellAbility> activationAbilities = new ArrayList<>();
        collectActivationAbilities(ability, activationAbilities);
        final Map<SpellAbility, List<GameEntity>> selectedByAbility = new IdentityHashMap<>();
        for (final OutcomePlan.Decision decision : plan.decisions()) {
            if (decision.kind() != OutcomePlan.DecisionKind.TARGET
                    || !decision.id().startsWith("target:")) {
                continue;
            }
            final String id = decision.id().substring("target:".length());
            final SpellAbility owner = activationAbilities.stream()
                    .filter(candidate -> Integer.toString(candidate.getId()).equals(id))
                    .findFirst().orElse(null);
            // Targets belonging to a delayed/immediate generated ability are chosen when that
            // ability resolves, not when the loyalty ability is activated.
            if (owner == null) {
                continue;
            }
            final List<GameEntity> selected = selectedTargets(decision);
            if (selected.isEmpty() || !owner.usesTargeting()) {
                return reject(ability, "target decision could not be transferred: " + decision.id());
            }
            if (selected.stream().anyMatch(target -> target == null)
                    || selected.size() < owner.getMinTargets()
                    || selected.size() > owner.getMaxTargets()) {
                return reject(ability, "target decision no longer matches legal target count");
            }
            selectedByAbility.put(owner, selected);
        }

        for (final SpellAbility candidate : activationAbilities) {
            if (candidate.usesTargeting() && !selectedByAbility.containsKey(candidate)) {
                return reject(ability, "an announcement-time target has no selected plan target");
            }
        }
        for (final Map.Entry<SpellAbility, List<GameEntity>> entry
                : selectedByAbility.entrySet()) {
            entry.getKey().resetTargets();
            entry.getKey().getTargets().addAll(entry.getValue());
        }
        trace(ability, "prepared", "complete", plan.value(), plan.decisions().toString());
        return true;
    }

    private static boolean reject(final SpellAbility ability, final String reason) {
        trace(ability, "rejected", reason, 0, "");
        return false;
    }

    private static void trace(final SpellAbility ability, final String status,
            final String reason, final double outcomePlanValue, final String decisions) {
        if (!Boolean.parseBoolean(System.getProperty(EffectAnalysisTrace.ENABLE_PROPERTY, "true"))
                || ability == null || ability.getHostCard() == null) {
            return;
        }
        Logger.info("[AI Effect Analysis] Planeswalker activation preparation: source={}, cost={}, "
                        + "status={}, outcomePlanValue={}, decisions={}, reason={}",
                ability.getHostCard().getName(),
                ability.getPayCosts() == null ? "NONE" : ability.getPayCosts(),
                status, outcomePlanValue, decisions, reason);
    }

    private static List<GameEntity> selectedTargets(final OutcomePlan.Decision decision) {
        final List<GameEntity> result = new ArrayList<>();
        for (final Object selection : decision.selections()) {
            if (selection instanceof List<?> group) {
                for (final Object target : group) {
                    if (target instanceof GameEntity entity) {
                        result.add(entity);
                    }
                }
            } else if (selection instanceof GameEntity entity) {
                result.add(entity);
            }
        }
        return result;
    }

    private static void collectActivationAbilities(final SpellAbility ability,
            final List<SpellAbility> result) {
        if (ability == null || result.contains(ability)) {
            return;
        }
        result.add(ability);
        final SpellAbility sub = ability.getSubAbility();
        if (sub instanceof AbilitySub abilitySub) {
            collectActivationAbilities(abilitySub, result);
        }
    }
}
