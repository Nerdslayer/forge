package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.ai.ability.TokenAi;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

/** Extracts currently supported token-created productions. */
final class TokenProductionExtractor implements EffectProductionExtractor {
    static final TokenProductionExtractor INSTANCE = new TokenProductionExtractor();

    private TokenProductionExtractor() {
    }

    @Override
    public List<EffectProduction> extract(final Card source, final Trigger trigger) {
        if (!EffectAbilityUtils.isActiveBattlefieldTrigger(source, trigger)) {
            return List.of();
        }
        final int expectedBatches = EffectOccurrenceEstimator.estimateTriggerBatches(source, trigger);
        if (expectedBatches <= 0) {
            return List.of();
        }

        final SpellAbility tokenOutcome = findSupportedTokenOutcome(
                EffectAbilityUtils.copyTriggerOutcome(source, trigger));
        final EffectProduction production = tokenOutcome == null
                ? null : createProduction(source, tokenOutcome, expectedBatches);
        return production == null ? List.of() : List.of(production);
    }

    @Override
    public List<EffectProduction> extract(final Card source, final SpellAbility ability) {
        final SpellAbility copied = EffectAbilityUtils.copyPayableActivatedAbility(source, ability);
        if (copied == null) {
            return List.of();
        }
        final SpellAbility tokenOutcome = findSupportedTokenOutcome(copied);
        final EffectProduction production = tokenOutcome == null
                ? null : createProduction(source, tokenOutcome, 1);
        return production == null ? List.of() : List.of(production);
    }

    private static SpellAbility findSupportedTokenOutcome(final SpellAbility root) {
        final SpellAbility outcome = EffectAbilityUtils.findOutcome(root, ApiType.Token);
        if (outcome == null || !outcome.hasParam("TokenScript")
                || !"You".equals(outcome.getParamOrDefault("TokenOwner", "You"))) {
            return null;
        }
        for (final String param : outcome.getMapParams().keySet()) {
            if (param.startsWith("Condition") || param.startsWith("Unless")) {
                return null;
            }
        }
        return outcome.getParam("TokenScript").isBlank() ? null : outcome;
    }

    private static EffectProduction createProduction(final Card source,
            final SpellAbility outcome, final int expectedBatches) {
        outcome.setActivatingPlayer(source.getController());
        final int tokenAmount = AbilityUtils.calculateAmount(source,
                outcome.getParamOrDefault("TokenAmount", "1"), outcome);
        if (tokenAmount <= 0) {
            return null;
        }

        final List<EffectEvent> events = new ArrayList<>();
        for (final String script : outcome.getParam("TokenScript").split(",")) {
            if (script.isBlank()) {
                return null;
            }
            final SpellAbility tokenAbility = outcome.copy(source, false);
            tokenAbility.setActivatingPlayer(source.getController());
            tokenAbility.getMapParams().put("TokenScript", script.trim());
            final Card token = TokenAi.spawnToken(source.getController(), tokenAbility);
            if (outcome.hasParam("TokenTapped")) {
                token.setTapped(true);
            }

            final Map<AbilityKey, Object> triggerParameters = new EnumMap<>(AbilityKey.class);
            triggerParameters.put(AbilityKey.Player, source.getController());
            triggerParameters.put(AbilityKey.Card, token);
            events.add(new EffectEvent(EffectType.TOKEN_CREATED, source.getController(),
                    List.of(new EffectEvent.Subject(token, tokenAmount)), triggerParameters));
        }
        return events.isEmpty() ? null : new EffectProduction(
                source, EffectType.TOKEN_CREATED, events, expectedBatches);
    }
}
