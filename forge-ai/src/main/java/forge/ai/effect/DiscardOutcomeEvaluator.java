package forge.ai.effect;

import java.util.List;
import java.util.Set;

import forge.ai.PlayerResourceValueEvaluator;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Values mandatory random, discard-all, and affected-player-chosen discard. */
final class DiscardOutcomeEvaluator implements OutcomeEvaluator {
    static final DiscardOutcomeEvaluator INSTANCE = new DiscardOutcomeEvaluator();

    // TODO(effect analysis): Support source-controller-selected discard, restricted card subsets,
    // discard-unless, optional/up-to/any-number forms, reveal/look and defined-card modes,
    // dynamic recipients, multiplayer or multi/optional targets, mixed discard/draw chains,
    // replacement effects, known/revealed card quality, and graveyard, madness, reanimation, or
    // discard-cost synergies. Current chosen-discard value assumes optimal affected-player choice.
    private static final Set<String> SUPPORTED_PARAMS = Set.of(
            "DB", "Defined", "Mode", "NumCards", "ValidTgts", "ValidTgtsDesc", "TgtPrompt",
            "TargetMin", "TargetMax", "TargetsAtRandom",
            "SpellDescription", "StackDescription");
    private static final Set<String> SUPPORTED_RECIPIENTS = Set.of(
            "You", "Player", "Opponent", "Player.Opponent");

    private DiscardOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        if (outcome == null
                || outcome.getApi() != ApiType.Discard
                || outcome.getSubAbility() != null
                || !SUPPORTED_PARAMS.containsAll(outcome.getMapParams().keySet())
                || !PlayerRecipientResolver.hasSupportedTargetShape(outcome)
                || (!outcome.usesTargeting() && !SUPPORTED_RECIPIENTS.contains(
                        outcome.getParamOrDefault("Defined", "You")))
                || outcome.getParamOrDefault("NumCards", "1").isBlank()) {
            return false;
        }
        final String mode = outcome.getParam("Mode");
        return "Random".equals(mode) || "TgtChoose".equals(mode) || "Hand".equals(mode);
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome,
            final OutcomeEvaluationContext context) {
        try {
            int value = 0;
            final String mode = outcome.getParam("Mode");
            final boolean randomOrWholeHand = "Random".equals(mode) || "Hand".equals(mode);
            final int requested = "Hand".equals(mode) ? Integer.MAX_VALUE
                    : AbilityUtils.calculateAmount(outcome.getHostCard(),
                            outcome.getParamOrDefault("NumCards", "1"), outcome);
            if (requested <= 0) {
                return 0;
            }
            final List<Player> recipients = PlayerRecipientResolver.resolve(outcome);
            for (final Player recipient : recipients) {
                if (!recipient.isInGame() || !recipient.canDiscardBy(outcome, true)) {
                    continue;
                }
                final int handSize = recipient.getCardsIn(ZoneType.Hand).size();
                final int discardValue = randomOrWholeHand
                        ? PlayerResourceValueEvaluator.evaluateRandomDiscard(handSize, requested)
                        : PlayerResourceValueEvaluator.evaluateChosenDiscard(handSize, requested);
                value = EffectMath.add(value,
                        recipient.isOpponentOf(context.evaluatingAi())
                                ? EffectMath.negate(discardValue) : discardValue);
            }
            return value;
        } catch (final RuntimeException ignored) {
            return 0;
        }
    }
}
