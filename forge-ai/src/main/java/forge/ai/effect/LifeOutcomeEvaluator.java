package forge.ai.effect;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/** Values mandatory life-gain, life-loss, and drain consequence outcomes. */
final class LifeOutcomeEvaluator implements OutcomeEvaluator {
    static final LifeOutcomeEvaluator INSTANCE = new LifeOutcomeEvaluator();

    // TODO(effect analysis): Support targeted and broader dynamic recipients, optional/conditional forms,
    // replacement-modified amounts, life payment/exchange/set effects, mixed subability chains,
    // shared-life variants, unequal multiplayer win probabilities, and resources that leave with
    // an eliminated player. Current game-loss prevention is checked without simulating replacements.
    private static final Set<String> SUPPORTED_PARAMS = Set.of(
            "DB", "Defined", "LifeAmount", "SubAbility",
            "SpellDescription", "StackDescription");
    private static final Set<String> SUPPORTED_RECIPIENTS = Set.of(
            "You", "Opponent", "Player.Opponent",
            "TriggeredPlayer", "TriggeredCardController");

    private LifeOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        if (outcome == null) {
            return false;
        }
        SpellAbility current = outcome;
        while (current != null) {
            if (!supportsPart(current)) {
                return false;
            }
            current = current.getSubAbility();
        }
        return true;
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome,
            final OutcomeEvaluationContext context) {
        try {
            final Map<Player, Integer> projectedLife = new LinkedHashMap<>();
            int lastLifeLost = 0;
            SpellAbility current = outcome;
            while (current != null) {
                final boolean losesLife = current.getApi() == ApiType.LoseLife;
                if (losesLife) {
                    lastLifeLost = 0;
                }
                final String amountDefinition = current.getParam("LifeAmount");
                final int amount = "AFLifeLost".equals(amountDefinition)
                        ? lastLifeLost : AbilityUtils.calculateAmount(current.getHostCard(),
                                amountDefinition, current);
                if (amount > 0) {
                    final List<Player> recipients = AbilityUtils.getDefinedPlayers(
                            current.getHostCard(), current.getParamOrDefault("Defined", "You"), current);
                    int lostThisPart = 0;
                    for (final Player recipient : recipients) {
                        if (!recipient.isInGame()
                                || (losesLife ? !recipient.canLoseLife() : !recipient.canGainLife())) {
                            continue;
                        }
                        final int before = projectedLife.getOrDefault(recipient, recipient.getLife());
                        final int after = losesLife
                                ? saturatedSubtract(before, amount) : saturatedAdd(before, amount);
                        projectedLife.put(recipient, after);
                        if (losesLife) {
                            lostThisPart = EffectMath.add(lostThisPart, amount);
                        }
                    }
                    if (losesLife) {
                        lastLifeLost = lostThisPart;
                    }
                }
                current = current.getSubAbility();
            }

            return PlayerLifeOutcomeValue.evaluate(context.evaluatingAi(), projectedLife);
        } catch (final RuntimeException ignored) {
            return 0;
        }
    }

    private static boolean supportsPart(final SpellAbility outcome) {
        return (outcome.getApi() == ApiType.GainLife || outcome.getApi() == ApiType.LoseLife)
                && !outcome.usesTargeting()
                && SUPPORTED_PARAMS.containsAll(outcome.getMapParams().keySet())
                && SUPPORTED_RECIPIENTS.contains(outcome.getParamOrDefault("Defined", "You"))
                && outcome.hasParam("LifeAmount")
                && !outcome.getParam("LifeAmount").isBlank();
    }

    private static int saturatedAdd(final int left, final int right) {
        final long result = (long) left + right;
        return result >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static int saturatedSubtract(final int left, final int right) {
        return PlayerLifeOutcomeValue.saturatedSubtract(left, right);
    }
}
