package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;

import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

/** Extracts currently supported token-created productions. */
final class TokenProductionExtractor implements EffectProductionExtractor {
    static final TokenProductionExtractor INSTANCE = new TokenProductionExtractor();

    // TODO(effect analysis): Support conditional, optional, targeted/dynamic-owner, named-action,
    // replacement-modified, and additional trigger-origin token productions. Token battlefield
    // entry is emitted, but self-ETB abilities on newly created token prototypes are not analyzed.

    private TokenProductionExtractor() {
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final Trigger trigger) {
        final ProductionOpportunity opportunity = ProductionOpportunity.fromTrigger(
                evaluatingAi, source, trigger);
        if (opportunity == null) {
            return List.of();
        }
        final SpellAbility tokenOutcome = findSupportedTokenOutcome(
                opportunity.root());
        return tokenOutcome == null ? List.of()
                : createProductions(source, tokenOutcome, opportunity.expectedBatches());
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final SpellAbility ability) {
        final ProductionOpportunity opportunity =
                ProductionOpportunity.fromActivatedAbility(source, ability);
        if (opportunity == null) {
            return List.of();
        }
        final SpellAbility tokenOutcome = findSupportedTokenOutcome(opportunity.root());
        return tokenOutcome == null ? List.of()
                : createProductions(source, tokenOutcome, opportunity.expectedBatches());
    }

    private static SpellAbility findSupportedTokenOutcome(final SpellAbility root) {
        final SpellAbility outcome = EffectAbilityUtils.findOutcome(root, ApiType.Token);
        if (outcome == null || !outcome.hasParam("TokenScript")
                || !hasSupportedOwner(outcome)) {
            return null;
        }
        for (final String param : outcome.getMapParams().keySet()) {
            if (param.startsWith("Condition") || param.startsWith("Unless")) {
                return null;
            }
        }
        return outcome.getParam("TokenScript").isBlank() ? null : outcome;
    }

    private static List<EffectProduction> createProductions(final Card source,
            final SpellAbility outcome, final int expectedBatches) {
        outcome.setActivatingPlayer(source.getController());
        final int tokenAmount = AbilityUtils.calculateAmount(source,
                outcome.getParamOrDefault("TokenAmount", "1"), outcome);
        if (tokenAmount <= 0) {
            return List.of();
        }

        final List<EffectProduction> productions = new ArrayList<>();
        for (final Player owner : EffectTokenUtils.resolvePlayers(
                outcome, "TokenOwner", "You")) {
            final List<EffectTokenUtils.ProducedToken> tokens = new ArrayList<>();
            for (final Card token : EffectTokenUtils.createPrototypes(outcome, owner)) {
                tokens.add(new EffectTokenUtils.ProducedToken(token, tokenAmount));
            }
            productions.addAll(EffectTokenUtils.createProductions(
                    source, owner, tokens, expectedBatches, outcome));
        }
        return productions;
    }

    private static boolean hasSupportedOwner(final SpellAbility outcome) {
        final String owner = outcome.getParamOrDefault("TokenOwner", "You");
        return "You".equals(owner) || "Opponent".equals(owner);
    }
}
