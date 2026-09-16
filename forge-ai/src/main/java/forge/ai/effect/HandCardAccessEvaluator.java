package forge.ai.effect;

import java.util.List;

import forge.ai.ComputerUtilMana;
import forge.game.card.Card;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Estimates when a known card in hand can become accessible using currently visible resources.
 * This is deliberately separate from card benefit valuation so an approximate access estimate
 * cannot silently alter the printed card value.
 */
public final class HandCardAccessEvaluator {
    private static final int LOOKAHEAD_TURNS = 3;
    private static final double UNKNOWN_CARD_LAND_PROBABILITY = 0.40;

    private HandCardAccessEvaluator() {
    }

    public record Estimate(int manaCost, int availableMana, int landsInHand,
            double expectedLandsInHand, int knownManaAfterLookahead,
            double expectedManaAfterLookahead, int earliestKnownTurn, boolean castableNow,
            List<String> reasons) {
        public Estimate {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        public boolean canReachWithKnownLands() {
            return earliestKnownTurn <= LOOKAHEAD_TURNS;
        }

        public boolean canReachWithExpectedResources() {
            return expectedManaAfterLookahead >= manaCost;
        }
    }

    /**
     * Estimates access using generic mana and mana-producing lands only.
     *
     * <p>The known-land estimate never inspects hidden cards. For partial information, the
     * expected-land estimate applies a deliberately broad 40% land heuristic to unknown cards;
     * it is diagnostic only and must not be treated as a revealed card or a guaranteed land drop.
     * TODO(unified valuation): account for colored mana, non-land mana sources, alternative costs,
     * cost reducers, playable-land restrictions, and unknown cards drawn before the lookahead
     * horizon.</p>
     */
    public static Estimate evaluate(final Card card, final HandValuationContext context) {
        if (card == null || context == null) {
            return new Estimate(0, 0, 0, 0, 0, 0, Integer.MAX_VALUE, false,
                    List.of("Card access is unavailable without a card and hand context."));
        }
        if (!context.knows(card)) {
            return new Estimate(0, 0, 0, 0, 0, 0, Integer.MAX_VALUE, false,
                    List.of("Card access is unavailable because the card is not known."));
        }
        final Player owner = context.handOwner();
        final int manaCost = Math.max(0, card.getCMC());
        final int availableMana = ComputerUtilMana.getAvailableManaEstimate(owner, false);
        // A known-card-only context must not inspect the owner's hidden hand. In a full-hand
        // context the actual hand is available; otherwise only individually known cards can
        // contribute to the estimate (including the known card that is about to be returned).
        final int landsInHand = context.knowledge() == HandKnowledge.FULL_HAND
                ? CardLists.count(owner.getCardsIn(ZoneType.Hand), CardPredicates.LANDS_PRODUCING_MANA)
                : (int) context.knownCards().stream()
                        .filter(CardPredicates.LANDS_PRODUCING_MANA)
                        .count();
        final double expectedLandsInHand = landsInHand
                + UNKNOWN_CARD_LAND_PROBABILITY * context.unknownCardCount();
        final boolean castableNow = manaCost <= availableMana;
        final int knownManaAfterLookahead = availableMana + Math.min(LOOKAHEAD_TURNS, landsInHand);
        final double expectedManaAfterLookahead = availableMana
                + Math.min(LOOKAHEAD_TURNS, expectedLandsInHand);
        final int earliestKnownTurn;
        if (castableNow) {
            earliestKnownTurn = 0;
        } else if (knownManaAfterLookahead < manaCost) {
            earliestKnownTurn = Integer.MAX_VALUE;
        } else {
            earliestKnownTurn = manaCost - availableMana;
        }

        final String horizon = canReach(earliestKnownTurn)
                ? "known resources reach the cost in " + earliestKnownTurn + " turn(s)"
                : "known resources do not reach the cost within " + LOOKAHEAD_TURNS + " turns";
        final String expected = context.unknownCardCount() == 0
                ? "full hand is known"
                : String.format(java.util.Locale.ROOT,
                        "expected %.2f mana-producing land(s) including %.2f from %d unknown card(s)",
                        expectedLandsInHand,
                        UNKNOWN_CARD_LAND_PROBABILITY * context.unknownCardCount(),
                        context.unknownCardCount());
        return new Estimate(manaCost, availableMana, landsInHand, expectedLandsInHand,
                knownManaAfterLookahead, expectedManaAfterLookahead, earliestKnownTurn,
                castableNow,
                List.of("Generic mana access: " + availableMana + " now, " + landsInHand
                        + " known mana-producing land(s) in hand", expected, horizon));
    }

    private static boolean canReach(final int turn) {
        return turn != Integer.MAX_VALUE;
    }
}
