package forge.ai.combat;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

/** One public survivor-board follow-up. Inner block calls explicitly have no further lookahead. */
public final class CombatSafetyEvaluator {
    private CombatSafetyEvaluator() { }

    public record FollowUp(boolean supported, boolean searchExhaustive, int nonterminalUtility,
            boolean lethalOpportunity, List<Integer> attackers, List<String> reasons) {
        public FollowUp { attackers = List.copyOf(attackers); reasons = List.copyOf(reasons); }
    }

    /** Optional scalar continuation for the explicit two-turn heuristic, never a recursive policy. */
    public record Continuation(PublicCombatSnapshot snapshot, CombatProjection projection, PreparedCombatValuation values) { }

    public record Forecast(FollowUp evaluation, Optional<Continuation> continuation) { }

    /** Ordinary speculative combat value gets half weight; lethal protection is ranked separately. */
    public static int discountedFollowUpValue(final long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(value * 0.5)));
    }

    /** A heuristic opportunity is not an actual win; current terminal ranking remains separate. */
    public static int incrementalPressure(final FollowUp baseline, final FollowUp candidate) {
        if (!baseline.supported() || !baseline.searchExhaustive() || !candidate.supported() || !candidate.searchExhaustive()) {
            throw new IllegalArgumentException("Complete comparable follow-up estimates required");
        }
        final long delta = (long) candidate.nonterminalUtility() - baseline.nonterminalUtility();
        final int ordinary = discountedFollowUpValue(delta);
        final int lethal = candidate.lethalOpportunity() == baseline.lethalOpportunity() ? 0
                : candidate.lethalOpportunity() ? 500 : -500;
        return CombatOutcomePredictor.add(ordinary, lethal);
    }

    public static FollowUp nextAttack(final PublicCombatSnapshot before, final CombatProjection current,
            final PublicCombatReadiness readiness, final PreparedCombatValuation values, final CombatSearchBudget budget) {
        return forecastNextAttack(before, current, readiness, values, budget).evaluation();
    }

    /** Same bounded forecast, retaining the selected public continuation without redoing search. */
    public static Forecast forecastNextAttack(final PublicCombatSnapshot before, final CombatProjection current,
            final PublicCombatReadiness readiness, final PreparedCombatValuation values, final CombatSearchBudget budget) {
        if (!current.supported() || !current.available() || current.terminal() != CombatProjection.Terminal.NONE) {
            return new Forecast(rejected("No continuing supported combat state"), Optional.empty());
        }
        if (!readiness.unsupportedReasons().isEmpty()) {
            return new Forecast(new FollowUp(false, false, 0, false, List.of(), readiness.unsupportedReasons()), Optional.empty());
        }
        if (readiness.nextActivePlayerId() != before.defendingPlayerId()) {
            return new Forecast(rejected("Immediate reply timing differs from ordinary alternating turns"), Optional.empty());
        }
        final int active = readiness.nextActivePlayerId();
        final int defending = before.attackingPlayerId();
        final boolean observingAttacker = before.observingPlayerId() == active;
        final Map<Integer, PublicCombatSnapshot.Creature> creatures = new LinkedHashMap<>();
        current.survivors().forEach((id, survivor) -> {
            final PublicCombatSnapshot.Creature card = before.creatureAfterLosses(id, current.lostCreatures());
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
        if (!before.triggers().isEmpty() && !resources.isEmpty()) {
            final CombatPlayerResources nextPlayer = resources.get(active);
            if (nextPlayer == null || nextPlayer.librarySize() < 1) {
                return new Forecast(rejected("Normal draw before reply could exhaust the projected library"), Optional.empty());
            }
            // Public count-only normal draw, not a hidden-card guess or additional utility bonus.
            resources.put(active, new CombatPlayerResources(CombatOutcomePredictor.add(nextPlayer.handSize(), 1), nextPlayer.librarySize() - 1));
        }
        final List<Integer> eligible = creatures.keySet().stream()
                .filter(id -> creatures.get(id).controllerId() == active && !creatures.get(id).tapped()
                        && readiness.canAttackNextTurn().contains(id)).sorted().toList();
        final var declarations = CombatAttackCandidates.generate(eligible);
        boolean exhaustive = declarations.exhaustive();
        boolean lethal = false;
        int bestUtility = 0; // A legal no-attack baseline; admitted mechanics have no compulsory attacks.
        List<Integer> chosen = List.of();
        Continuation continuation = null;
        final Set<String> reasons = new LinkedHashSet<>(readiness.ignoredEffects());
        for (final List<Integer> group : declarations.groups()) {
            if (!budget.tryConsume()) { exhaustive = false; reasons.add("Follow-up shared budget exhausted"); break; }
            final Map<Integer, Integer> attacks = new LinkedHashMap<>();
            final Map<Integer, Set<Integer>> blockers = new LinkedHashMap<>();
            for (final int id : group) {
                attacks.put(id, defending);
                blockers.put(id, readiness.blockPairsNextTurn().getOrDefault(id, Set.of()).stream()
                        .filter(blocker -> creatures.containsKey(blocker) && creatures.get(blocker).controllerId() == defending
                                && !creatures.get(blocker).tapped()).collect(java.util.stream.Collectors.toSet()));
            }
            final PublicCombatSnapshot next = new PublicCombatSnapshot(before.observingPlayerId(), active, defending,
                    creatures, players, attacks, blockers, List.of(), List.of(), before.legacyDamageOrder(), resources,
                    before.triggers().stream().filter(trigger -> creatures.containsKey(trigger.ability().sourceId())).toList(), Set.of(), Set.of(),
                    before.preventionRules().stream().filter(rule -> !current.lostCreatures().contains(rule.providerId())).toList(),
                    before.staticWorlds().surviving(current.lostCreatures()), readiness.ignoredEffects());
            final CombatBlockSearch.Result response = CombatBlockSearch.search(next, survivingValues, budget);
            exhaustive &= response.searchExhaustive();
            if (!response.outcomeDomainComplete() || response.best().isEmpty()) {
                return new Forecast(new FollowUp(false, false, 0, false, List.of(), response.reasons()), Optional.empty());
            }
            final var candidate = response.best().orElseThrow();
            final int utility = CombatOutcomePredictor.add(CombatOutcomePredictor.add(candidate.score().permanentLoss().total(),
                    candidate.score().lifeUtility()), candidate.score().outcomeUtility());
            final boolean groupLethal = response.searchExhaustive() && candidate.projection().terminal()
                    == (observingAttacker ? CombatProjection.Terminal.WIN : CombatProjection.Terminal.LOSS);
            if (continuation == null && group.isEmpty()) {
                continuation = new Continuation(next, candidate.projection(), survivingValues);
            }
            if (groupLethal && !lethal || groupLethal == lethal && (observingAttacker ? utility > bestUtility : utility < bestUtility)) {
                bestUtility = utility;
                chosen = group;
                continuation = new Continuation(next, candidate.projection(), survivingValues);
            }
            lethal |= groupLethal;
        }
        if (!exhaustive) { reasons.add("Bounded next-combat estimate, not a complete future-game prediction"); }
        // TODO: Scheduled state-changing opportunities, static layers beyond fixed additive P/T,
        // untap/turn-skipping rules and alternative equally ranked public reply continuations.
        return new Forecast(new FollowUp(true, exhaustive, bestUtility, lethal, chosen, List.copyOf(reasons)),
                Optional.ofNullable(continuation));
    }

    private static FollowUp rejected(final String reason) {
        return new FollowUp(false, false, 0, false, List.of(), List.of(reason));
    }
}
