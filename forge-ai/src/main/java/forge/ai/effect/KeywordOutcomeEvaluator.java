package forge.ai.effect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.spellability.SpellAbility;

/** Values fixed keyword changes and detain through creature evaluation. */
final class KeywordOutcomeEvaluator implements OutcomeEvaluator {
    static final KeywordOutcomeEvaluator INSTANCE = new KeywordOutcomeEvaluator();

    // TODO(effect analysis): Add duration-aware weighting, dynamic/chosen/shared/random keywords,
    // player and non-battlefield recipients, multiple targets, distribution, broader detained
    // permanents, and subability chains. Only restrictions already observed by CreatureEvaluator
    // receive value; other successfully applied keywords naturally produce a zero delta.

    private KeywordOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        if (outcome == null || outcome.getSubAbility() != null
                || EffectAbilityUtils.hasUnsupportedControlFlow(outcome)
                || !isSupportedForm(outcome) || !affectsBattlefield(outcome)) {
            return false;
        }
        if (isGroupEffect(outcome.getApi())) {
            return !outcome.usesTargeting() && !outcome.hasParam("Defined");
        }
        return !outcome.usesTargeting()
                || AffectedCardResolver.supportsSingleBattlefieldTarget(outcome);
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome,
            final OutcomeEvaluationContext context) {
        try {
            final AffectedCardResolver.Resolution resolution = resolveAffectedCards(
                    outcome, context);
            return CardStateDeltaEvaluator.evaluate(outcome, context, resolution,
                    affected -> evaluateCardDelta(outcome, affected, context));
        } catch (final RuntimeException ignored) {
            // Dynamic or malformed script forms contribute no outcome value.
            return 0;
        }
    }

    private static AffectedCardResolver.Resolution resolveAffectedCards(
            final SpellAbility outcome, final OutcomeEvaluationContext context) {
        if (isGroupEffect(outcome.getApi())) {
            return AffectedCardResolver.group(outcome, context, Card::isCreature);
        }
        if (outcome.usesTargeting()) {
            return AffectedCardResolver.targeted(outcome, context, Card::isCreature);
        }
        return AffectedCardResolver.defined(outcome, context, Card::isCreature);
    }

    private static int evaluateCardDelta(final SpellAbility outcome, final Card affected,
            final OutcomeEvaluationContext context) {
        final Card changed = CardCopyService.getLKICopy(affected);
        if (affected.getZone() != null) {
            changed.setZone(affected.getZone());
        }
        applyChange(outcome, changed);
        return CardStateDeltaEvaluator.evaluateChange(context, affected, changed);
    }

    private static void applyChange(final SpellAbility outcome, final Card changed) {
        final long timestamp = outcome.getHostCard().getGame().getTimestamp() + 1;
        if (outcome.getApi() == ApiType.Detain) {
            changed.detain(outcome.getActivatingPlayer());
            return;
        }
        if (outcome.getApi() == ApiType.Debuff) {
            changed.addChangedCardKeywords(List.of(), split(outcome.getParam("Keywords")),
                    false, timestamp, null, false);
            changed.updateKeywordsCache();
            return;
        }

        final List<String> visible = new ArrayList<>();
        final List<String> hidden = new ArrayList<>();
        for (final String keyword : split(outcome.getParam("KW"))) {
            if (keyword.startsWith("HIDDEN")) {
                hidden.add(keyword.substring(7));
            } else {
                visible.add(keyword);
            }
        }
        if (!visible.isEmpty()) {
            changed.addChangedCardKeywords(visible, List.of(), false,
                    timestamp, null, false);
        }
        if (!hidden.isEmpty()) {
            changed.addHiddenExtrinsicKeywords(timestamp, 0, hidden);
        }
        changed.updateKeywordsCache();
    }

    private static boolean isSupportedForm(final SpellAbility outcome) {
        return switch (outcome.getApi()) {
        case Pump, PumpAll -> outcome.hasParam("KW")
                && !outcome.hasParam("NumAtt") && !outcome.hasParam("NumDef")
                && !hasDynamicKeywordParams(outcome);
        case Debuff -> outcome.hasParam("Keywords")
                && !outcome.hasParam("AllSuffixKeywords");
        case Detain -> true;
        default -> false;
        };
    }

    private static boolean hasDynamicKeywordParams(final SpellAbility outcome) {
        return outcome.hasParam("KWChoice") || outcome.hasParam("SharedKeywordsZone")
                || outcome.hasParam("DefinedKW") || outcome.hasParam("DefinedLandwalk")
                || outcome.hasParam("RandomKeyword")
                || outcome.getParam("KW").contains("CardManaCost")
                || outcome.getParam("KW").contains("ConvertedManaCost");
    }

    private static boolean affectsBattlefield(final SpellAbility outcome) {
        if (outcome.getApi() == ApiType.Pump || outcome.getApi() == ApiType.PumpAll) {
            return AffectedCardResolver.affectsOnlyBattlefield(outcome, "PumpZone");
        }
        return true;
    }

    private static boolean isGroupEffect(final ApiType api) {
        return api == ApiType.PumpAll;
    }

    private static List<String> split(final String keywords) {
        return keywords == null || keywords.isEmpty()
                ? List.of() : Arrays.asList(keywords.split(" & "));
    }
}
