package forge.ai.effect;

import java.util.Set;

import forge.ai.ComputerUtilCombat;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.staticability.StaticAbilityCantCrew;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;

/** Coarse near-term occurrence estimates shared by production extractors. */
final class EffectOccurrenceEstimator {
    // TODO(effect analysis): Add a bounded horizon and likelihood estimates for more trigger
    // origins, repeated activations, legal targets, resource competition, source survival, and
    // combat-damage/cast conditions. Current support is a coarse one-batch attack/phase estimate.
    private static final Set<String> ATTACK_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> PHASE_TRIGGER_PARAMS = Set.of(
            "Mode", "Phase", "ValidPlayer", "Execute", "TriggerZones", "TriggerDescription", "Secondary");

    private EffectOccurrenceEstimator() {
    }

    static int estimateTriggerBatches(final Card source, final Trigger trigger) {
        if (trigger.getMode() == TriggerType.Attacks) {
            if (!EffectAbilityUtils.hasOnlyParams(trigger, ATTACK_TRIGGER_PARAMS)
                    || !"Card.Self".equals(trigger.getParam("ValidCard"))) {
                return 0;
            }
            return canProduceByAttacking(source) ? 1 : 0;
        }
        if (trigger.getMode() == TriggerType.Phase) {
            if (!EffectAbilityUtils.hasOnlyParams(trigger, PHASE_TRIGGER_PARAMS)
                    || !isSupportedPhase(trigger.getParam("Phase"))
                    || (trigger.hasParam("ValidPlayer")
                            && !"You".equals(trigger.getParam("ValidPlayer")))) {
                return 0;
            }
            return 1;
        }
        return 0;
    }

    private static boolean canProduceByAttacking(final Card source) {
        if (source.isCreature()) {
            return ComputerUtilCombat.canAttackNextTurn(source);
        }
        if (!source.getType().hasSubtype("Vehicle") || !source.hasKeyword(Keyword.CREW)
                || !CombatUtil.getAllPossibleDefenders(source.getController()).anyMatch(
                        defender -> CombatUtil.canAttackNextTurn(source, defender))) {
            return false;
        }

        final int crewPowerNeeded = source.getKeywordMagnitude(Keyword.CREW);
        int availablePower = 0;
        for (final Card creature : source.getController().getCreaturesInPlay()) {
            if (creature != source && canCrewNextTurn(creature)) {
                availablePower = EffectMath.add(availablePower, Math.max(0, creature.getNetPower()));
            }
        }
        return availablePower >= crewPowerNeeded;
    }

    private static boolean canCrewNextTurn(final Card creature) {
        return !creature.isPhasedOut()
                && !StaticAbilityCantCrew.cantCrew(creature)
                && (!creature.isTapped() || (creature.getCounters(CounterEnumType.STUN) == 0
                        && creature.canUntap(creature.getController(), true)));
    }

    private static boolean isSupportedPhase(final String phase) {
        return "Upkeep".equalsIgnoreCase(phase) || "End of Turn".equalsIgnoreCase(phase);
    }
}
