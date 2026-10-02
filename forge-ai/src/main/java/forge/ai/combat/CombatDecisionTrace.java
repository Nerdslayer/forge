package forge.ai.combat;

import org.tinylog.Logger;

import forge.ai.effect.EffectAnalysisTrace;
import forge.game.player.Player;

/** One compact decision record; no formatting or logging inside strategy/projection loops. */
public final class CombatDecisionTrace {
    private CombatDecisionTrace() { }

    public static void damagePreparation(final Player ai, final CombatDamagePlanner.Preparation preparation,
            final CombatSearchBudget budget) {
        if (!Boolean.parseBoolean(System.getProperty(EffectAnalysisTrace.ENABLE_PROPERTY, "true"))) { return; }
        final StringBuilder text = new StringBuilder("[AI Combat Analysis]\nDecision: actual-block damage binding, observer=")
                .append(ai.getName()).append('#').append(ai.getId())
                .append(", prepared=").append(preparation.execution().isPresent())
                .append("\nNodes: ").append(budget.used()).append(", elapsedMs=").append(budget.elapsedMillis())
                .append("\nReasons: ").append(preparation.reasons())
                .append("\nTwo-turn pressure: ").append(preparation.twoTurnPressure());
        preparation.search().ifPresent(search -> {
            text.append("\nAllocation search exhaustive: ").append(search.exhaustive())
                    .append(", outcome supported: ").append(search.outcomeSupported());
            search.best().ifPresent(candidate -> text.append("\nActual blocks: ").append(candidate.assignment().blockersByAttacker())
                    .append(", allocations=").append(candidate.damagePlan())
                    .append("\nLoss components: ").append(candidate.score().permanentLoss())
                    .append(", life after=").append(candidate.projection().playerLifeAfter())
                    .append(", terminal=").append(candidate.projection().terminal())
                    .append("\nIgnored effects (approximate model): ").append(candidate.projection().reasons()));
        });
        Logger.info(text.toString());
    }

    public static void attacks(final Player ai, final CombatAttackPlanner.Plan plan, final boolean applied,
            final String executionReason, final CombatSearchBudget budget) {
        if (!Boolean.parseBoolean(System.getProperty(EffectAnalysisTrace.ENABLE_PROPERTY, "true"))) { return; }
        final StringBuilder text = new StringBuilder("[AI Combat Analysis]\nDecision: attack, observer=")
                .append(ai.getName()).append('#').append(ai.getId()).append(", applied=").append(applied)
                .append("\nNodes: ").append(budget.used()).append(", elapsedMs=").append(budget.elapsedMillis())
                .append("\nPlan reasons: ").append(plan.reasons()).append("\nExecution: ").append(executionReason);
        plan.snapshot().ifPresent(snapshot -> text.append("\nPublic attack alternatives: ").append(snapshot.attackersToDefenders())
                .append("\nPublic creature IDs: ").append(publicLabels(ai, snapshot))
                .append("\nIgnored effects (approximate model): ").append(snapshot.ignoredEffects()));
        plan.search().ifPresent(search -> {
            text.append("\nSearch exhaustive: ").append(search.searchExhaustive())
                    .append(", outcome domain supported: ").append(search.outcomeDomainComplete());
            text.append("\nFixed attackers: ").append(plan.fixedAttackers());
            text.append("\nGreedy candidate search complete: ").append(search.candidateSearchComplete())
                    .append(", declarations evaluated: ").append(search.declarationsEvaluated());
            search.noAttack().ifPresent(candidate -> text.append("\nNo additional attacks: ").append(candidate.total())
                    .append(", public reply=").append(candidate.reply()));
            search.best().ifPresent(candidate -> text.append("\nChosen attackers: ").append(candidate.attackers())
                    .append(", predicted blocks=").append(candidate.combat().assignment().blockersByAttacker())
                    .append("\nLoss components: ").append(candidate.combat().score().permanentLoss())
                    .append("\nLife utility: ").append(candidate.combat().score().lifeUtility())
                    .append(", immediate outcome utility=").append(candidate.combat().score().outcomeUtility())
                    .append(", resolved opportunities=").append(candidate.combat().projection().outcomes().resolutions())
                    .append(", life after=").append(candidate.combat().projection().playerLifeAfter())
                    .append(", terminal=").append(candidate.combat().projection().terminal())
                    .append(", certified immediate win=").append(candidate.certifiedWin())
                    .append(", allocations=").append(candidate.combat().damagePlan())
                    .append("\nIncremental reply utility: ").append(candidate.replyAdjustment())
                    .append(", public reply=").append(candidate.reply())
                    .append("\nTwo-turn pressure: ").append(candidate.twoTurnPressure()));
        });
        Logger.info(text.toString());
    }

    public static void blocks(final Player ai, final CombatBlockPlanner.Plan plan, final boolean applied,
            final String executionReason, final CombatSearchBudget budget) {
        if (!Boolean.parseBoolean(System.getProperty(EffectAnalysisTrace.ENABLE_PROPERTY, "true"))) { return; }
        final StringBuilder text = new StringBuilder("[AI Combat Analysis]\nDecision: block, observer=")
                .append(ai.getName()).append('#').append(ai.getId()).append(", applied=").append(applied)
                .append("\nNodes: ").append(budget.used()).append(", elapsedMs=").append(budget.elapsedMillis())
                .append("\nPlan reasons: ").append(plan.reasons()).append("\nExecution: ").append(executionReason);
        plan.snapshot().ifPresent(snapshot -> {
            text.append("\nAttacking player: ").append(snapshot.attackingPlayerId())
                    .append(", defending player: ").append(snapshot.defendingPlayerId());
            text.append("\nPublic creature IDs: ").append(publicLabels(ai, snapshot));
            text.append("\nIgnored effects (approximate model): ").append(snapshot.ignoredEffects());
        });
        plan.search().ifPresent(search -> {
            text.append("\nSearch exhaustive: ").append(search.searchExhaustive())
                    .append(", outcome domain supported: ").append(search.outcomeDomainComplete());
            text.append("\nFixed blocks: ").append(plan.fixedBlocks());
            search.unblocked().ifPresent(candidate -> text.append("\nLegal block baseline: ").append(candidate.assignment().blockersByAttacker())
                    .append(", value=").append(candidate.score().total())
                    .append(", terminal=").append(candidate.projection().terminal()));
            search.best().ifPresent(candidate -> text.append("\nChosen blocks: ").append(candidate.assignment().blockersByAttacker())
                    .append("\nLoss components: ").append(candidate.score().permanentLoss())
                    .append("\nLife utility: ").append(candidate.score().lifeUtility())
                    .append(", immediate outcome utility=").append(candidate.score().outcomeUtility())
                    .append(", resolved opportunities=").append(candidate.projection().outcomes().resolutions())
                    .append(", life after=").append(candidate.projection().playerLifeAfter())
                    .append(", terminal=").append(candidate.projection().terminal())
                    .append(", coverage=").append(candidate.score().completeness())
                    .append(", allocations=").append(candidate.damagePlan())
                    .append("\nIncremental pressure: ").append(candidate.pressureValue())
                    .append(", equal-exchange preference=").append(candidate.exchangePreference())
                    .append(", public follow-up=").append(candidate.followUp()));
        });
        Logger.info(text.toString());
    }

    private static java.util.Map<Integer, String> publicLabels(final Player ai, final PublicCombatSnapshot snapshot) {
        final java.util.Map<Integer, String> labels = new java.util.LinkedHashMap<>();
        ai.getGame().getCardsIn(forge.game.zone.ZoneType.Battlefield).stream()
                .filter(card -> snapshot.creatures().containsKey(card.getId())).sorted(java.util.Comparator.comparingInt(card -> card.getId()))
                .forEach(card -> labels.put(card.getId(), card.isFaceDown() ? "Face-down card" : card.getName()));
        return labels;
    }
}
