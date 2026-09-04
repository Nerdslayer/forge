package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.game.GameEntity;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.card.CardCollectionView;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/** Extracts fixed, non-targeted noncombat damage with currently resolvable recipients. */
final class DamageProductionExtractor implements EffectProductionExtractor {
    static final DamageProductionExtractor INSTANCE = new DamageProductionExtractor();

    private static final Set<String> SUPPORTED_DEFINED_RECIPIENTS = Set.of(
            "Self", "You", "Opponent", "Player", "Player.Opponent");
    private static final Set<String> UNSUPPORTED_DAMAGE_PARAMS = Set.of(
            "CardChoices", "ChooseDamage", "DamageMap", "DivideEvenly",
            "DividerOnResolution", "ExcessDamage", "OptionalDecider", "Radiance",
            "RelativeTarget", "Remove", "ReplaceDyingDefined", "UseDamageMap");

    // TODO(effect analysis): Support targeted, optional, chosen, divided, random, and variable-
    // recipient damage; additional DamageSource definitions; prevention/replacement and excess
    // damage; fight and ordinary combat damage; spells and non-battlefield actions; damage from
    // unsupported trigger origins; and damage created by later consequence chains. DamageAll
    // currently values automatically attacking/blocking recipients exactly like other current
    // battlefield recipients and does not predict state changes before the damage occurs.

    private DamageProductionExtractor() {
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final Trigger trigger) {
        final ProductionOpportunity opportunity = ProductionOpportunity.fromTrigger(
                evaluatingAi, source, trigger);
        if (opportunity == null) {
            return List.of();
        }
        final SpellAbility outcome = findSupportedDamageOutcome(opportunity.root());
        final EffectProduction production = outcome == null ? null
                : createProduction(source, outcome, opportunity.expectedBatches());
        return production == null ? List.of() : List.of(production);
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final SpellAbility ability) {
        final ProductionOpportunity opportunity =
                ProductionOpportunity.fromActivatedAbility(source, ability);
        if (opportunity == null) {
            return List.of();
        }
        final SpellAbility outcome = findSupportedDamageOutcome(opportunity.root());
        final EffectProduction production = outcome == null ? null
                : createProduction(source, outcome, opportunity.expectedBatches());
        return production == null ? List.of() : List.of(production);
    }

    private static SpellAbility findSupportedDamageOutcome(final SpellAbility root) {
        SpellAbility current = root;
        while (current != null) {
            if (EffectAbilityUtils.hasUnsupportedControlFlow(current)) {
                return null;
            }
            if (current.getApi() == ApiType.DealDamage
                    || current.getApi() == ApiType.DamageAll) {
                return isSupported(current) ? current : null;
            }
            current = current.getSubAbility();
        }
        return null;
    }

    private static boolean isSupported(final SpellAbility outcome) {
        if (outcome.usesTargeting() || !outcome.hasParam("NumDmg")
                || outcome.getMapParams().keySet().stream()
                        .anyMatch(UNSUPPORTED_DAMAGE_PARAMS::contains)
                || !"Self".equals(outcome.getParamOrDefault("DamageSource", "Self"))) {
            return false;
        }
        if (outcome.getApi() == ApiType.DealDamage) {
            return SUPPORTED_DEFINED_RECIPIENTS.contains(
                    outcome.getParamOrDefault("Defined", "Self"));
        }
        if (!outcome.hasParam("ValidCards") && !outcome.hasParam("ValidPlayers")) {
            return false;
        }
        return !outcome.hasParam("ValidPlayers")
                || SUPPORTED_DEFINED_RECIPIENTS.contains(outcome.getParam("ValidPlayers"));
    }

    private static EffectProduction createProduction(final Card source,
            final SpellAbility outcome, final int expectedBatches) {
        outcome.setActivatingPlayer(source.getController());
        final int amount = AbilityUtils.calculateAmount(
                source, outcome.getParam("NumDmg"), outcome);
        if (amount <= 0) {
            return null;
        }

        final List<GameEntity> recipients = outcome.getApi() == ApiType.DamageAll
                ? damageAllRecipients(source, outcome) : AbilityUtils.getDefinedEntities(
                        source, outcome.getParamOrDefault("Defined", "Self"), outcome);
        final List<EffectEvent> events = new ArrayList<>();
        for (final GameEntity recipient : recipients) {
            if (recipient instanceof Card card
                    && (!card.isInPlay() || card.isPhasedOut())) {
                continue;
            }
            final Map<AbilityKey, Object> triggerParameters = new EnumMap<>(AbilityKey.class);
            triggerParameters.put(AbilityKey.DamageSource, source);
            triggerParameters.put(AbilityKey.DamageTarget, recipient);
            triggerParameters.put(AbilityKey.DamageAmount, amount);
            triggerParameters.put(AbilityKey.IsCombatDamage, false);
            triggerParameters.put(AbilityKey.Cause, outcome);
            events.add(new EffectEvent(EffectType.DAMAGE_DEALT, source.getController(),
                    List.of(new EffectEvent.Subject(recipient, 1)), triggerParameters));
        }
        return events.isEmpty() ? null : new EffectProduction(
                source, EffectType.DAMAGE_DEALT, events, expectedBatches);
    }

    private static List<GameEntity> damageAllRecipients(final Card source,
            final SpellAbility outcome) {
        final List<GameEntity> recipients = new ArrayList<>();
        if (outcome.hasParam("ValidCards")) {
            final CardCollectionView battlefield = source.getGame().getCardsIn(
                    ZoneType.Battlefield);
            recipients.addAll(AbilityUtils.filterListByType(
                    battlefield, outcome.getParam("ValidCards"), outcome));
        }
        if (outcome.hasParam("ValidPlayers")) {
            recipients.addAll(AbilityUtils.getDefinedPlayers(
                    source, outcome.getParam("ValidPlayers"), outcome));
        }
        return recipients;
    }
}
