package forge.ai.effect;

import java.util.EnumMap;
import java.util.Map;

/** Routes normalized event families to their trigger matching adapters. */
final class EffectEventMatcherRegistry {
    private static final Map<EffectType, EffectEventMatcher> MATCHERS = new EnumMap<>(EffectType.class);

    static {
        MATCHERS.put(EffectType.TOKEN_CREATED, TokenCreatedEventMatcher.INSTANCE);
        MATCHERS.put(EffectType.COUNTER_ADDED, CounterAddedEventMatcher.INSTANCE);
        MATCHERS.put(EffectType.LIFE_GAINED, LifeGainedEventMatcher.INSTANCE);
        MATCHERS.put(EffectType.CARD_DRAWN, CardDrawnEventMatcher.INSTANCE);
        MATCHERS.put(EffectType.DAMAGE_DEALT, DamageDealtEventMatcher.INSTANCE);
        MATCHERS.put(EffectType.ATTACKED_OR_BLOCKED, AttackEventMatcher.INSTANCE);
        MATCHERS.put(EffectType.SACRIFICED, SacrificeEventMatcher.INSTANCE);
        MATCHERS.put(EffectType.ZONE_CHANGED, ZoneChangedEventMatcher.INSTANCE);
    }

    private EffectEventMatcherRegistry() {
    }

    static EffectEventMatcher find(final EffectType type) {
        return MATCHERS.get(type);
    }
}
