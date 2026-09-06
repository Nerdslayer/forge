package forge.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import forge.game.player.Player;

/**
 * Converts player resources and projected game-result changes into the permanent-evaluation point scale.
 */
public final class PlayerResourceValueEvaluator {
    /** Approximate value of one unrestricted mana in permanent-evaluation points. */
    public static final int MANA_VALUE = 35;
    /** Strategic value of a game win or loss caused by the evaluated outcome. */
    public static final int TERMINAL_GAME_VALUE = 10_000;

    private static final double LIFE_UTILITY_SCALE = 290.0;

    private static final int EMPTY_HAND_CARD_VALUE = 140;
    private static final int CARD_VALUE_LOSS_PER_EXISTING_CARD = 12;
    private static final int MINIMUM_CARD_VALUE = 60;

    private PlayerResourceValueEvaluator() {
    }

    /**
     * Returns the marginal value of adding one unknown, mid-power card to a hand of the given size.
     * The declining value reflects that the first playable option matters more than another option in
     * an already-full hand. At three cards the value is 104, approximately three mana at 35 each.
     */
    public static int evaluateNextCard(final int currentHandSize) {
        final long decliningValue = (long) EMPTY_HAND_CARD_VALUE
                - (long) CARD_VALUE_LOSS_PER_EXISTING_CARD * Math.max(0, currentHandSize);
        return (int) Math.max(MINIMUM_CARD_VALUE, decliningValue);
    }

    /** Returns the value of drawing {@code amount} unknown cards into the current hand. */
    public static int evaluateCardDraw(final int currentHandSize, final int amount) {
        if (amount <= 0) {
            return 0;
        }
        long value = 0;
        for (int i = 0; i < amount; i++) {
            value += evaluateNextCard(saturatedAdd(currentHandSize, i));
            if (value >= Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) value;
    }

    /**
     * Returns the value lost by discarding unknown cards at random. Removing cards reverses the
     * same marginal hand-value curve used for drawing them.
     */
    public static int evaluateRandomDiscard(final int currentHandSize, final int amount) {
        final int handSize = Math.max(0, currentHandSize);
        final int discarded = Math.min(handSize, Math.max(0, amount));
        return evaluateCardDraw(handSize - discarded, discarded);
    }

    /**
     * Returns the value lost when the affected player chooses which cards to discard. Choice makes
     * a small discard substantially less harmful, while losing the whole hand approaches random
     * discard value because there is no useful selection left.
     */
    public static int evaluateChosenDiscard(final int currentHandSize, final int amount) {
        final int handSize = Math.max(0, currentHandSize);
        final int discarded = Math.min(handSize, Math.max(0, amount));
        if (discarded == 0) {
            return 0;
        }
        final double choiceMultiplier = 0.5 + 0.5 * discarded / handSize;
        return saturatedRound(evaluateRandomDiscard(handSize, discarded) * choiceMultiplier);
    }

    /** Returns the value of gaining the given amount of unrestricted, immediately usable mana. */
    public static int evaluateMana(final int amount) {
        if (amount <= 0) {
            return 0;
        }
        final long value = (long) MANA_VALUE * amount;
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    /**
     * Returns the signed value to the affected player of moving between two life totals.
     * Positive values are gains and negative values are losses. Game elimination is separate.
     */
    public static int evaluateLifeChange(final int before, final int after) {
        return saturatedRound(lifeUtility(after) - lifeUtility(before));
    }

    /**
     * Returns the signed strategic value to {@code perspective} of the projected eliminations.
     * A final win or loss receives the full terminal value. In a continuing multiplayer game,
     * removing an opposing side uses the equal-competitor win-probability approximation.
     */
    public static int evaluateEliminations(final Player perspective,
            final Collection<Player> eliminatedPlayers) {
        if (perspective == null || eliminatedPlayers == null || eliminatedPlayers.isEmpty()) {
            return 0;
        }

        final List<Player> activePlayers = new ArrayList<>(perspective.getGame().getPlayers());
        final int competitorsBefore = countCompetingSides(activePlayers);
        activePlayers.removeAll(eliminatedPlayers);
        final int competitorsAfter = countCompetingSides(activePlayers);
        if (competitorsAfter == 0) {
            return 0; // Every remaining side losing simultaneously produces a draw.
        }

        final boolean perspectiveSideSurvives = activePlayers.stream()
                .anyMatch(perspective::sameTeam);
        if (!perspectiveSideSurvives) {
            return -TERMINAL_GAME_VALUE;
        }
        if (competitorsAfter == 1) {
            return TERMINAL_GAME_VALUE;
        }
        return evaluateCompetitorReduction(competitorsBefore, competitorsAfter);
    }

    /** Values a nonterminal reduction in equally likely competing players or teams. */
    public static int evaluateCompetitorReduction(final int before, final int after) {
        if (before <= 1 || after <= 0 || after >= before) {
            return 0;
        }
        return saturatedRound(TERMINAL_GAME_VALUE
                * (1.0 / after - 1.0 / before));
    }

    private static double lifeUtility(final int life) {
        return LIFE_UTILITY_SCALE * Math.log(Math.max(1, life));
    }

    private static int countCompetingSides(final List<Player> players) {
        final List<Player> representatives = new ArrayList<>();
        for (final Player player : players) {
            if (representatives.stream().noneMatch(player::sameTeam)) {
                representatives.add(player);
            }
        }
        return representatives.size();
    }

    private static int saturatedRound(final double value) {
        if (value >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) Math.round(value);
    }

    private static int saturatedAdd(final int left, final int right) {
        final long result = (long) left + right;
        return result >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }
}
