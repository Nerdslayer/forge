package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import forge.game.GameEntity;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.card.CardCollectionView;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/** Extracts supported noncombat damage with currently resolvable recipients. */
final class DamageProductionExtractor implements EffectProductionExtractor {
    static final DamageProductionExtractor INSTANCE = new DamageProductionExtractor();

    private static final Set<String> SUPPORTED_DEFINED_RECIPIENTS = Set.of(
            "Self", "You", "Opponent", "Player", "Player.Opponent");
    private static final Set<String> SUPPORTED_PLAYER_TARGETS = Set.of(
            "Player", "Opponent", "Player.Opponent");
    private static final Set<String> UNSUPPORTED_DAMAGE_PARAMS = Set.of(
            "CardChoices", "ChooseDamage", "DamageMap", "DivideEvenly",
            "DividerOnResolution", "ExcessDamage", "OptionalDecider", "Radiance",
            "RelativeTarget", "Remove", "ReplaceDyingDefined", "UseDamageMap",
            "NoPrevention");

    // TODO(effect analysis): Support permanent/mixed targeted, optional, chosen, divided, random,
    // and variable-recipient damage; additional DamageSource definitions; replacement and excess
    // damage; fight and blocked/trample combat damage; spells and non-battlefield actions; damage from
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
        if (!outcome.hasParam("NumDmg")
                || outcome.getMapParams().keySet().stream()
                        .anyMatch(UNSUPPORTED_DAMAGE_PARAMS::contains)
                || !"Self".equals(outcome.getParamOrDefault("DamageSource", "Self"))) {
            return false;
        }
        if (outcome.getApi() == ApiType.DealDamage) {
            if (outcome.usesTargeting()) {
                return PlayerRecipientResolver.hasSupportedTargetShape(outcome)
                        && SUPPORTED_PLAYER_TARGETS.contains(outcome.getParam("ValidTgts"));
            }
            return SUPPORTED_DEFINED_RECIPIENTS.contains(
                    outcome.getParamOrDefault("Defined", "Self"));
        }
        if (outcome.usesTargeting()) {
            return false;
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

        final List<? extends GameEntity> recipients = outcome.getApi() == ApiType.DamageAll
                ? damageAllRecipients(source, outcome)
                : outcome.usesTargeting() ? PlayerRecipientResolver.resolve(outcome)
                : AbilityUtils.getDefinedEntities(source,
                        outcome.getParamOrDefault("Defined", "Self"), outcome);
        final List<EffectEvent> events = new ArrayList<>();
        for (final GameEntity recipient : recipients) {
            if (recipient instanceof Card card
                    && (!card.isInPlay() || card.isPhasedOut())) {
                continue;
            }
            final EffectEvent event = DamageEventFactory.create(
                    source, recipient, amount, false, outcome, null);
            if (event != null) {
                events.add(event);
            }
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
