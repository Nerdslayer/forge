package forge.ai.effect;

import java.util.Map;
import java.util.function.ToIntFunction;

import forge.ai.ComputerUtilCard;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/** Shared aggregation and perspective handling for analysis-only card-state changes. */
final class CardStateDeltaEvaluator {
    private CardStateDeltaEvaluator() {
    }

    static int evaluate(final SpellAbility outcome, final OutcomeEvaluationContext context,
            final AffectedCardResolver.Resolution resolution,
            final ToIntFunction<Card> cardDeltaEvaluator) {
        if (resolution.cards().isEmpty()) {
            return 0;
        }
        if (resolution.chooseOne()) {
            return chooseBest(outcome, context, resolution, cardDeltaEvaluator);
        }

        int value = 0;
        for (final AffectedCardResolver.WeightedCard affected : resolution.cards()) {
            value = EffectMath.add(value, EffectMath.multiply(
                    affected.occurrences(), cardDeltaEvaluator.applyAsInt(affected.card())));
        }
        return value;
    }

    static int evaluateChange(final OutcomeEvaluationContext context,
            final Card original, final Card changed) {
        preserveProjectedDamage(context, original, changed);
        final int before = permanentValue(context, original);
        final int after = permanentValue(context, changed);
        final int delta = EffectMath.subtract(after, before);
        if (context.state() != null) { context.state().cards.put(original, changed); }
        return applyPerspective(context, original, delta);
    }

    static int evaluateDeparture(final OutcomeEvaluationContext context, final Card original) {
        final Card projected = context.state() == null ? original : context.state().card(original);
        if (projected == null) { return 0; }
        final int value = permanentValue(context, projected);
        if (context.state() != null) { context.state().cards.put(original, null); }
        return applyPerspective(context, projected, EffectMath.negate(value));
    }

    static int evaluateControlChange(final OutcomeEvaluationContext context,
            final Card original, final Player newController) {
        if (original.getController() == newController) {
            return 0;
        }

        final Card changed = CardCopyService.getLKICopy(original);
        changed.setController(newController, 0);
        return evaluateBoardChanges(context, Map.of(original, changed));
    }

    static int evaluateBoardChanges(final OutcomeEvaluationContext context,
            final Map<Card, Card> changes) {
        int before = 0;
        int after = 0;
        for (final Map.Entry<Card, Card> change : changes.entrySet()) {
            final Card original = change.getKey();
            before = EffectMath.add(before,
                    signedBoardValue(context, original, original.getController()));
            final Card changed = change.getValue();
            if (changed != null) {
                preserveProjectedDamage(context, original, changed);
                after = EffectMath.add(after,
                        signedBoardValue(context, changed, changed.getController()));
            }
        }
        if (context.state() != null) { context.state().cards.putAll(changes); }
        return EffectMath.subtract(before, after);
    }

    private static int applyPerspective(final OutcomeEvaluationContext context,
            final Card original, final int delta) {
        return original.getController().isOpponentOf(context.evaluatingAi())
                ? delta : EffectMath.negate(delta);
    }

    private static int signedBoardValue(final OutcomeEvaluationContext context,
            final Card card, final Player controller) {
        final int value = permanentValue(context, card);
        return controller.isOpponentOf(context.evaluatingAi())
                ? EffectMath.negate(value) : value;
    }

    private static void preserveProjectedDamage(final OutcomeEvaluationContext context,
            final Card original, final Card changed) {
        // Forge's LKI copies omit marked damage. Ordinary counter/keyword/state changes retain
        // it, so carry the branch's marked damage through those copy-building helpers.
        if (context.state() != null && context.state().damageAffected.contains(original)) {
            if (changed.getDamage() == 0) { changed.setDamage(original.getDamage()); }
            if (original.hasBeenDealtDeathtouchDamage()) { changed.setHasBeenDealtDeathtouchDamage(true); }
        }
    }

    static int permanentValue(final OutcomeEvaluationContext context, final Card card) {
        if (context.state() != null && context.state().damageAffected.contains(card)) {
            if (card.isCreature() && (card.getNetToughness() <= 0 || card.canBeDestroyed()
                    && (card.getDamage() >= card.getLethal() || card.hasBeenDealtDeathtouchDamage()))) {
                return 0;
            }
            if (card.isPlaneswalker() && card.getCounters(forge.game.card.CounterEnumType.LOYALTY) == 0) {
                return 0;
            }
        }
        return ComputerUtilCard.evaluatePermanent(context.evaluatingAi(), card);
    }

    private static int chooseBest(final SpellAbility outcome,
            final OutcomeEvaluationContext context,
            final AffectedCardResolver.Resolution resolution,
            final ToIntFunction<Card> cardDeltaEvaluator) {
        final boolean chooserIsOpponent = outcome.getActivatingPlayer()
                .isOpponentOf(context.evaluatingAi());
        int best = chooserIsOpponent ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        for (final AffectedCardResolver.WeightedCard affected : resolution.cards()) {
            final int value = cardDeltaEvaluator.applyAsInt(affected.card());
            best = chooserIsOpponent ? Math.max(best, value) : Math.min(best, value);
        }
        if (best == Integer.MIN_VALUE || best == Integer.MAX_VALUE) {
            return 0;
        }
        return best;
    }
}
