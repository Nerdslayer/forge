package forge.ai.effect;

import java.util.Optional;
import java.util.function.Function;

import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;

/** Optional richer token capability; existing profile-only resolver clients remain compatible. */
public interface IntrinsicTokenResolver extends Function<String, Optional<PermanentProfile>> {
    @FunctionalInterface
    interface ResourceValue {
        double evaluate(int handSize, int life, boolean initiallyTapped);

        // Existing/custom implementations stay conservative unless they declare dependencies.
        default boolean usesHandSize() { return true; }
        default boolean usesLifeTotal() { return true; }
    }

    record Definition(PermanentProfile profile, ResourceValue resourceValue,
            boolean requiresPowerOverride, boolean requiresToughnessOverride) {
        public Definition(final PermanentProfile profile, final ResourceValue resourceValue) {
            this(profile, resourceValue, false, false);
        }
    }

    Optional<Definition> resolveToken(String script);

    @Override
    default Optional<PermanentProfile> apply(final String script) {
        return resolveToken(script).filter(definition -> !definition.requiresPowerOverride() && !definition.requiresToughnessOverride())
                .map(Definition::profile);
    }
}
