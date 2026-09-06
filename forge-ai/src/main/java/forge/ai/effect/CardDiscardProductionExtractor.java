package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/** Extracts mandatory discard effects with known recipients and calculable amounts. */
final class CardDiscardProductionExtractor implements EffectProductionExtractor {
    static final CardDiscardProductionExtractor INSTANCE = new CardDiscardProductionExtractor();

    // TODO(effect analysis): Support spells and stack objects, discard costs and named actions,
    // targeted/dynamic recipients, discard-all/defined-card and source-controller choice modes,
    // optional/up-to/any-number, restricted subsets, multiple discard steps, replacements, and
    // prediction of chosen or random card characteristics without using hidden information.
    private static final Set<String> SUPPORTED_PARAMS = Set.of(
            "AB", "DB", "Cost", "Defined", "Mode", "NumCards", "RememberDiscarded", "SubAbility",
            "AILogic", "ActivationFirstCombat", "ActivationLimit", "ActivationPhases",
            "ActivationZone", "Planeswalker", "PlayerTurn", "PowerUp", "PrecostDesc",
            "SorcerySpeed", "Ultimate", "SpellDescription", "StackDescription");
    private static final Set<String> SUPPORTED_RECIPIENTS = Set.of(
            "You", "Player", "Opponent", "Player.Opponent");

    private CardDiscardProductionExtractor() {
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final Trigger trigger) {
        final ProductionOpportunity opportunity = ProductionOpportunity.fromTrigger(
                evaluatingAi, source, trigger);
        return opportunity == null ? List.of()
                : extractFromOpportunity(source, opportunity);
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final SpellAbility ability) {
        final ProductionOpportunity opportunity =
                ProductionOpportunity.fromActivatedAbility(source, ability);
        return opportunity == null ? List.of()
                : extractFromOpportunity(source, opportunity);
    }

    private static List<EffectProduction> extractFromOpportunity(final Card source,
            final ProductionOpportunity opportunity) {
        final SpellAbility discard = findSupportedDiscardOutcome(opportunity.root());
        if (discard == null) {
            return List.of();
        }
        discard.setActivatingPlayer(source.getController());
        final int requested = AbilityUtils.calculateAmount(source,
                discard.getParamOrDefault("NumCards", "1"), discard);
        if (requested <= 0) {
            return List.of();
        }

        final List<EffectEvent> events = new ArrayList<>();
        for (final Player recipient : AbilityUtils.getDefinedPlayers(source,
                discard.getParamOrDefault("Defined", "You"), discard)) {
            if (!recipient.isInGame() || !recipient.canDiscardBy(discard, true)) {
                continue;
            }
            final int amount = Math.min(requested,
                    recipient.getCardsIn(ZoneType.Hand).size());
            if (amount <= 0) {
                continue;
            }
            final List<EffectEvent.Subject> subjects = new ArrayList<>();
            for (int i = 0; i < amount; i++) {
                subjects.add(new EffectEvent.Subject(
                        EffectAnalysisCardFactory.createUnknownCard(recipient, ZoneType.Hand), 1));
            }
            final Map<AbilityKey, Object> triggerParameters = new EnumMap<>(AbilityKey.class);
            triggerParameters.put(AbilityKey.Player, recipient);
            triggerParameters.put(AbilityKey.Cause, discard);
            events.add(new EffectEvent(EffectType.CARD_DISCARDED, recipient,
                    subjects, triggerParameters));
        }
        return events.isEmpty() ? List.of() : List.of(new EffectProduction(source,
                EffectType.CARD_DISCARDED, events, opportunity.expectedBatches()));
    }

    private static SpellAbility findSupportedDiscardOutcome(final SpellAbility root) {
        SpellAbility current = root;
        while (current != null) {
            if (EffectAbilityUtils.hasUnsupportedControlFlow(current)) {
                return null;
            }
            if (current.getApi() == ApiType.Discard) {
                final String mode = current.getParam("Mode");
                return !current.usesTargeting()
                        && SUPPORTED_PARAMS.containsAll(current.getMapParams().keySet())
                        && SUPPORTED_RECIPIENTS.contains(
                                current.getParamOrDefault("Defined", "You"))
                        && ("Random".equals(mode) || "TgtChoose".equals(mode))
                        ? current : null;
            }
            current = current.getSubAbility();
        }
        return null;
    }
}
