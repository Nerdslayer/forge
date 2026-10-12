package forge.ai.combat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

/** Public survivor-board transition; no live game access or hidden-card prediction. */
final class CombatTurnProjectionBuilder {
    private CombatTurnProjectionBuilder() { }

    record Turn(PublicCombatSnapshot alternatives, PreparedCombatValuation values) { }
    record Result(Optional<Turn> turn, List<String> reasons) {
        Result { reasons = List.copyOf(reasons); }
    }

    static Result advance(final PublicCombatSnapshot before, final CombatProjection current,
            final PublicCombatReadiness readiness, final PreparedCombatValuation values) {
        if (!current.supported() || !current.available() || current.terminal() != CombatProjection.Terminal.NONE) {
            return new Result(Optional.empty(), List.of("No continuing supported combat state"));
        }
        if (!readiness.unsupportedReasons().isEmpty()) {
            return new Result(Optional.empty(), readiness.unsupportedReasons());
        }
        if (readiness.nextActivePlayerId() != before.defendingPlayerId()) {
            return new Result(Optional.empty(), List.of("Immediate reply timing differs from ordinary alternating turns"));
        }
        final int active = readiness.nextActivePlayerId();
        final int defending = before.attackingPlayerId();
        final Map<Integer, PublicCombatSnapshot.Creature> creatures = new LinkedHashMap<>();
        current.survivors().forEach((id, survivor) -> {
            final PublicCombatSnapshot.Creature card = before.creatureAfterLosses(id, current.lostPermanents());
            // The next active player untaps; the other side does not. Combat damage clears.
            final boolean attemptsUntap = card.controllerId() == active && survivor.tapped();
            final boolean tapped = survivor.tapped() && (!attemptsUntap || card.stunCounters() > 0);
            final int stun = card.stunCounters() - (attemptsUntap && card.stunCounters() > 0 ? 1 : 0);
            creatures.put(id, new PublicCombatSnapshot.Creature(id, card.controllerId(), card.combatDamage(), card.toughness(),
                    0, false, card.firstStrike(), card.doubleStrike(), card.deathtouch(), card.indestructible(), card.trample(),
                    card.lifelink(), card.vigilance(), tapped, card.minimumBlockers(), card.maximumBlockers(), stun, survivor.shieldCounters()));
        });
        final Map<Integer, PublicCombatSnapshot.LifeState> players = new LinkedHashMap<>();
        before.players().forEach((id, state) -> players.put(id, new PublicCombatSnapshot.LifeState(current.playerLifeAfter().get(id),
                state.canLoseLife(), state.canGainLife(), state.cannotLoseAtZero(), state.cannotWin())));
        // Neither lost edges nor completed current opportunities can be charged again later.
        final PreparedCombatValuation survivingValues = values.afterCombat(before, current);
        final Map<Integer, CombatPlayerResources> resources = new LinkedHashMap<>(current.outcomes().resourcesAfter().isEmpty()
                ? before.resources() : current.outcomes().resourcesAfter());
        final Map<Integer, PublicCombatSnapshot.Planeswalker> walkers = new LinkedHashMap<>();
        before.planeswalkers().forEach((id, walker) -> {
            final int loyalty = current.planeswalkerLoyaltyAfter().getOrDefault(id, walker.loyalty());
            if (loyalty > 0) {
                walkers.put(id, walker.withLoyalty(loyalty));
            }
        });
        if (!before.triggers().isEmpty() && !resources.isEmpty()) {
            final CombatPlayerResources nextPlayer = resources.get(active);
            if (nextPlayer == null || nextPlayer.librarySize() < 1) {
                return new Result(Optional.empty(), List.of("Normal draw before reply could exhaust the projected library"));
            }
            // Public count-only normal draw, not a hidden-card guess or additional utility bonus.
            resources.put(active, new CombatPlayerResources(CombatOutcomePredictor.add(nextPlayer.handSize(), 1), nextPlayer.librarySize() - 1));
        }
        final List<Integer> eligible = creatures.keySet().stream()
                .filter(id -> creatures.get(id).controllerId() == active && !creatures.get(id).tapped()
                        && readiness.canAttackNextTurn().contains(id)).sorted().toList();
        final Map<Integer, Integer> attacks = new LinkedHashMap<>();
        final Map<Integer, Set<Integer>> blockers = new LinkedHashMap<>();
        for (final int id : eligible) {
            // TODO: Reconstruct state-dependent attack/block restrictions when departed sources
            // or cleanup layers change them. Existing frozen readiness is approximate in those cases.
            attacks.put(id, defending);
            blockers.put(id, readiness.blockPairsNextTurn().getOrDefault(id, Set.of()).stream()
                    .filter(blocker -> creatures.containsKey(blocker) && creatures.get(blocker).controllerId() == defending
                            && !creatures.get(blocker).tapped()).collect(java.util.stream.Collectors.toSet()));
        }
        final PublicCombatSnapshot alternatives = new PublicCombatSnapshot(before.observingPlayerId(), active, defending,
                creatures, players, attacks, blockers, List.of(), List.of(), before.legacyDamageOrder(), resources,
                before.triggers().stream().filter(trigger -> !current.lostPermanents().contains(trigger.ability().sourceId())).toList(), Set.of(), Set.of(),
                before.preventionRules().stream().filter(rule -> !current.lostPermanents().contains(rule.providerId())).toList(),
                before.staticWorlds().surviving(current.lostPermanents()), readiness.ignoredEffects(), walkers,
                before.singletonPermutationSafe() && readiness.singletonPermutationSafe());
        return new Result(Optional.of(new Turn(alternatives, survivingValues)), readiness.ignoredEffects());
    }
}
