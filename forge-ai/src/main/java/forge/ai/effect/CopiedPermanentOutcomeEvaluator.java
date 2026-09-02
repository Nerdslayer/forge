package forge.ai.effect;

import java.util.List;
import java.util.Set;

import forge.ai.ComputerUtilCard;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/** Values permanent tokens copied from already-resolved card definitions. */
final class CopiedPermanentOutcomeEvaluator implements OutcomeEvaluator {
    static final CopiedPermanentOutcomeEvaluator INSTANCE =
            new CopiedPermanentOutcomeEvaluator();

    // TODO(effect analysis): Support copies selected through targeting, Choices, populate, or
    // DefinedName, plus temporary, attached, conditional, replacement-modified, and chained
    // copy outcomes. Current valuation requires an already-resolved Defined source.

    private static final Set<String> SUPPORTED_PARAMS = Set.of(
            "DB", "Defined", "Controller", "NumCopies", "TokenTapped",
            "TokenAttacking", "TokenBlocking", "NonLegendary", "SetPower",
            "SetToughness", "AddTypes", "AddKeywords", "AddColors", "SetColor",
            "SetCreatureTypes", "RemoveKeywords", "RemoveCardTypes", "RemoveSubTypes",
            "SetLoyalty", "KeepName", "NewName", "RememberTokens",
            "RememberOriginalTokens", "ImprintTokens", "SpellDescription",
            "StackDescription");

    private CopiedPermanentOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        if (outcome == null || outcome.getApi() != ApiType.CopyPermanent
                || outcome.getSubAbility() != null || outcome.usesTargeting()
                || !outcome.hasParam("Defined")
                || !SUPPORTED_PARAMS.containsAll(outcome.getMapParams().keySet())) {
            return false;
        }
        final String controller = outcome.getParamOrDefault("Controller", "You");
        return "You".equals(controller) || "Opponent".equals(controller);
    }

    static boolean supportsKnownCopy(final SpellAbility outcome) {
        return INSTANCE.supports(outcome);
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome,
            final OutcomeEvaluationContext context) {
        try {
            final int amount = AbilityUtils.calculateAmount(outcome.getHostCard(),
                    outcome.getParamOrDefault("NumCopies", "1"), outcome);
            if (amount <= 0) {
                return 0;
            }

            final List<Card> originals = AbilityUtils.getDefinedCards(outcome.getHostCard(),
                    outcome.getParam("Defined"), outcome);
            final List<Player> controllers = EffectTokenUtils.resolvePlayers(
                    outcome, "Controller", "You");
            if (originals.isEmpty() || controllers.isEmpty()) {
                return 0;
            }

            int value = 0;
            for (final Player controller : controllers) {
                for (final Card original : originals) {
                    if (original.isInstant() || original.isSorcery()) {
                        continue;
                    }
                    final Card token = EffectTokenUtils.createCopyPrototype(
                            outcome, original, controller);
                    final int tokenValue = ComputerUtilCard.evaluatePermanent(
                            context.evaluatingAi(), token);
                    value = EffectMath.add(value,
                            controller.isOpponentOf(context.evaluatingAi())
                                    ? tokenValue : EffectMath.negate(tokenValue));
                }
            }
            return EffectMath.multiply(value, amount);
        } catch (final RuntimeException ignored) {
            return 0;
        }
    }
}
