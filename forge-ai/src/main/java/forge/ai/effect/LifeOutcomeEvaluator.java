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

    // The planner handles both beneficial/harmful targets and mixed sequences.
    // TODO(effect analysis): Support broader dynamic and
    // multiplayer recipients, optional/conditional forms, replacement-modified amounts, life
    // payment/exchange/set effects, shared-life variants, unequal
    // multiplayer win probabilities, and resources that leave with an eliminated player. Current
    // game-loss prevention is checked without simulating replacements.
    private static final Set<String> SUPPORTED_PARAMS = Set.of(
            "DB", "Defined", "LifeAmount", "SubAbility",
            "ValidTgts", "ValidTgtsDesc", "TgtPrompt", "TargetMin", "TargetMax",
            "SpellDescription", "StackDescription");
    private static final Set<String> SUPPORTED_RECIPIENTS = Set.of(
            "You", "Opponent", "Player.Opponent",
            "TriggeredPlayer", "TriggeredCardController");
    private static final Set<String> SUPPORTED_TARGETS = Set.of(
            "Player", "Opponent", "Player.Opponent", "Player.You", "You");

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
            int lastLifeLost = context.state() == null ? 0 : context.state().lastLifeLost;
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
                    final List<Player> recipients = current.usesTargeting()
                            ? PlayerRecipientResolver.resolve(current, context)
                            : AbilityUtils.getDefinedPlayers(current.getHostCard(),
                                    current.getParamOrDefault("Defined", "You"), current);
                    int lostThisPart = 0;
                    for (final Player recipient : recipients) {
                        if (!recipient.isInGame()
                                || (losesLife ? !recipient.canLoseLife() : !recipient.canGainLife())) {
                            continue;
                        }
                        final int before = projectedLife.getOrDefault(recipient, context.state() == null
                                ? recipient.getLife() : context.state().life.getOrDefault(recipient, recipient.getLife()));
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

            if (context.state() != null) { context.state().lastLifeLost = lastLifeLost; }
            return PlayerLifeOutcomeValue.evaluate(context, projectedLife);
        } catch (final RuntimeException ignored) {
            return context.unsupported();
        }
    }

    private static boolean supportsPart(final SpellAbility outcome) {
        if ((outcome.getApi() != ApiType.GainLife && outcome.getApi() != ApiType.LoseLife)
                || !SUPPORTED_PARAMS.containsAll(outcome.getMapParams().keySet())
                || !outcome.hasParam("LifeAmount")
                || outcome.getParam("LifeAmount").isBlank()) {
            return false;
        }
        if (outcome.usesTargeting()) {
            // The planner assigns recipients using the complete outcome value, including gains.
            return PlayerRecipientResolver.hasSupportedTargetShape(outcome)
                    && SUPPORTED_TARGETS.contains(outcome.getParam("ValidTgts"));
        }
        return SUPPORTED_RECIPIENTS.contains(outcome.getParamOrDefault("Defined", "You"))
                || SpellAbilityOutcomePlanner.sharedPlayer(outcome);
    }

    private static int saturatedAdd(final int left, final int right) {
        final long result = (long) left + right;
        return result >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static int saturatedSubtract(final int left, final int right) {
        return PlayerLifeOutcomeValue.saturatedSubtract(left, right);
    }
}
