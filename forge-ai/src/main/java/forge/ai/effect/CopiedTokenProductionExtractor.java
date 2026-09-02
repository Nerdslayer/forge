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

/** Extracts token-created events from copies whose source is already resolvable. */
final class CopiedTokenProductionExtractor implements EffectProductionExtractor {
    static final CopiedTokenProductionExtractor INSTANCE =
            new CopiedTokenProductionExtractor();

    // TODO(effect analysis): Support copies selected through targeting, Choices, populate, or
    // DefinedName, plus temporary, attached, conditional, replacement-modified, and unresolved
    // event-dependent copies. This extractor requires an already-resolvable Defined source.

    private CopiedTokenProductionExtractor() {
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
        return extract(source, EffectAbilityUtils.copyTriggerOutcome(source, trigger), expectedBatches);
    }

    @Override
    public List<EffectProduction> extract(final Card source, final SpellAbility ability) {
        final SpellAbility copied = EffectAbilityUtils.copyPayableActivatedAbility(source, ability);
        return copied == null ? List.of() : extract(source, copied, 1);
    }

    private static List<EffectProduction> extract(final Card source,
            final SpellAbility root, final int expectedBatches) {
        final SpellAbility outcome = EffectAbilityUtils.findOutcome(root, ApiType.CopyPermanent);
        if (!CopiedPermanentOutcomeEvaluator.supportsKnownCopy(outcome)) {
            return List.of();
        }
        outcome.setActivatingPlayer(source.getController());

        try {
            final int amount = AbilityUtils.calculateAmount(source,
                    outcome.getParamOrDefault("NumCopies", "1"), outcome);
            final List<Card> originals = AbilityUtils.getDefinedCards(
                    source, outcome.getParam("Defined"), outcome);
            if (amount <= 0 || originals.isEmpty()) {
                return List.of();
            }

            final List<EffectProduction> productions = new ArrayList<>();
            for (final Player controller : EffectTokenUtils.resolvePlayers(
                    outcome, "Controller", "You")) {
                final List<EffectEvent> events = new ArrayList<>();
                for (final Card original : originals) {
                    if (original.isInstant() || original.isSorcery()) {
                        continue;
                    }
                    final Card token = EffectTokenUtils.createCopyPrototype(
                            outcome, original, controller);
                    final Map<AbilityKey, Object> triggerParameters =
                            new EnumMap<>(AbilityKey.class);
                    triggerParameters.put(AbilityKey.Player, controller);
                    triggerParameters.put(AbilityKey.Card, token);
                    events.add(new EffectEvent(EffectType.TOKEN_CREATED, controller,
                            List.of(new EffectEvent.Subject(token, amount)), triggerParameters));
                }
                if (!events.isEmpty()) {
                    productions.add(new EffectProduction(source, EffectType.TOKEN_CREATED,
                            events, expectedBatches));
                }
            }
            return productions;
        } catch (final RuntimeException ignored) {
            return List.of();
        }
    }
}
