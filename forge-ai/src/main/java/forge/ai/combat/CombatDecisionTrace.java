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
                .append("\nPublic planeswalkers (loyalty, ultimate threshold, urgency): ").append(snapshot.planeswalkers())
                .append("\nLegal attack destinations: ").append(plan.legalDestinations())
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
                    .append(", destinations=").append(candidate.destinations())
                    .append(", planeswalker loyalty after=").append(candidate.combat().projection().planeswalkerLoyaltyAfter())
                    .append(", planeswalker utility=").append(CombatTransitionValueEvaluator.planeswalkerUtility(plan.snapshot().orElseThrow(), candidate.combat().projection()))
                    .append(", walker goals=").append(walkerGoals(plan.snapshot().orElseThrow(), candidate.combat().projection()))
                    .append(", uncommitted attackers=").append(plan.legalDestinations().keySet().stream()
                            .filter(id -> !candidate.attackers().contains(id)).sorted().toList())
                    .append(", predicted blocks=").append(candidate.combat().assignment().blockersByAttacker())
                    .append(", blocker chump life adjustment=").append(candidate.combat().chumpLifeAdjustment())
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

    private static java.util.Map<Integer, String> walkerGoals(final PublicCombatSnapshot snapshot, final CombatProjection projection) {
        final java.util.Map<Integer, String> result = new java.util.LinkedHashMap<>();
        snapshot.planeswalkers().values().stream().sorted(java.util.Comparator.comparingInt(PublicCombatSnapshot.Planeswalker::id)).forEach(walker -> {
            final int after = projection.planeswalkerLoyaltyAfter().getOrDefault(walker.id(), walker.loyalty());
            final String goal = after == 0 ? "predicted kill" : walker.ultimateThreat().potential()
                    && walker.ultimateThreshold() > 0 && after < walker.ultimateThreshold() ? "predicted ultimate denial"
                            : after < walker.loyalty() ? "loyalty reduced" : "unchanged";
            result.put(walker.id(), goal);
        });
        return result;
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
            text.append("\nPublic combat characteristics: ").append(snapshot.creatures());
            text.append("\nPublic player life: ").append(snapshot.players());
            text.append("\nAttacker destinations: ").append(snapshot.attackersToDefenders());
            text.append("\nAvailable legal blocking pairs: ").append(snapshot.legalBlockers());
            text.append("\nIgnored effects (approximate model): ").append(snapshot.ignoredEffects());
        });
        plan.search().ifPresent(search -> {
            text.append("\nSearch exhaustive: ").append(search.searchExhaustive())
                    .append(", outcome domain supported: ").append(search.outcomeDomainComplete());
            text.append(", fresh block evaluations: ").append(search.evaluations())
                    .append(", exact block cache hits: ").append(search.cacheHits());
            text.append("\nFixed blocks: ").append(plan.fixedBlocks());
            search.unblocked().ifPresent(candidate -> text.append("\nLegal block baseline: ").append(candidate.assignment().blockersByAttacker())
                    .append(", value=").append(candidate.score().total())
                    .append(", terminal=").append(candidate.projection().terminal())
                    .append(", public follow-up=").append(candidate.followUp()));
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
                    .append(", nonlethal chump life adjustment=").append(candidate.chumpLifeAdjustment())
                    .append(", public follow-up=").append(candidate.followUp()));
            if (search.unblocked().isPresent() && search.best().isPresent()) {
                final var baseline = search.unblocked().orElseThrow().followUp();
                final var selected = search.best().orElseThrow().followUp();
                if (baseline.isPresent() && selected.isPresent() && selected.orElseThrow().survivalOpportunity().isPresent()) {
                    final int raw = CombatSafetyEvaluator.survivalDelta(baseline.orElseThrow(), selected.orElseThrow());
                    final long ordinary = (long) selected.orElseThrow().nonterminalUtility() - baseline.orElseThrow().nonterminalUtility();
                    text.append("\nSurvival opportunity delta: raw=").append(raw)
                            .append(", weighted contribution=").append(CombatSafetyEvaluator.discountedFollowUpValue(ordinary + raw)
                                    - CombatSafetyEvaluator.discountedFollowUpValue(ordinary));
                }
            }
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
