package forge.ai.effect;

import forge.ai.ComputerUtil;
import forge.ai.CardResourceValueEvaluator;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Values supported outcomes that permanently remove a battlefield permanent. */
final class PermanentRemovalOutcomeEvaluator implements OutcomeEvaluator {
    static final PermanentRemovalOutcomeEvaluator INSTANCE =
            new PermanentRemovalOutcomeEvaluator();

    // TODO(effect analysis): Account for destruction and zone-change replacement effects,
    // regeneration decisions, death/leave triggers, commanders, cards valuable in other zones,
    // downstream static-effect changes, targeted groups, temporary exile,
    // independently scripted delayed returns, optional/control-flow forms, and subability chains.

    private PermanentRemovalOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        if (outcome == null || outcome.getSubAbility() != null
                || EffectAbilityUtils.hasUnsupportedControlFlow(outcome)) {
            return false;
        }
        if (outcome.getApi() == ApiType.DestroyAll) {
            return !outcome.usesTargeting() && !outcome.hasParam("Defined")
                    && outcome.hasParam("ValidCards");
        }
        if (!outcome.usesTargeting() && !outcome.hasParam("Defined")) { return false; }
        final boolean bounce = isBounce(outcome);
        if (outcome.usesTargeting() && !(bounce
                ? supportsOptionalSingleBattlefieldTarget(outcome)
                : AffectedCardResolver.supportsSingleBattlefieldTarget(outcome))) { return false; }
        if (outcome.getApi() == ApiType.Destroy) {
            return !outcome.hasParam("Radiance");
        }
        return outcome.getApi() == ApiType.ChangeZone
                && "Battlefield".equals(outcome.getParam("Origin"))
                && ("Exile".equals(outcome.getParam("Destination")) || bounce)
                && !outcome.hasParam("Duration")
                && !outcome.hasParam("ChangeNum")
                && !outcome.hasParam("ChangeType")
                && !outcome.hasParam("Chooser")
                && !outcome.hasParam("DefinedPlayer");
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome,
            final OutcomeEvaluationContext context) {
        try {
            if (outcome.getApi() == ApiType.DestroyAll) {
                final AffectedCardResolver.Resolution resolution = AffectedCardResolver.group(
                        outcome, context, card -> canRemove(outcome, card));
                final java.util.Map<Card, Card> departures = new java.util.LinkedHashMap<>();
                for (final AffectedCardResolver.WeightedCard affected : resolution.cards()) {
                    departures.put(affected.card(), null);
                }
                return CardStateDeltaEvaluator.evaluateBoardChanges(context, departures);
            }
            if (isBounce(outcome)) {
                final AffectedCardResolver.Resolution resolution = outcome.usesTargeting()
                        ? AffectedCardResolver.targeted(outcome, context,
                                card -> card.isInPlay() && !card.isPhasedOut())
                        : AffectedCardResolver.defined(outcome, context,
                                card -> card.isInPlay() && !card.isPhasedOut());
                return CardStateDeltaEvaluator.evaluate(outcome, context, resolution,
                        card -> evaluateBounce(outcome, context, card));
            }
            final AffectedCardResolver.Resolution resolution = outcome.usesTargeting()
                    ? AffectedCardResolver.targeted(outcome, context,
                            card -> canRemove(outcome, card))
                    : AffectedCardResolver.defined(outcome, context,
                            card -> canRemove(outcome, card));
            return CardStateDeltaEvaluator.evaluate(outcome, context, resolution,
                    affected -> CardStateDeltaEvaluator.evaluateDeparture(context, affected));
        } catch (final RuntimeException ignored) {
            // Dynamic or malformed script forms contribute no outcome value.
            return context.unsupported();
        }
    }

    private static boolean canRemove(final SpellAbility outcome, final Card card) {
        if (!card.isInPlay() || card.isPhasedOut()) {
            return false;
        }
        if (outcome.getApi() != ApiType.Destroy && outcome.getApi() != ApiType.DestroyAll) {
            return true;
        }
        if (!card.canBeDestroyed() || card.getShieldCount() > 0) {
            return false;
        }
        return outcome.hasParam("NoRegen")
                || !ComputerUtil.canRegenerate(card.getController(), card);
    }

    private static int evaluateBounce(final SpellAbility outcome,
            final OutcomeEvaluationContext context, final Card card) {
        int value = CardStateDeltaEvaluator.evaluateDeparture(context, card);
        if (card.isToken()) { return value; }

        final Player owner = card.getOwner() == null ? card.getController() : card.getOwner();
        if (owner == null) { return value; }
        final int currentHandSize = context.state() == null
                ? owner.getCardsIn(ZoneType.Hand).size() : context.state().hand(owner);
        if (context.state() != null) {
            context.state().hands.put(owner, EffectMath.add(currentHandSize, 1));
        }
        // TODO(effect analysis): Use this known card's hand value instead of a generic marginal
        // card value; account for replay likelihood, hidden alternatives, and entry effects.
        final int returnedCardValue = CardResourceValueEvaluator.evaluateNextCard(currentHandSize);
        return EffectMath.add(value, owner.isOpponentOf(context.evaluatingAi())
                ? returnedCardValue : EffectMath.negate(returnedCardValue));
    }

    private static boolean isBounce(final SpellAbility outcome) {
        return outcome.getApi() == ApiType.ChangeZone
                && "Battlefield".equals(outcome.getParam("Origin"))
                && "Hand".equals(outcome.getParam("Destination"));
    }

    private static boolean supportsOptionalSingleBattlefieldTarget(final SpellAbility outcome) {
        final forge.game.spellability.TargetRestrictions restrictions = outcome.getTargetRestrictions();
        return outcome.usesTargeting() && outcome.getMinTargets() >= 0
                && outcome.getMaxTargets() == 1
                && restrictions.getZone().size() == 1
                && restrictions.getZone().contains(ZoneType.Battlefield);
    }
}
