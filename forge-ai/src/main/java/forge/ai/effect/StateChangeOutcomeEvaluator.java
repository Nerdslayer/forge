package forge.ai.effect;

import java.util.Set;

import forge.ai.ability.SetStateAi;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

/** Values supported battlefield card-state changes through permanent evaluation. */
final class StateChangeOutcomeEvaluator implements OutcomeEvaluator {
    static final StateChangeOutcomeEvaluator INSTANCE = new StateChangeOutcomeEvaluator();

    private static final Set<String> SUPPORTED_MODES = Set.of(
            "Transform", "Flip", "TurnFaceUp", "TurnFaceDown");

    // TODO(effect analysis): Support multiple targets, non-battlefield recipients, choices,
    // custom face-down characteristics, specialize/unspecialize, meld and merged permanents,
    // optional/control-flow forms, replacement-sensitive changes, and subability chains. Estimate
    // or predict opponent-controlled face-down cards from public information instead of reading
    // their hidden front-face characteristics.

    private StateChangeOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        return outcome != null && outcome.getApi() == ApiType.SetState
                && outcome.getSubAbility() == null
                && SUPPORTED_MODES.contains(outcome.getParam("Mode"))
                && !hasUnsupportedParams(outcome)
                && !EffectAbilityUtils.hasUnsupportedControlFlow(outcome)
                && (!outcome.usesTargeting()
                        || AffectedCardResolver.supportsSingleBattlefieldTarget(outcome));
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome,
            final OutcomeEvaluationContext context) {
        try {
            final AffectedCardResolver.Resolution resolution = outcome.usesTargeting()
                    ? AffectedCardResolver.targeted(outcome, context, Card::isInPlay)
                    : AffectedCardResolver.defined(outcome, context, Card::isInPlay);
            return CardStateDeltaEvaluator.evaluate(outcome, context, resolution,
                    affected -> evaluateCardDelta(outcome, affected, context));
        } catch (final RuntimeException ignored) {
            // Dynamic, illegal, or malformed state changes contribute no outcome value.
            return 0;
        }
    }

    private static int evaluateCardDelta(final SpellAbility outcome, final Card affected,
            final OutcomeEvaluationContext context) {
        if ("TurnFaceUp".equals(outcome.getParam("Mode"))
                && affected.getController() != context.evaluatingAi()) {
            // The engine retains the hidden front face, but using it for an opponent's permanent
            // would let the AI cheat. A later model can estimate it from public information.
            return 0;
        }
        if ("Transform".equals(outcome.getParam("Mode"))
                && !affected.canTransform(outcome)) {
            return 0;
        }
        final Card changed = SetStateAi.changeStateForEvaluation(
                affected, outcome.getParam("Mode"));
        return changed == null ? 0
                : CardStateDeltaEvaluator.evaluateChange(context, affected, changed);
    }

    private static boolean hasUnsupportedParams(final SpellAbility outcome) {
        return outcome.hasParam("Choices") || outcome.hasParam("NewState")
                || outcome.hasParam("FaceDownPower") || outcome.hasParam("FaceDownToughness")
                || outcome.hasParam("FaceDownTypes") || outcome.hasParam("FaceDownKeywords")
                || outcome.hasParam("FaceDownAbilities") || outcome.hasParam("FaceDownSVars")
                || outcome.hasParam("ValidNewFace") || outcome.hasParam("RevealFirst")
                || outcome.hasParam("StoredTransform") || outcome.hasParam("ETB");
    }
}
