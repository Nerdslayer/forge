package forge.ai.combat;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/** Explicit current/reply/stressed-next-attack heuristic; never a future-game win certificate. */
public final class CombatTwoTurnPressureEvaluator {
    private CombatTwoTurnPressureEvaluator() { }

    public record Forecast(boolean supported, boolean searchExhaustive, boolean lethalOpportunity,
            List<Integer> removedAttackers, int currentDamage, int nextDamage, int opponentLifeAfterReply, List<String> reasons,
            boolean candidateSearchComplete) {
        public Forecast { removedAttackers = List.copyOf(removedAttackers); reasons = List.copyOf(reasons); }
        public Forecast(final boolean supported, final boolean searchExhaustive, final boolean lethalOpportunity,
                final List<Integer> removedAttackers, final int currentDamage, final int nextDamage,
                final int opponentLifeAfterReply, final List<String> reasons) {
            this(supported, searchExhaustive, lethalOpportunity, removedAttackers, currentDamage, nextDamage,
                    opponentLifeAfterReply, reasons, searchExhaustive);
        }
        public boolean usable() { return supported && candidateSearchComplete; }
        public int value() { return usable() && lethalOpportunity ? 500 : 0; }
    }

    public static Forecast evaluate(final PublicCombatSnapshot before, final CombatProjection current,
            final PublicCombatReadiness readiness, final CombatSafetyEvaluator.Forecast reply, final CombatSearchBudget budget) {
        if (before.observingPlayerId() != before.attackingPlayerId()) {
            throw new IllegalArgumentException("Two-turn pressure requires an observing attacker");
        }
        final int opponent = before.defendingPlayerId();
        final int currentDamage = playerDamage(current, opponent);
        if (!current.supported() || !current.available() || current.terminal() != CombatProjection.Terminal.NONE) {
            return new Forecast(false, false, false, List.of(), currentDamage, 0, 0,
                    List.of("Two-turn pressure requires a continuing supported current combat"));
        }
        if (!reply.evaluation().usable()) {
            return new Forecast(false, false, false, List.of(), currentDamage, 0, 0, reply.evaluation().reasons());
        }
        if (reply.evaluation().lethalOpportunity()) {
            // An avoidable lethal reply always outranks speculative pressure; do no deeper work.
            final int life = reply.continuation().map(state -> state.projection().playerLifeAfter().get(opponent)).orElse(0);
            return new Forecast(true, reply.evaluation().searchExhaustive(), false, List.of(), currentDamage, 0, life,
                    List.of("Two-turn pressure suppressed by the intervening lethal reply"), true);
        }
        if (reply.continuation().isEmpty()) {
            return new Forecast(false, false, false, List.of(), currentDamage, 0, 0,
                    List.of("A supported public reply continuation is required"));
        }
        final var continuation = reply.continuation().orElseThrow();
        final var replyState = continuation.projection();
        final int life = replyState.playerLifeAfter().get(opponent);
        if (replyState.terminal() != CombatProjection.Terminal.NONE) {
            return new Forecast(true, reply.evaluation().searchExhaustive(), false, List.of(), currentDamage, 0, life,
                    List.of("No continuing game after the public reply"), true);
        }
        final var board = continuation.snapshot();
        final List<Integer> removed = replyState.survivors().keySet().stream()
                .filter(id -> board.creatures().get(id).controllerId() == before.observingPlayerId()
                        && readiness.canAttackNextTurn().contains(id))
                .sorted(Comparator.<Integer>comparingLong(id -> attackDamage(board.creatures().get(id))).reversed()
                        .thenComparingInt(Integer::intValue)).limit(2).toList();
        final var survivors = new LinkedHashMap<>(replyState.survivors());
        removed.forEach(survivors::remove);
        final var lost = new LinkedHashSet<>(replyState.lostCreatures());
        lost.addAll(removed);
        // Hypothetical removals are a resilience allowance, not scored extra casualties.
        // nextAttack drops their bodies/edges from the future ledger before scoring.
        final var stressed = new CombatProjection(true, true, List.of(), lost, survivors,
                replyState.playerLifeAfter(), replyState.batches(), CombatProjection.Terminal.NONE, List.of(), replyState.outcomes());
        final var nextReadiness = new PublicCombatReadiness(before.observingPlayerId(), readiness.canAttackNextTurn(),
                readiness.blockPairsNextTurn(), readiness.unsupportedReasons());
        final var next = CombatSafetyEvaluator.forecastNextAttack(board, stressed, nextReadiness, continuation.values(), budget);
        final Optional<CombatSafetyEvaluator.Continuation> result = next.continuation();
        final int nextDamage = result.map(state -> playerDamage(state.projection(), opponent)).orElse(0);
        // Actual updated life (including reply lifelink) determines lethal, not a gross damage sum.
        // TODO: Alternative tied replies, future turn-order changes, scheduled resources/static
        // changes and the separate generic-new-blocker stress case. Do not stack stress models.
        return new Forecast(next.evaluation().supported(), reply.evaluation().searchExhaustive() && next.evaluation().searchExhaustive(),
                next.evaluation().lethalOpportunity(), removed, currentDamage, nextDamage, life, next.evaluation().reasons(),
                next.evaluation().candidateSearchComplete());
    }

    private static long attackDamage(final PublicCombatSnapshot.Creature creature) {
        return (long) creature.combatDamage() * (creature.doubleStrike() ? 2 : 1);
    }

    private static int playerDamage(final CombatProjection projection, final int player) {
        int damage = 0;
        for (final var batch : projection.batches()) {
            damage = CombatOutcomePredictor.add(damage, batch.playerDamage().getOrDefault(player, 0));
        }
        return damage;
    }
}
