package forge.ai.effect;

import java.util.Objects;
import java.util.function.Predicate;

import forge.ai.effect.IntrinsicReferenceModel.CreatureProfile;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;

/**
 * Reference target populations that retain absent and unsuitable cases instead of renormalizing
 * them away. A later intrinsic outcome adapter can therefore distinguish no legal target from an
 * unsupported target evaluation.
 */
public final class IntrinsicTargetDistribution {
    private final IntrinsicReferenceModel model;

    public IntrinsicTargetDistribution(final IntrinsicReferenceModel model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    public IntrinsicReferenceModel model() {
        return model;
    }

    public WeightedDistribution<TargetCase<CreatureProfile>> creatureTargets() {
        return creatureTargets(profile -> true);
    }

    public WeightedDistribution<TargetCase<CreatureProfile>> creatureTargets(
            final Predicate<CreatureProfile> restriction) {
        Objects.requireNonNull(restriction, "restriction");
        return model.creatureProfiles().map(profile -> new TargetCase<>(profile, profile.present(),
                profile.present() && restriction.test(profile)));
    }

    public WeightedDistribution<TargetCase<PermanentProfile>> permanentTargets() {
        return permanentTargets(profile -> true);
    }

    public WeightedDistribution<TargetCase<PermanentProfile>> permanentTargets(
            final Predicate<PermanentProfile> restriction) {
        Objects.requireNonNull(restriction, "restriction");
        return model.permanentProfiles().map(profile -> new TargetCase<>(profile, profile.present(),
                profile.present() && restriction.test(profile)));
    }

    /** Returns the configured population-presence distribution for a permanent kind. */
    public WeightedDistribution<Boolean> availability(final PermanentKind kind) {
        return model.targetAvailability(kind);
    }

    /** A target profile with legal-target status evaluated for the current outcome restriction. */
    public record TargetCase<T>(T value, boolean present, boolean legal) {
        public TargetCase {
            Objects.requireNonNull(value, "target value");
        }
    }
}
