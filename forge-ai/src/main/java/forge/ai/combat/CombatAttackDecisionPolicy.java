package forge.ai.combat;

import java.util.ArrayList;
import java.util.Optional;

/** Final own-attack policy; never used to predict the opponent's willingness to attack. */
final class CombatAttackDecisionPolicy {
    private CombatAttackDecisionPolicy() { }

    static boolean bothRepliesLethal(final CombatSafetyEvaluator.FollowUp baseline,
            final CombatSafetyEvaluator.FollowUp candidate) {
        return baseline.usable() && candidate.usable()
                && baseline.lethalOpportunity() && candidate.lethalOpportunity();
    }

    static CombatAttackSearch.Result select(final CombatAttackSearch.Result result) {
        if (!result.outcomeDomainComplete() || !result.candidateSearchComplete()
                || result.best().isEmpty() || result.noAttack().isEmpty()) { return result; }
        final var selected = result.best().orElseThrow();
        final var baseline = result.noAttack().orElseThrow();
        if (selected.equals(baseline)
                || selected.combat().projection().terminal() != CombatProjection.Terminal.NONE
                || baseline.combat().projection().terminal() != CombatProjection.Terminal.NONE
                || selected.reply().isEmpty() || baseline.reply().isEmpty()
                || !bothRepliesLethal(baseline.reply().orElseThrow(), selected.reply().orElseThrow())) {
            return result;
        }
        // These completed heuristic continuations are not a proof that every possible line loses.
        // Preserve the evaluated fixed-attacker baseline, not an invented empty declaration.
        // TODO: Explicit second-main recovery actions or drawing an answer may justify an attack;
        // ordinary resource/damage points alone do not establish a chance to escape the lethal reply.
        final long rawDelta = (long) selected.reply().orElseThrow().nonterminalUtility()
                - baseline.reply().orElseThrow().nonterminalUtility();
        final var reasons = new ArrayList<>(result.reasons());
        reasons.add("hold_no_modeled_escape_from_lethal_reply: baselineLethal=true, selectedLethal=true"
                + ", rejectedPrePolicyAttackers=" + selected.attackers()
                + ", rejectedPrePolicyDestinations=" + selected.destinations()
                + ", rawReplyDelta=" + rawDelta
                + ", discountedRawReplyAdjustment=" + CombatSafetyEvaluator.discountedFollowUpValue(rawDelta)
                + ", appliedReplyAdjustment=" + selected.replyAdjustment());
        return new CombatAttackSearch.Result(Optional.of(baseline), result.noAttack(), result.searchExhaustive(),
                result.outcomeDomainComplete(), result.nodes(), reasons, result.candidateSearchComplete(),
                result.declarationsEvaluated());
    }
}
