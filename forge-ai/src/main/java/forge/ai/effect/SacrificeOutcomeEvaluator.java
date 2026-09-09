package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;

import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/** Values fixed self-sacrifice and supplies legal selections to the shared outcome planner. */
final class SacrificeOutcomeEvaluator implements OutcomeEvaluator {
    static final SacrificeOutcomeEvaluator INSTANCE = new SacrificeOutcomeEvaluator();

    // TODO(effect analysis): Optional/random/SacEachValid forms, variable amounts, sacrifice and
    // zone-change replacements, death/leave/sacrifice triggers, commanders and graveyard value,
    // and downstream static-effect changes. The planner handles player choices and sequences.

    private SacrificeOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        if (outcome == null || outcome.getApi() != ApiType.Sacrifice
                || outcome.getSubAbility() != null
                || outcome.hasParam("SacEachValid") || outcome.hasParam("Random")
                || outcome.hasParam("Destroy") || outcome.hasParam("Echo")
                || outcome.hasParam("CumulativeUpkeep")
                || EffectAbilityUtils.hasUnsupportedControlFlow(outcome)) {
            return false;
        }
        return fixedAmount(outcome) > 0;
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome,
            final OutcomeEvaluationContext context) {
        try {
            if (!"Self".equals(outcome.getParamOrDefault("SacValid", "Self"))) {
                return PlannedOutcomeEvaluator.INSTANCE.evaluateOutcome(outcome, context);
            }
            final Card source = context.state() == null ? outcome.getHostCard()
                    : context.state().card(outcome.getHostCard());
            return source != null && source.canBeSacrificedBy(outcome, true)
                    ? CardStateDeltaEvaluator.evaluateDeparture(context, source) : 0;
        } catch (final RuntimeException ignored) {
            // Dynamic, choice-dependent, or malformed sacrifice forms contribute no value.
            return context.unsupported();
        }
    }

    static List<List<Card>> choices(final SpellAbility outcome, final Player player, final OutcomeState state) {
        if (state.unprojectedBoard) { throw new IllegalArgumentException("Unprojected token/copy recipients"); }
        final CardCollection candidates = new CardCollection();
        for (final Card original : state.battlefield(player)) {
            final Card card = state.card(original);
            if (card != null && card.getController() == player) { candidates.add(card); }
        }
        final List<Card> legal = new ArrayList<>(CardLists.filter(
                AbilityUtils.filterListByType(candidates, outcome.getParam("SacValid"), outcome),
                card -> state.createdTokens.contains(card)
                        ? !card.isImmutable() && !card.isPhasedOut()
                                && !forge.game.staticability.StaticAbilityCantSacrifice.cantSacrifice(card, outcome, true)
                        : card.canBeSacrificedBy(outcome, true)));
        final int amount = Math.min(fixedAmount(outcome), legal.size());
        if (outcome.hasParam("StrictAmount") && amount < fixedAmount(outcome)) { return List.of(List.of()); }
        final List<List<Card>> choices = new ArrayList<>();
        sacrificeGroups(legal, amount, 0, new ArrayList<>(), choices);
        return choices;
    }

    private static void sacrificeGroups(final List<Card> legal, final int amount, final int start,
            final List<Card> selected, final List<List<Card>> choices) {
        if (choices.size() > 1024 || amount > 8) { throw new IllegalArgumentException("Sacrifice choice limit exceeded"); }
        if (selected.size() == amount) { choices.add(List.copyOf(selected)); return; }
        for (int i = start; i < legal.size(); i++) {
            selected.add(legal.get(i));
            sacrificeGroups(legal, amount, i + 1, selected, choices);
            selected.remove(selected.size() - 1);
        }
    }

    private static int fixedAmount(final SpellAbility outcome) {
        try {
            return Integer.parseInt(outcome.getParamOrDefault("Amount", "1"));
        } catch (final NumberFormatException ignored) {
            return 0;
        }
    }
}
