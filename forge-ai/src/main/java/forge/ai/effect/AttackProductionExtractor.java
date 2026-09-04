package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.ai.AttackLikelihoodEvaluator;
import forge.ai.NextCombatPrediction;
import forge.game.GameEntity;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

/** Produces a normalized event for a permanent expected to attack next turn. */
final class AttackProductionExtractor implements EffectProductionExtractor {
    static final AttackProductionExtractor INSTANCE = new AttackProductionExtractor();

    // TODO(effect analysis): Add declaration/once-per-combat groups, attacking alone, complete
    // public attack-group prediction, combat changes after declaration, attack costs, and
    // likelihood weighting between the current expected/unexpected categories.

    private AttackProductionExtractor() {
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source) {
        if (!source.isInPlay() || source.isPhasedOut()) {
            return List.of();
        }

        final List<EffectEvent> events = new ArrayList<>();
        if (AttackLikelihoodEvaluator.estimateNextTurn(evaluatingAi, source).isExpected()) {
            events.add(createAttackEvent(evaluatingAi, source));
            addAttackerDispositionEvents(events, evaluatingAi, source);
        }
        addBlockEvent(events, evaluatingAi, source);
        return events.isEmpty() ? List.of() : List.of(new EffectProduction(
                source, EffectType.ATTACKED_OR_BLOCKED, events, 1));
    }

    private static EffectEvent createAttackEvent(final Player evaluatingAi, final Card source) {
        GameEntity attacked = evaluatingAi;
        Player defendingPlayer = evaluatingAi;
        if (source.getController() == evaluatingAi) {
            for (final Player opponent : evaluatingAi.getOpponents()) {
                final Combat combat = NextCombatPrediction.predict(
                        evaluatingAi, evaluatingAi, opponent);
                if (combat != null && combat.isAttacking(source)) {
                    attacked = combat.getDefenderByAttacker(source);
                    defendingPlayer = combat.getDefenderPlayerByAttacker(source);
                    break;
                }
            }
        }
        final Map<AbilityKey, Object> runParams = new EnumMap<>(AbilityKey.class);
        runParams.put(AbilityKey.Attacker, source);
        runParams.put(AbilityKey.Attacked, attacked);
        runParams.put(AbilityKey.Defender, attacked);
        runParams.put(AbilityKey.DefendingPlayer, defendingPlayer);
        runParams.put(AbilityKey.OtherAttackers, List.of());
        runParams.put(AbilityKey.Defenders, List.of(attacked));
        return createEvent(source, runParams);
    }

    private static void addAttackerDispositionEvents(final List<EffectEvent> events,
            final Player evaluatingAi, final Card attacker) {
        if (attacker.getController() == evaluatingAi) {
            for (final Player opponent : evaluatingAi.getOpponents()) {
                final Combat combat = NextCombatPrediction.predict(
                        evaluatingAi, evaluatingAi, opponent);
                if (addAttackerDispositionEvent(events, attacker, combat)) {
                    return;
                }
            }
            return;
        }
        addAttackerDispositionEvent(events, attacker, NextCombatPrediction.predict(
                evaluatingAi, attacker.getController(), evaluatingAi));
    }

    private static boolean addAttackerDispositionEvent(final List<EffectEvent> events,
            final Card attacker, final Combat combat) {
        if (combat == null || !combat.isAttacking(attacker)) {
            return false;
        }
        final CardCollection blockers = combat.getBlockers(attacker);
        final Map<AbilityKey, Object> runParams = new EnumMap<>(AbilityKey.class);
        runParams.put(AbilityKey.Attacker, attacker);
        runParams.put(AbilityKey.Defender, combat.getDefenderByAttacker(attacker));
        runParams.put(AbilityKey.DefendingPlayer, combat.getDefenderPlayerByAttacker(attacker));
        if (blockers.isEmpty()) {
            events.add(createEvent(attacker, runParams));
            return true;
        }

        runParams.put(AbilityKey.Blockers, blockers);
        events.add(createEvent(attacker, runParams));
        for (final Card blocker : blockers) {
            final Map<AbilityKey, Object> pairParams = new EnumMap<>(AbilityKey.class);
            pairParams.put(AbilityKey.Attacker, attacker);
            pairParams.put(AbilityKey.Blocker, blocker);
            events.add(createEvent(attacker, pairParams));
        }
        return true;
    }

    private static void addBlockEvent(final List<EffectEvent> events,
            final Player evaluatingAi, final Card blocker) {
        if (blocker.getController() == evaluatingAi) {
            for (final Player opponent : evaluatingAi.getOpponents()) {
                if (addBlockEvent(events, blocker, NextCombatPrediction.predict(
                        evaluatingAi, opponent, evaluatingAi))) {
                    return;
                }
            }
            return;
        }
        addBlockEvent(events, blocker, NextCombatPrediction.predict(
                evaluatingAi, evaluatingAi, blocker.getController()));
    }

    private static boolean addBlockEvent(final List<EffectEvent> events,
            final Card blocker, final Combat combat) {
        if (combat == null || !combat.isBlocking(blocker)) {
            return false;
        }
        final CardCollection attackers = combat.getAttackersBlockedBy(blocker);
        if (attackers.isEmpty()) {
            return false;
        }
        final Map<AbilityKey, Object> runParams = new EnumMap<>(AbilityKey.class);
        runParams.put(AbilityKey.Blocker, blocker);
        runParams.put(AbilityKey.Attackers, attackers);
        events.add(createEvent(blocker, runParams));
        return true;
    }

    private static EffectEvent createEvent(final Card subject,
            final Map<AbilityKey, Object> runParams) {
        return new EffectEvent(EffectType.ATTACKED_OR_BLOCKED,
                subject.getController(), List.of(new EffectEvent.Subject(subject, 1)), runParams);
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final Trigger trigger) {
        return List.of();
    }

    @Override
    public List<EffectProduction> extract(final Player evaluatingAi, final Card source,
            final SpellAbility ability) {
        return List.of();
    }
}
