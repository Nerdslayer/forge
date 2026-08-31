package forge.ai.effect;

import java.util.EnumMap;
import java.util.Map;

/** Routes normalized event families to their trigger matching adapters. */
final class EffectEventMatcherRegistry {
    private static final Map<EffectType, EffectEventMatcher> MATCHERS = new EnumMap<>(EffectType.class);

    static {
        MATCHERS.put(EffectType.TOKEN_CREATED, TokenCreatedEventMatcher.INSTANCE);
    }

    private EffectEventMatcherRegistry() {
    }

    static EffectEventMatcher find(final EffectType type) {
        return MATCHERS.get(type);
    }
}
