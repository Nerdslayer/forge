package forge.ai.effect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.ai.ComputerUtilCombat;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/** Values mandatory damage to players after currently predictable prevention and replacement. */
final class PlayerDamageOutcomeEvaluator implements OutcomeEvaluator {
    static final PlayerDamageOutcomeEvaluator INSTANCE = new PlayerDamageOutcomeEvaluator();

    private static final Set<String> SUPPORTED_DEAL_DAMAGE_PARAMS = Set.of(
            "DB", "Defined", "NumDmg", "DamageSource",
            "ValidTgts", "ValidTgtsDesc", "TgtPrompt", "TargetMin", "TargetMax",
            "SpellDescription", "StackDescription");
    private static final Set<String> SUPPORTED_DAMAGE_ALL_PARAMS = Set.of(
            "DB", "ValidPlayers", "ValidCards", "ValidDescription", "NumDmg", "DamageSource",
            "SpellDescription", "StackDescription");
    private static final Set<String> SUPPORTED_RECIPIENTS = Set.of(
            "You", "Player", "Opponent", "Player.Opponent");
    private static final Set<String> SUPPORTED_TARGETS = Set.of(
            "Player", "Opponent", "Player.Opponent");

    // TODO(effect analysis): Support combat damage; planeswalker/battle and creature damage;
    // targeted forms with multiple legal player choices; random, optional, divided, excess, and
    // redirected damage; non-Self damage sources; no-prevention and replacement-modified amounts;
    // infect/poison value; dynamic player definitions; and damage embedded in subability chains.
    // DamageAll currently values only its player recipients even when it also damages cards.

    private PlayerDamageOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        if (outcome == null || outcome.getSubAbility() != null
                || EffectAbilityUtils.hasUnsupportedControlFlow(outcome)
                || !outcome.hasParam("NumDmg") || outcome.getParam("NumDmg").isBlank()
                || !"Self".equals(outcome.getParamOrDefault("DamageSource", "Self"))) {
            return false;
        }
        if (outcome.getApi() == ApiType.DealDamage) {
            return SUPPORTED_DEAL_DAMAGE_PARAMS.containsAll(outcome.getMapParams().keySet())
                    && PlayerRecipientResolver.hasSupportedTargetShape(outcome)
                    && (outcome.usesTargeting()
                            ? SUPPORTED_TARGETS.contains(outcome.getParam("ValidTgts"))
                            : SUPPORTED_RECIPIENTS.contains(
                                    outcome.getParamOrDefault("Defined", "Self")));
        }
        return outcome.getApi() == ApiType.DamageAll
                && !outcome.usesTargeting()
                && SUPPORTED_DAMAGE_ALL_PARAMS.containsAll(outcome.getMapParams().keySet())
                && outcome.hasParam("ValidPlayers")
                && SUPPORTED_RECIPIENTS.contains(outcome.getParam("ValidPlayers"));
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome,
            final OutcomeEvaluationContext context) {
        try {
            final int nominalDamage = AbilityUtils.calculateAmount(
                    outcome.getHostCard(), outcome.getParam("NumDmg"), outcome);
            if (nominalDamage <= 0) {
                return 0;
            }

            final Card source = outcome.getHostCard();
            final Map<Player, Integer> projectedLife = new LinkedHashMap<>();
            for (final Player recipient : resolveRecipients(outcome)) {
                if (!recipient.isInGame() || !recipient.canLoseLife()
                        || source.isInfectDamage(recipient)) {
                    continue;
                }
                final int predictedDamage = ComputerUtilCombat.predictDamageTo(
                        recipient, nominalDamage, source, false);
                if (predictedDamage <= 0
                        || LifeLossPrediction.hasApplicableLifeReductionReplacement(
                                recipient, predictedDamage, true)) {
                    continue;
                }
                final int before = projectedLife.getOrDefault(recipient, recipient.getLife());
                projectedLife.put(recipient,
                        PlayerLifeOutcomeValue.saturatedSubtract(before, predictedDamage));
            }
            return PlayerLifeOutcomeValue.evaluate(context.evaluatingAi(), projectedLife);
        } catch (final RuntimeException ignored) {
            return 0;
        }
    }

    private static List<Player> resolveRecipients(final SpellAbility outcome) {
        if (outcome.getApi() == ApiType.DealDamage) {
            return PlayerRecipientResolver.resolve(outcome);
        }
        return new ArrayList<>(AbilityUtils.getDefinedPlayers(
                outcome.getHostCard(), outcome.getParam("ValidPlayers"), outcome));
    }
}
