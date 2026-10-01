package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import forge.ai.combat.PreparedCombatValuation;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/** Prepares first-order public relationships without calling activation or combat prediction. */
final class CombatRelationshipPreparation {
    private CombatRelationshipPreparation() { }

    record Result(List<PreparedCombatValuation.RelationshipValue> relationships, List<String> reasons) {
        Result { relationships = List.copyOf(relationships); reasons = List.copyOf(reasons); }
    }

    static Optional<Result> prepare(final Player ai, final int weightPercent, final BooleanSupplier checkpoint) {
        final List<EffectProduction> productions = new ArrayList<>();
        final Map<EffectType, List<EffectConsequence>> consequences = new EnumMap<>(EffectType.class);
        final List<String> reasons = new ArrayList<>();
        final List<PreparedCombatValuation.RelationshipValue> relationships = new ArrayList<>();
        if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
        if (weightPercent <= 0) { return Optional.of(new Result(relationships, reasons)); }
        for (final Card source : ai.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
            if (source.isFaceDown() || source.isPhasedOut() || source.getController() == null) { continue; }
            for (final Trigger trigger : source.getTriggers()) {
                if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
                try {
                    productions.addAll(EffectProductionExtractorRegistry.extractForCombatPreparation(ai, source, trigger));
                    final EffectConsequence consequence = EffectConsequenceExtractorRegistry.extract(source, trigger);
                    if (consequence != null) {
                        consequences.computeIfAbsent(consequence.observedType(), key -> new ArrayList<>()).add(consequence);
                    }
                } catch (final RuntimeException ignored) {
                    reasons.add("Public relationship extraction unavailable for card " + source.getId());
                }
            }
        }
        for (int batch = 0; batch < productions.size(); batch++) {
            if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
            final EffectProduction production = productions.get(batch);
            final EffectEventMatcher matcher = EffectEventMatcherRegistry.find(production.type());
            if (matcher == null) { continue; }
            for (final EffectConsequence consequence : consequences.getOrDefault(production.type(), List.of())) {
                if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
                final TriggeredRelationshipEvaluator.Evaluation evaluation = TriggeredRelationshipEvaluator.evaluate(
                        ai, production, consequence, matcher, EffectAnalysisTrace.disabled(), true);
                if (!evaluation.supported()) {
                    reasons.add("Unprepared " + production.type() + " consequence on card " + consequence.source().getId());
                    continue;
                }
                if (evaluation.value() == 0) { continue; }
                final PreparedCombatValuation.RelationshipKey key = new PreparedCombatValuation.RelationshipKey(
                        production.source().getId(), production.ability().path(), consequence.source().getId(),
                        consequence.ability().path(), production.type().name(), "scheduled:" + batch);
                relationships.add(new PreparedCombatValuation.RelationshipValue(key,
                        EffectMath.scalePercent(evaluation.value(), weightPercent)));
            }
        }
        // TODO: Add side-effect-free token prototypes, activated/normal player/combat events,
        // targeted/modal consequences, and surviving static-recipient transitions. This subset
        // must remain PARTIAL to consumers.
        return checkpoint.getAsBoolean() ? Optional.of(new Result(relationships, reasons)) : Optional.empty();
    }
}
