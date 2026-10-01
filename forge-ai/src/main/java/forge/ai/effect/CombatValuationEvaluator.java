package forge.ai.effect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import forge.ai.combat.PreparedCombatValuation;
import forge.game.card.Card;
import forge.game.zone.ZoneType;

/** Preparation boundary for combat; no attack/block planner is invoked here. */
public final class CombatValuationEvaluator {
    private CombatValuationEvaluator() { }

    public static PreparedCombatValuation prepare(final ValuationContext context) {
        return prepare(context, () -> true).orElseThrow();
    }

    /** All-or-nothing preparation: cancellation never publishes a partially filled loss ledger. */
    public static Optional<PreparedCombatValuation> prepare(final ValuationContext context, final BooleanSupplier checkpoint) {
        if (context == null || context.mode() != ValuationMode.SITUATIONAL
                || (context.decision() != ValuationDecision.ATTACK && context.decision() != ValuationDecision.BLOCK)) {
            throw new IllegalArgumentException("An explicit situational combat context is required");
        }
        if (checkpoint == null) { throw new IllegalArgumentException("A preparation checkpoint is required"); }
        if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
        final Map<Integer, PreparedCombatValuation.PermanentValue> values = new LinkedHashMap<>();
        final Map<forge.ai.combat.CombatAbilityKey, PreparedCombatValuation.OpportunityValue> opportunities = new LinkedHashMap<>();
        for (final Card card : context.evaluatingAi().getGame().getCardsIn(ZoneType.Battlefield)) {
            if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
            if (card.getController() == null) { continue; }
            final int lossSign = card.getController().isOpponentOf(context.evaluatingAi()) ? 1 : -1;
            final List<String> reasons = new ArrayList<>();
            int future = 0;
            int unknown = 0;
            if (context.intrinsicWeightPercent() > 0) {
                final PermanentAbilityValueEvaluator.FutureAbilityEvaluation abilities =
                        PermanentAbilityValueEvaluator.evaluateFutureAbilities(context.evaluatingAi(),
                                card, List.of(), EffectAnalysisTrace.disabled(),
                                PermanentAbilityValueEvaluator.FutureAbilityMode.REFERENCE_ONLY);
                final Map<AbilityIdentity, Integer> byAbility = new LinkedHashMap<>();
                reasons.addAll(abilities.reasons());
                for (final AbilityValueContribution contribution : abilities.contributions()) {
                    if (contribution.counted()) {
                        future = EffectMath.add(future, contribution.value());
                        byAbility.merge(contribution.sourceAbility(), contribution.value(), EffectMath::add);
                    }
                    if (contribution.reason() != null) { reasons.add(contribution.reason()); }
                }
                future = EffectMath.scalePercent(future, context.intrinsicWeightPercent());
                for (final var trigger : card.getTriggers()) {
                    final var description = CombatTriggerDescription.parse(trigger);
                    if (description.isEmpty()) { continue; }
                    final AbilityIdentity identity = AbilityIdentity.forTrigger(card, trigger);
                    final int remaining = EffectMath.scalePercent(byAbility.getOrDefault(identity, 0), context.intrinsicWeightPercent());
                    final int perUse = EffectMath.scalePercent(abilities.referenceResolutionValues().getOrDefault(identity, 0),
                            context.intrinsicWeightPercent());
                    if (remaining != 0 && perUse != 0 && Integer.signum(remaining) == Integer.signum(perUse)) {
                        opportunities.put(description.orElseThrow().ability(), new PreparedCombatValuation.OpportunityValue(remaining, perUse));
                    }
                }
                if (card.isCreature() && abilities.hasUnevaluatedAbility()) {
                    // TODO: Value unknown abilities directly, including drawbacks. The combat-only
                    // minimum protects relevant token/zero-mana engines; removal keeps its old scale.
                    unknown = lossSign * Math.max(10, EffectMath.multiply(Math.max(0, card.getCMC()), 5));
                }
            }
            values.put(card.getId(), new PreparedCombatValuation.PermanentValue(card.getId(),
                    card.getController().getId(), EffectMath.multiply(lossSign,
                            UnifiedPermanentValueEvaluator.evaluate(context.evaluatingAi(), card)),
                    unknown, future, reasons));
            // TODO: Propagate cancellation inside a single intrinsic/reference outcome evaluation.
            // Between-card checks bound repeated work, not the latency of one complex script.
            if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
        }
        final Optional<CombatRelationshipPreparation.Result> preparedRelationships = CombatRelationshipPreparation.prepare(
                context.evaluatingAi(), context.relationshipWeightPercent(), checkpoint);
        if (preparedRelationships.isEmpty() || !checkpoint.getAsBoolean()) { return Optional.empty(); }
        final CombatRelationshipPreparation.Result relationships = preparedRelationships.orElseThrow();
        final List<String> reasons = new ArrayList<>(relationships.reasons());
        reasons.add("Only fixed scheduled public relationships are prepared; static transitions and combat events remain");
        // Do not call the removal analyzer here: its production extraction can recurse through
        // predicted combat. The dedicated preparation policy only admits audited public events.
        // TODO: Replace remaining attack/tap opportunity estimates with concrete event value and
        // project surviving static recipients. This is a frozen loss subtotal, not a full outcome.
        PreparedCombatValuation result = new PreparedCombatValuation(values, relationships.relationships(), ValuationCompleteness.PARTIAL, reasons, opportunities);
        final var liveCombat = context.evaluatingAi().getGame().getPhaseHandler().getCombat();
        if (liveCombat != null && !opportunities.isEmpty()) {
            final Map<forge.ai.combat.CombatAbilityKey, Integer> alreadyObserved = new LinkedHashMap<>();
            for (final Card card : context.evaluatingAi().getGame().getCardsIn(ZoneType.Battlefield)) {
                if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
                for (final var trigger : card.getTriggers()) {
                    CombatTriggerDescription.parse(trigger).ifPresent(description -> {
                        final boolean observed = switch (description.kind()) {
                            case SELF_ATTACK -> liveCombat.isAttacking(card);
                            case SELF_BLOCK -> liveCombat.isBlocking(card);
                            case SELF_COMBAT_PLAYER_DAMAGE, SELF_DIES -> false; // Declarations have not caused these events.
                        };
                        if (observed) { alreadyObserved.put(description.ability(), 1); }
                    });
                }
            }
            result = result.retireOpportunities(alreadyObserved);
        }
        return Optional.of(result);
    }
}
