package forge.ai.combat;

import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.effect.CombatValuationEvaluator;
import forge.ai.effect.ValuationContext;
import forge.ai.effect.ValuationDecision;
import forge.game.player.Player;
import java.util.Optional;

/** Shared profile-weighted public valuation preparation for both declaration adapters. */
public final class CombatPlanningPreparation {
    private CombatPlanningPreparation() { }

    /** Live entry boundary only; nested searches receive this same caller-owned allowance. */
    public static CombatSearchBudget budget(final Player observer) {
        return new CombatSearchBudget(Math.max(0, AiProfileUtil.getIntProperty(observer, AiProps.COMBAT_PLANNING_MAX_NODES)),
                Math.max(1, AiProfileUtil.getIntProperty(observer, AiProps.COMBAT_PLANNING_TIMEOUT_MS)));
    }

    static Optional<PreparedCombatValuation> values(final Player observer, final ValuationDecision decision, final CombatSearchBudget budget) {
        final int relationship = AiProfileUtil.getBoolProperty(observer, AiProps.ENABLE_EFFECT_ANALYSIS)
                ? AiProfileUtil.getIntProperty(observer, AiProps.EFFECT_SYNERGY_WEIGHT) : 0;
        final int intrinsic = AiProfileUtil.getBoolProperty(observer, AiProps.ENABLE_INTRINSIC_REMOVAL_ANALYSIS)
                ? AiProfileUtil.getIntProperty(observer, AiProps.INTRINSIC_REMOVAL_WEIGHT) : 0;
        return CombatValuationEvaluator.prepare(ValuationContext.forCombat(observer, decision, relationship, intrinsic), budget::tryConsume);
    }
}
