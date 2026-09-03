package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;

import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Values sacrifices whose affected battlefield permanents are known without a choice. */
final class SacrificeOutcomeEvaluator implements OutcomeEvaluator {
    static final SacrificeOutcomeEvaluator INSTANCE = new SacrificeOutcomeEvaluator();

    // TODO(effect analysis): Support player-chosen sacrifices by modeling the affected player's
    // choice, targeted players, optional/random/SacEachValid forms, variable amounts, sacrifice
    // and zone-change replacements, death/leave/sacrifice triggers, commanders and graveyard
    // value, downstream static-effect changes, multiple outcome branches, and subability chains.

    private SacrificeOutcomeEvaluator() {
    }

    @Override
    public boolean supports(final SpellAbility outcome) {
        if (outcome == null || outcome.getApi() != ApiType.Sacrifice
                || outcome.getSubAbility() != null || outcome.usesTargeting()
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
            final List<Card> sacrificed = resolveKnownSacrifices(outcome);
            if (sacrificed.isEmpty()) {
                return 0;
            }

            int value = 0;
            for (final Card card : sacrificed) {
                value = EffectMath.add(value,
                        CardStateDeltaEvaluator.evaluateDeparture(context, card));
            }
            return value;
        } catch (final RuntimeException ignored) {
            // Dynamic, choice-dependent, or malformed sacrifice forms contribute no value.
            return 0;
        }
    }

    private static List<Card> resolveKnownSacrifices(final SpellAbility outcome) {
        final String valid = outcome.getParamOrDefault("SacValid", "Self");
        if ("Self".equals(valid)) {
            final Card source = outcome.getHostCard();
            return source.canBeSacrificedBy(outcome, true)
                    ? List.of(source) : List.of();
        }

        final PlayerCollection affectedPlayers = AbilityUtils.getDefinedPlayers(
                outcome.getHostCard(), outcome.getParamOrDefault("Defined", "Self"), outcome);
        if (affectedPlayers.isEmpty()) {
            return List.of();
        }

        final int amount = fixedAmount(outcome);
        final List<Card> sacrificed = new ArrayList<>();
        for (final Player player : affectedPlayers) {
            final CardCollection battlefield = new CardCollection(
                    player.getCardsIn(ZoneType.Battlefield));
            final CardCollectionView legal = CardLists.filter(
                    AbilityUtils.filterListByType(battlefield, valid, outcome),
                    CardPredicates.canBeSacrificedBy(outcome, true));
            // If there are more legal permanents than the amount, the affected player chooses.
            // Requiring an exact count also avoids guessing undersized/StrictAmount behavior.
            if (legal.size() != amount) {
                return List.of();
            }
            sacrificed.addAll(legal);
        }
        return sacrificed;
    }

    private static int fixedAmount(final SpellAbility outcome) {
        try {
            return Integer.parseInt(outcome.getParamOrDefault("Amount", "1"));
        } catch (final NumberFormatException ignored) {
            return 0;
        }
    }
}
