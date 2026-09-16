package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;

/** Produces target-selection events for the removal ability currently choosing a target. */
final class BecameTargetProductionExtractor {
    // TODO(effect analysis): Support multi-target target sets, target changes, copied or
    // stack-only source objects, and optional/random target selection beyond this bounded
    // removal-target slice. BecomesTargetOnce should also batch a real multi-target event.

    private BecameTargetProductionExtractor() {
    }

    static List<EffectProduction> extract(final Player evaluatingAi,
            final SpellAbility targetingAbility, final Iterable<Card> candidates) {
        if (evaluatingAi == null || targetingAbility == null || !targetingAbility.usesTargeting()
                || targetingAbility.getHostCard() == null || candidates == null) {
            return List.of();
        }
        final Player activator = targetingAbility.getActivatingPlayer() == null
                ? evaluatingAi : targetingAbility.getActivatingPlayer();
        final List<Card> targetable = new ArrayList<>();
        for (final Card candidate : candidates) {
            if (candidate != null && candidate.isInPlay()
                    && canBeTargeted(candidate, targetingAbility)) {
                targetable.add(candidate);
            }
        }
        if (targetable.isEmpty()) {
            return List.of();
        }

        final List<EffectEvent> events = new ArrayList<>();
        for (final Card candidate : targetable) {
            final Map<AbilityKey, Object> parameters = new EnumMap<>(AbilityKey.class);
            parameters.put(AbilityKey.SourceSA, targetingAbility);
            parameters.put(AbilityKey.Target, candidate);
            parameters.put(AbilityKey.Targets, Set.of(candidate));
            parameters.put(AbilityKey.Cause, targetingAbility.getHostCard());
            if (!candidate.hasBecomeTargetThisTurn()) {
                // Forge represents FirstTime by the presence of the key, including a null value.
                parameters.put(AbilityKey.FirstTime, null);
            }
            if (isRandomTarget(targetingAbility)) {
                parameters.put(AbilityKey.Random, true);
            }
            events.add(new EffectEvent(EffectType.BECAME_TARGET, activator,
                    List.of(new EffectEvent.Subject(candidate, 1)), parameters));
        }
        return List.of(new EffectProduction(targetingAbility.getHostCard(),
                EffectType.BECAME_TARGET, events, 1));
    }

    private static boolean canBeTargeted(final Card candidate,
            final SpellAbility targetingAbility) {
        try {
            return candidate.canBeTargetedBy(targetingAbility);
        } catch (final RuntimeException ignored) {
            // The removal caller already supplied legal candidates. Preserve those candidates if
            // a custom target restriction cannot be rechecked by this analysis-only adapter.
            return true;
        }
    }

    private static boolean isRandomTarget(final SpellAbility targetingAbility) {
        for (SpellAbility current = targetingAbility; current != null;
                current = current.getSubAbility()) {
            final TargetRestrictions restrictions = current.getTargetRestrictions();
            if (current.usesTargeting() && restrictions != null && restrictions.isRandomTarget()) {
                return true;
            }
        }
        return false;
    }
}
