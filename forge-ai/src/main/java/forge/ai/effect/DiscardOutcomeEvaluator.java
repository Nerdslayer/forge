package forge.ai.effect;

import java.util.List;
import java.util.Set;

import forge.ai.PlayerResourceValueEvaluator;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Values mandatory random and affected-player-chosen discard of unrestricted cards. */
final class DiscardOutcomeEvaluator implements OutcomeEvaluator {
    static final DiscardOutcomeEvaluator INSTANCE = new DiscardOutcomeEvaluator();

    // TODO(effect analysis): Support source-controller-selected discard, restricted card subsets,
    // discard-unless, optional/up-to/any-number forms, reveal/look variants, discard-all and
    // defined-card modes, dynamic or targeted recipients, mixed discard/draw chains, replacement
    // effects, known/revealed card quality, and graveyard, madness, reanimation, or discard-cost
    // synergies. Current chosen-discard value assumes the affected player chooses optimally.
    private static final Set<String> SUPPORTED_PARAMS = Set.of(
            "DB", "Defined", "Mode", "NumCards",
            "SpellDescription", "StackDescription");
    private static final Set<String> SUPPORTED_RECIPIENTS = Set.of(
            "You", "Opponent", "Player.Opponent");

    private DiscardOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        if (outcome == null
                || outcome.getApi() != ApiType.Discard
                || outcome.getSubAbility() != null
                || outcome.usesTargeting()
                || !SUPPORTED_PARAMS.containsAll(outcome.getMapParams().keySet())
                || !SUPPORTED_RECIPIENTS.contains(outcome.getParamOrDefault("Defined", "You"))
                || outcome.getParamOrDefault("NumCards", "1").isBlank()) {
            return false;
        }
        final String mode = outcome.getParam("Mode");
        return "Random".equals(mode) || "TgtChoose".equals(mode);
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome,
            final OutcomeEvaluationContext context) {
        try {
            final int requested = AbilityUtils.calculateAmount(outcome.getHostCard(),
                    outcome.getParamOrDefault("NumCards", "1"), outcome);
            if (requested <= 0) {
                return 0;
            }

            int value = 0;
            final boolean random = "Random".equals(outcome.getParam("Mode"));
            final List<Player> recipients = AbilityUtils.getDefinedPlayers(outcome.getHostCard(),
                    outcome.getParamOrDefault("Defined", "You"), outcome);
            for (final Player recipient : recipients) {
                if (!recipient.isInGame() || !recipient.canDiscardBy(outcome, true)) {
                    continue;
                }
                final int handSize = recipient.getCardsIn(ZoneType.Hand).size();
                final int discardValue = random
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
