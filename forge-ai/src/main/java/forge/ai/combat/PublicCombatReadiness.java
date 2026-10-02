package forge.ai.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import forge.game.card.Card;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** Public next-turn eligibility, frozen before search; no controller/hand prediction calls. */
public record PublicCombatReadiness(int nextActivePlayerId, Set<Integer> canAttackNextTurn,
        Map<Integer, Set<Integer>> blockPairsNextTurn, List<String> unsupportedReasons, List<String> ignoredEffects) {
    public PublicCombatReadiness {
        canAttackNextTurn = Set.copyOf(canAttackNextTurn);
        final Map<Integer, Set<Integer>> pairs = new LinkedHashMap<>();
        blockPairsNextTurn.forEach((id, blockers) -> pairs.put(id, Set.copyOf(blockers)));
        blockPairsNextTurn = Map.copyOf(pairs);
        unsupportedReasons = List.copyOf(unsupportedReasons);
        ignoredEffects = List.copyOf(ignoredEffects);
    }

    public PublicCombatReadiness(final int nextActivePlayerId, final Set<Integer> canAttackNextTurn,
            final Map<Integer, Set<Integer>> blockPairsNextTurn, final List<String> unsupportedReasons) {
        this(nextActivePlayerId, canAttackNextTurn, blockPairsNextTurn, unsupportedReasons, List.of());
    }

    public static PublicCombatReadiness capture(final Player observer, final PublicCombatSnapshot snapshot) {
        return captureComplete(observer, snapshot, () -> true);
    }

    /** No partial future eligibility is published when the caller's allowance is exhausted. */
    public static Optional<PublicCombatReadiness> capture(final Player observer, final PublicCombatSnapshot snapshot,
            final BooleanSupplier checkpoint) {
        return Optional.ofNullable(captureComplete(observer, snapshot, checkpoint));
    }

    private static PublicCombatReadiness captureComplete(final Player observer, final PublicCombatSnapshot snapshot,
            final BooleanSupplier checkpoint) {
        if (observer == null || snapshot == null || checkpoint == null) { throw new IllegalArgumentException("Explicit readiness inputs required"); }
        if (!checkpoint.getAsBoolean()) { return null; }
        final List<String> reasons = new ArrayList<>(snapshot.unsupportedReasons());
        final List<String> ignored = new ArrayList<>(snapshot.ignoredEffects());
        if (snapshot.preventionRules().stream().anyMatch(rule -> !rule.survivesCleanup())) {
            reasons.add("Command-zone prevention policy needs duration-aware cleanup before a future attack");
        }
        final Set<Integer> attacks = new LinkedHashSet<>();
        final Map<Integer, Set<Integer>> blocks = new LinkedHashMap<>();
        int scheduledDraws = 0;
        // TODO: Project scheduled life/board changes before the reply instead of freezing them.
        // Pure bounded draws do not change this mechanical forecast; rule out known decking.
        for (final Card card : observer.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (!checkpoint.getAsBoolean()) { return null; }
            if (card.isFaceDown() || card.isPhasedOut()) { continue; }
            for (final var ability : forge.ai.effect.CardAbilityTraversal.inspect(card.getCurrentState())) {
                if (!checkpoint.getAsBoolean()) { return null; }
                if (ability.origin() != forge.ai.effect.CardAbilityTraversal.Origin.TRIGGER
                        || forge.ai.effect.ScheduledTriggerParser.parse(ability.parameters()).isEmpty()) { continue; }
                final var outcome = ability.outcome();
                final String count = outcome.parameters().getOrDefault("NumCards", "1");
                if (!"Draw".equals(outcome.api()) || !outcome.issue().isEmpty() || outcome.next() != null
                        || !outcome.choices().isEmpty() || outcome.parameters().containsKey("ValidTgts")
                        || !count.matches("[0-9]{1,4}")) {
                    ignored.add("Scheduled changes before the next attack are not projected: " + card.getId());
                } else {
                    scheduledDraws += Integer.parseInt(count);
                }
            }
        }
        for (final Player player : observer.getGame().getPlayers()) {
            if (!checkpoint.getAsBoolean()) { return null; }
            if (player.getCardsIn(ZoneType.Library).size() <= scheduledDraws) {
                reasons.add("Normal or scheduled draws may exhaust a library before the projected attack");
            }
        }
        if (scheduledDraws > 0 && !snapshot.triggers().isEmpty()) {
            // TODO: Apply scheduled draws in chronological resource batches before forecasting
            // concrete combat draws. Frozen starting hands would misprice the latter.
            ignored.add("Scheduled draws before combat outcomes need resource projection");
        }
        final List<Card> creatures = observer.getGame().getCardsIn(ZoneType.Battlefield).stream().filter(Card::isCreature).toList();
        for (final Card attacker : creatures) {
            if (!checkpoint.getAsBoolean()) { return null; }
            if (attacker.isPhasedOut()) { reasons.add("Next-turn phasing readiness is not projected"); continue; }
            if (!snapshot.creatures().containsKey(attacker.getId())) { continue; }
            // TODO: Reconstruct duration/layer changes at cleanup rather than carrying a pump,
            // animation or granted keyword forward as if it were permanent.
            if (!forge.ai.effect.CombatStaticProjectionPreparation.hasOnlyPersistentBoosts(attacker) || !attacker.getSetPTTable().isEmpty()
                    || !attacker.getChangedCardKeywords().isEmpty() || attacker.getChangedCardTypes().iterator().hasNext()) {
                ignored.add("Cleanup characteristic changes need a duration-aware projection: " + attacker.getId());
            }
            final Set<Integer> eligible = new LinkedHashSet<>();
            for (final Player opponent : observer.getGame().getPlayers()) {
                if (!checkpoint.getAsBoolean()) { return null; }
                if (attacker.getController().isOpponentOf(opponent) && CombatUtil.canAttackNextTurn(attacker, opponent)
                        && CombatUtil.getAttackCost(observer.getGame(), attacker, opponent) == null) { attacks.add(attacker.getId()); }
            }
            for (final Card blocker : creatures) {
                if (!checkpoint.getAsBoolean()) { return null; }
                if (snapshot.creatures().containsKey(blocker.getId()) && !blocker.isPhasedOut()
                        && attacker.getController().isOpponentOf(blocker.getController())
                        && CombatUtil.canBlock(attacker, blocker, true)
                        && CombatUtil.getBlockCost(observer.getGame(), blocker, attacker) == null) { eligible.add(blocker.getId()); }
            }
            blocks.put(attacker.getId(), eligible);
        }
        if (!checkpoint.getAsBoolean()) { return null; }
        return new PublicCombatReadiness(observer.getGame().getPhaseHandler().getNextTurn().getId(), attacks, blocks, reasons, ignored);
    }
}
