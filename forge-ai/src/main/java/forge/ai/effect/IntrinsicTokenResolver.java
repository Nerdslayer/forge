package forge.ai.effect;

import java.util.Optional;
import java.util.function.Function;

import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;

/** Optional richer token capability; existing profile-only resolver clients remain compatible. */
public interface IntrinsicTokenResolver extends Function<String, Optional<PermanentProfile>> {
    @FunctionalInterface
    interface ResourceValue {
        double evaluate(int handSize, int life, boolean initiallyTapped);
    }

    record Definition(PermanentProfile profile, ResourceValue resourceValue) { }

    Optional<Definition> resolveToken(String script);

    @Override
    default Optional<PermanentProfile> apply(final String script) {
        return resolveToken(script).map(Definition::profile);
    }
}
