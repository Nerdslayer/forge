/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package forge.ai.effect;

import forge.game.spellability.SpellAbility;

/**
 * Conservative gate for replacing the legacy action with a value-selected combination.
 *
 * <p>Only a complete, certain comparison can override the existing chooser. Keeping this policy
 * pure makes the fail-closed behavior independently testable.</p>
 */
public final class ActionCombinationOverrideGate {
    private ActionCombinationOverrideGate() {
    }

    public record Decision(String reason, long advantage) {
        public boolean shouldOverride() {
            return "advantage_exceeds_threshold".equals(reason);
        }
    }

    public static Decision evaluate(final SpellAbility legacyFirstAction,
            final ManaActionCombinationSelector.Selection legacyPlan,
            final ManaActionCombinationSelector.Selection proposedPlan,
            final boolean resourcesComplete, final boolean legacyAssessmentIncomplete,
            final boolean legacyTimingConstraint, final boolean legacyPriorityProtected,
            final int minimumAdvantage) {
        final boolean loyaltyAlternative = proposedPlan != null && proposedPlan.hasAction()
                && PlaneswalkerActivationSupport.isLoyaltyAction(proposedPlan.firstAction());
        if (legacyFirstAction == null && !loyaltyAlternative) {
            return decision("legacy_pass_or_no_admitted_action", legacyPlan, proposedPlan);
        }
        if (!resourcesComplete) {
            return decision("mana_source_model_incomplete", legacyPlan, proposedPlan);
        }
        if (legacyAssessmentIncomplete) {
            return decision("legacy_assessment_incomplete", legacyPlan, proposedPlan);
        }
        if (legacyTimingConstraint) {
            return decision("legacy_timing_constraint", legacyPlan, proposedPlan);
        }
        if (legacyPlan == null || proposedPlan == null || !proposedPlan.hasAction()
                || legacyFirstAction != null && !legacyPlan.hasAction()) {
            return decision("no_complete_comparison_plan", legacyPlan, proposedPlan);
        }
        if (legacyPriorityProtected) {
            return decision("legacy_priority_protected", legacyPlan, proposedPlan);
        }
        if (legacyPlan.fallbackActionCount() > 0 || proposedPlan.fallbackActionCount() > 0) {
            return decision("fallback_action_in_comparison_plan", legacyPlan, proposedPlan);
        }
        if (legacyPlan.incompleteActionCount() > 0 || proposedPlan.incompleteActionCount() > 0
                || legacyPlan.uncertainActionCount() > 0
                || proposedPlan.uncertainActionCount() > 0) {
            return decision("incomplete_or_uncertain_action_in_comparison_plan", legacyPlan,
                    proposedPlan);
        }
        if (proposedPlan.firstAction() == legacyFirstAction) {
            return decision("legacy_first_card_preserved", legacyPlan, proposedPlan);
        }
        if (legacyFirstAction != null
                && proposedPlan.firstAction().getHostCard() == legacyFirstAction.getHostCard()
                && !(loyaltyAlternative
                        && PlaneswalkerActivationSupport.isLoyaltyAction(legacyFirstAction))) {
            // Only a complete comparison between two loyalty modes may change the legacy
            // same-planeswalker preference. Other same-card alternatives stay with the old AI.
            return decision("legacy_first_card_preserved", legacyPlan, proposedPlan);
        }
        final long advantage = (long) proposedPlan.score() - legacyPlan.score();
        return decision(advantage >= Math.max(0, minimumAdvantage)
                ? "advantage_exceeds_threshold" : "insufficient_advantage", advantage);
    }

    private static Decision decision(final String reason,
            final ManaActionCombinationSelector.Selection legacyPlan,
            final ManaActionCombinationSelector.Selection proposedPlan) {
        final long advantage = legacyPlan == null || proposedPlan == null
                ? Long.MIN_VALUE : (long) proposedPlan.score() - legacyPlan.score();
        return decision(reason, advantage);
    }

    private static Decision decision(final String reason, final long advantage) {
        return new Decision(reason, advantage);
    }
}
