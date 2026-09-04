package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;

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
    // Token battlefield entry is emitted, but self-ETB abilities on the copy are not analyzed.

    private CopiedTokenProductionExtractor() {
    }

    @Override
    public List<EffectProduction> extract(final Card source, final Trigger trigger) {
        final ProductionOpportunity opportunity = ProductionOpportunity.fromTrigger(source, trigger);
        if (opportunity == null) {
            return List.of();
        }
        return extract(source, opportunity.root(), opportunity.expectedBatches());
    }

    @Override
    public List<EffectProduction> extract(final Card source, final SpellAbility ability) {
        final ProductionOpportunity opportunity =
                ProductionOpportunity.fromActivatedAbility(source, ability);
        return opportunity == null ? List.of()
                : extract(source, opportunity.root(), opportunity.expectedBatches());
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
                final List<EffectTokenUtils.ProducedToken> tokens = new ArrayList<>();
                for (final Card original : originals) {
                    if (original.isInstant() || original.isSorcery()) {
                        continue;
                    }
                    final Card token = EffectTokenUtils.createCopyPrototype(
                            outcome, original, controller);
                    tokens.add(new EffectTokenUtils.ProducedToken(token, amount));
                }
                productions.addAll(EffectTokenUtils.createProductions(
                        source, controller, tokens, expectedBatches, outcome));
            }
            return productions;
        } catch (final RuntimeException ignored) {
            return List.of();
        }
    }
}
