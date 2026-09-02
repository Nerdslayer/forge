package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;
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
    // ETB, replacement-modified, and additional trigger-origin token productions.

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
        return tokenOutcome == null ? List.of()
                : createProductions(source, tokenOutcome, expectedBatches);
    }

    @Override
    public List<EffectProduction> extract(final Card source, final SpellAbility ability) {
        final SpellAbility copied = EffectAbilityUtils.copyPayableActivatedAbility(source, ability);
        if (copied == null) {
            return List.of();
        }
        final SpellAbility tokenOutcome = findSupportedTokenOutcome(copied);
        return tokenOutcome == null ? List.of()
                : createProductions(source, tokenOutcome, 1);
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
            final List<EffectEvent> events = new ArrayList<>();
            for (final Card token : EffectTokenUtils.createPrototypes(outcome, owner)) {
                final Map<AbilityKey, Object> triggerParameters = new EnumMap<>(AbilityKey.class);
                triggerParameters.put(AbilityKey.Player, owner);
                triggerParameters.put(AbilityKey.Card, token);
                events.add(new EffectEvent(EffectType.TOKEN_CREATED, owner,
                        List.of(new EffectEvent.Subject(token, tokenAmount)), triggerParameters));
            }
            if (!events.isEmpty()) {
                productions.add(new EffectProduction(
                        source, EffectType.TOKEN_CREATED, events, expectedBatches));
            }
        }
        return productions;
    }

    private static boolean hasSupportedOwner(final SpellAbility outcome) {
        final String owner = outcome.getParamOrDefault("TokenOwner", "You");
        return "You".equals(owner) || "Opponent".equals(owner);
    }
}
