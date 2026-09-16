package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/** Extracts fixed mandatory mill events without inspecting hidden library cards. */
final class MilledProductionExtractor implements EffectProductionExtractor {
    static final MilledProductionExtractor INSTANCE = new MilledProductionExtractor();

    // TODO(effect analysis): Support mill costs and spells, optional/up-to/random amounts,
    // variable or player-dependent amounts, non-graveyard destinations, replacement effects,
    // richer recipient definitions, hidden card characteristics, and mill inside unsupported
    // choices or conditional chains.

    private MilledProductionExtractor() {
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final Trigger trigger) {
        final ProductionOpportunity opportunity = ProductionOpportunity.fromTrigger(
                evaluatingAi, source, trigger);
        return opportunity == null ? List.of() : extractFromOpportunity(source, opportunity);
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final SpellAbility ability) {
        final ProductionOpportunity opportunity = ProductionOpportunity.fromActivatedAbility(
                source, ability);
        return opportunity == null ? List.of() : extractFromOpportunity(source, opportunity);
    }

    private static List<EffectProduction> extractFromOpportunity(final Card source,
            final ProductionOpportunity opportunity) {
        final SpellAbility mill = findSupportedMillOutcome(opportunity.root());
        if (mill == null) {
            return List.of();
        }
        mill.setActivatingPlayer(source.getController());
        final int requested = AbilityUtils.calculateAmount(source,
                mill.getParamOrDefault("NumCards", "1"), mill);
        if (requested <= 0) {
            return List.of();
        }

        final List<EffectEvent> events = new ArrayList<>();
        for (final Player recipient : PlayerRecipientResolver.resolve(mill)) {
            if (!recipient.isInGame()) {
                continue;
            }
            final int amount = Math.min(requested,
                    recipient.getCardsIn(ZoneType.Library).size());
            if (amount <= 0) {
                continue;
            }

            final List<EffectEvent.Subject> subjects = new ArrayList<>();
            for (int i = 0; i < amount; i++) {
                subjects.add(new EffectEvent.Subject(
                        EffectAnalysisCardFactory.createUnknownCard(recipient,
                                ZoneType.Graveyard), 1));
            }
            final Map<AbilityKey, Object> parameters = new EnumMap<>(AbilityKey.class);
            parameters.put(AbilityKey.Player, recipient);
            parameters.put(AbilityKey.Cards,
                    new CardCollection(subjects.stream()
                            .map(EffectEvent.Subject::value)
                            .map(Card.class::cast)
                            .toList()));
            parameters.put(AbilityKey.Cause, mill);
            events.add(new EffectEvent(EffectType.CARD_MILLED, recipient, subjects, parameters));
        }
        return events.isEmpty() ? List.of() : List.of(new EffectProduction(source,
                EffectType.CARD_MILLED, events, opportunity.expectedBatches()));
    }

    private static SpellAbility findSupportedMillOutcome(final SpellAbility root) {
        SpellAbility current = root;
        while (current != null) {
            if (EffectAbilityUtils.hasUnsupportedControlFlow(current)) {
                return null;
            }
            if (current.getApi() == ApiType.Mill) {
                return isSupported(current) ? current : null;
            }
            current = current.getSubAbility();
        }
        return null;
    }

    private static boolean isSupported(final SpellAbility mill) {
        if (mill.hasParam("Optional") || mill.hasParam("Upto")
                || mill.hasParam("TargetsAtRandom")) {
            return false;
        }
        if (mill.hasParam("Destination")
                && !ZoneType.Graveyard.name().equalsIgnoreCase(mill.getParam("Destination"))) {
            return false;
        }
        if (mill.usesTargeting() && !PlayerRecipientResolver.hasSupportedTargetShape(mill)) {
            return false;
        }
        return (!mill.hasParam("TargetMin") || "1".equals(mill.getParam("TargetMin")))
                && (!mill.hasParam("TargetMax") || "1".equals(mill.getParam("TargetMax")));
    }
}
