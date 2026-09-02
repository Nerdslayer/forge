package forge.ai.effect;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Iterables;

import forge.ai.ComputerUtilCard;
import forge.game.StaticEffect;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.player.Player;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.zone.ZoneType;

/** Evaluates active static abilities as signed changes to their affected cards. */
final class StaticAbilityAnalyzer {
    // TODO(effect analysis): Value automatic changes to the source itself, player-affecting static
    // abilities, restrictions, permissions, costs, replacement effects, and card changes invisible
    // to evaluatePermanent. Those cases currently need AIEffectValue hints or a dedicated evaluator.
    private StaticAbilityAnalyzer() {
    }

    static Map<Card, Integer> evaluateRelationships(final Player evaluatingAi,
            final Iterable<Card> candidates, final EffectAnalysisTrace trace) {
        if (evaluatingAi == null || candidates == null) {
            return Collections.emptyMap();
        }

        final Set<Player> analyzedControllers = new LinkedHashSet<>();
        for (final Card candidate : candidates) {
            if (candidate != null && candidate.getController().isOpponentOf(evaluatingAi)) {
                analyzedControllers.add(candidate.getController());
            }
        }
        if (analyzedControllers.isEmpty()) {
            return Collections.emptyMap();
        }

        final Map<Card, Integer> values = new HashMap<>();
        for (final Player controller : analyzedControllers) {
            for (final Card source : controller.getCardsIn(ZoneType.Battlefield)) {
                for (final StaticAbility ability : Iterables.concat(
                        source.getStaticAbilities(), source.getHiddenStaticAbilities())) {
                    try {
                        final int value = evaluateAbility(evaluatingAi, source, ability, trace);
                        if (value != 0) {
                            addSaturated(values, source, value);
                            trace.staticRelationship(source, value);
                        }
                    } catch (final RuntimeException ignored) {
                        // Card scripts are data. Unknown forms must not disrupt AI decisions.
                    }
                }
            }
        }
        return values;
    }

    private static int evaluateAbility(final Player evaluatingAi, final Card source,
            final StaticAbility ability, final EffectAnalysisTrace trace) {
        if (!ability.checkConditions(StaticAbilityMode.Continuous)) {
            return 0;
        }

        final StaticEffect effect = source.getGame().getStaticEffects().getStaticEffect(ability);
        // AIEffectValue supplements automatic evaluation and is signed from each recipient's
        // perspective: positive helps that card, negative harms it.
        final int hintedValue = ability.hasParam("AIEffectValue")
                ? AbilityUtils.calculateAmount(source, ability.getParam("AIEffectValue"), ability) : 0;
        int relationshipValue = 0;
        for (final Card affected : effect.getAffectedCards()) {
            if (!affected.isInZone(ZoneType.Battlefield)) {
                continue;
            }

            final int automaticValue = affected == source ? 0
                    : evaluateAutomaticDelta(evaluatingAi, affected, effect, ability);
            final int recipientValue = EffectMath.add(automaticValue, hintedValue);
            if (recipientValue == 0) {
                continue;
            }

            final int signedValue = affected.getController().isOpponentOf(evaluatingAi)
                    ? recipientValue : EffectMath.negate(recipientValue);
            trace.staticRecipient(source, affected, automaticValue, hintedValue, signedValue);
            relationshipValue = EffectMath.add(relationshipValue, signedValue);
        }
        return relationshipValue;
    }

    private static int evaluateAutomaticDelta(final Player evaluatingAi, final Card affected,
            final StaticEffect effect, final StaticAbility ability) {
        final int withEffect = ComputerUtilCard.evaluatePermanent(evaluatingAi, affected);
        final Card withoutEffect = CardCopyService.getLKICopy(affected);
        if (affected.getZone() != null) {
            withoutEffect.setZone(affected.getZone());
        }
        removeTrackedChanges(withoutEffect, effect, ability);
        return EffectMath.subtract(withEffect,
                ComputerUtilCard.evaluatePermanent(evaluatingAi, withoutEffect));
    }

    private static void removeTrackedChanges(final Card card, final StaticEffect effect,
            final StaticAbility ability) {
        final long timestamp = effect.getTimestamp();
        final long staticId = ability.getId();

        card.removeChangedTextColorWord(timestamp, staticId);
        card.removeChangedTextTypeWord(timestamp, staticId);
        card.removeChangedName(timestamp, staticId, false);
        card.removeChangedManaCost(timestamp, staticId);
        card.removeColorByText(timestamp, staticId);
        card.removeChangedCardTypesByText(timestamp, staticId);
        card.removeChangedCardTraitsByText(timestamp, staticId);
        card.removeChangedCardKeywordsByText(timestamp, staticId);
        card.removeNewPTbyText(timestamp, staticId);
        card.updateChangedText();
        card.removeChangedCardTypes(timestamp, staticId, false);
        card.removeColor(timestamp, staticId);
        card.removeChangedCardKeywords(timestamp, staticId, true);
        card.removeChangedCardTraits(timestamp, staticId);
        card.removeChangedSVars(timestamp, staticId);
        card.removeNewPT(timestamp, staticId, false);
        card.removePTBoost(timestamp, staticId);
        card.removeHiddenExtrinsicKeywords(timestamp, staticId);
    }

    private static void addSaturated(final Map<Card, Integer> values, final Card card, final int amount) {
        final int result = EffectMath.add(values.getOrDefault(card, 0), amount);
        if (result == 0) {
            values.remove(card);
        } else {
            values.put(card, result);
        }
    }
}
