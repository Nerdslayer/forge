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

/** Extracts fixed scry and surveil events from known live abilities. */
final class ScrySurveilProductionExtractor implements EffectProductionExtractor {
    static final ScrySurveilProductionExtractor INSTANCE =
            new ScrySurveilProductionExtractor();

    // TODO(effect analysis): Account for scry/surveil replacement effects, optional and dynamic
    // amounts, filtering quality, exact top-library state, and downstream graveyard movement.
    // First-time trigger limits are represented from the current turn only; future turn resets
    // and repeated activations remain conservative follow-up work.
    private ScrySurveilProductionExtractor() {
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
        final List<EffectEvent> events = new ArrayList<>();
        SpellAbility current = opportunity.root();
        while (current != null) {
            if (EffectAbilityUtils.hasUnsupportedControlFlow(current)) {
                return List.of();
            }
            if (current.getApi() == ApiType.Scry || current.getApi() == ApiType.Surveil) {
                final int amount = amount(source, current);
                if (amount <= 0 || !isSupported(current)) {
                    return List.of();
                }
                events.addAll(createEvents(source, current, amount));
            }
            current = current.getSubAbility();
        }
        return events.isEmpty() ? List.of() : List.of(new EffectProduction(source,
                EffectType.SCRIED_OR_SURVEILLED, events, opportunity.expectedBatches()));
    }

    private static List<EffectEvent> createEvents(final Card source,
            final SpellAbility scryOrSurveil, final int amount) {
        scryOrSurveil.setActivatingPlayer(source.getController());
        final List<EffectEvent> events = new ArrayList<>();
        for (final Player recipient : PlayerRecipientResolver.resolve(scryOrSurveil)) {
            if (recipient == null || !recipient.isInGame()) {
                continue;
            }
            final Map<AbilityKey, Object> parameters = new EnumMap<>(AbilityKey.class);
            parameters.put(AbilityKey.Player, recipient);
            parameters.put(AbilityKey.ScryNum, amount);
            parameters.put(AbilityKey.ScryBottom, 0);
            if (scryOrSurveil.getApi() == ApiType.Surveil) {
                parameters.put(AbilityKey.FirstTime, recipient.getSurveilThisTurn() == 0);
            }
            events.add(new EffectEvent(EffectType.SCRIED_OR_SURVEILLED, recipient,
                    List.of(new EffectEvent.Subject(recipient, 1)), parameters));
        }
        return events;
    }

    private static int amount(final Card source, final SpellAbility scryOrSurveil) {
        final String parameter = scryOrSurveil.getApi() == ApiType.Scry ? "ScryNum" : "Amount";
        return AbilityUtils.calculateAmount(source,
                scryOrSurveil.getParamOrDefault(parameter, "1"), scryOrSurveil);
    }

    private static boolean isSupported(final SpellAbility scryOrSurveil) {
        return !scryOrSurveil.hasParam("Optional")
                && PlayerRecipientResolver.hasSupportedTargetShape(scryOrSurveil);
    }
}
