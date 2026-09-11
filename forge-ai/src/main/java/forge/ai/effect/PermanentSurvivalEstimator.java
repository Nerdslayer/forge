package forge.ai.effect;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Set;

import forge.ai.effect.IntrinsicReferenceModel.CreatureProfile;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;
import forge.ai.effect.IntrinsicReferenceModel.SurvivalProfile;

/**
 * Estimates cumulative permanent survival without simulating a live game.
 *
 * <p>The intrinsic version uses coarse, configurable hazards from the reference model. The same
 * checkpoint and hazard-composition rules can later consume live, public-information hazards from
 * situational analysis.</p>
 */
public final class PermanentSurvivalEstimator {
    /** A neutral Aura host when the card's intrinsic text gives no host characteristics. */
    public static final CreatureProfile DEFAULT_AURA_HOST =
            new CreatureProfile(true, 3, 3, Set.of(), false, false);

    private static final double BASIC_LAND_REMOVAL_FACTOR = .35;
    // Hexproof and shroud still leave global effects, sacrifice, and some characteristic-changing
    // effects available, but make ordinary targeted removal much less likely to matter.
    private static final double HEXPROOF_NON_DAMAGE_REMOVAL_FACTOR = .20;
    private static final double HEXPROOF_DAMAGE_REMOVAL_FACTOR = .20;
    private static final double INDESTRUCTIBLE_NON_DAMAGE_REMOVAL_FACTOR = .55;
    private static final double INDESTRUCTIBLE_DAMAGE_REMOVAL_FACTOR = .65;
    private static final double WARD_NON_DAMAGE_REMOVAL_FACTOR = .85;
    private static final double WARD_DAMAGE_REMOVAL_FACTOR = .90;
    private static final double LOW_TOUGHNESS_REMOVAL_FACTOR = 1.75;
    private static final double HIGH_TOUGHNESS_REMOVAL_FLOOR = .35;
    private static final double LOW_TOUGHNESS_COMBAT_FACTOR = 1.35;
    private static final double MEDIUM_TOUGHNESS_COMBAT_FACTOR = 1.15;
    private static final double HIGH_TOUGHNESS_COMBAT_FLOOR = .55;
    private static final double EVASION_COMBAT_FACTOR = .75;
    private static final double FIRST_STRIKE_COMBAT_FACTOR = .85;
    private static final double STRONG_FIRST_STRIKE_COMBAT_FACTOR = .85;

    private final IntrinsicReferenceModel model;

    public PermanentSurvivalEstimator() {
        this(IntrinsicReferenceModel.defaults());
    }

    public PermanentSurvivalEstimator(final IntrinsicReferenceModel model) {
        if (model == null) {
            throw new IllegalArgumentException("Intrinsic reference model is required");
        }
        this.model = model;
    }

    public IntrinsicReferenceModel model() {
        return model;
    }

    public PermanentSurvivalEstimate estimate(final PermanentProfile source,
            final EntryTiming entryTiming) {
        return estimate(source, DEFAULT_AURA_HOST, entryTiming);
    }

    /**
     * Estimates source survival. Auras include the survival of their attached host; when the host
     * is not known, a vanilla 3/3 is used. TODO(effect analysis): Apply the Aura's granted static
     * characteristics to the host once intrinsic static-ability evaluation can construct them.
     */
    public PermanentSurvivalEstimate estimate(final PermanentProfile source,
            final CreatureProfile auraHost, final EntryTiming entryTiming) {
        if (entryTiming == null) {
            throw new IllegalArgumentException("Entry timing is required");
        }
        final EnumMap<SurvivalCheckpoint, Double> result = new EnumMap<>(SurvivalCheckpoint.class);
        if (source == null || !source.present()) {
            for (final SurvivalCheckpoint checkpoint : SurvivalCheckpoint.values()) {
                result.put(checkpoint, 0d);
            }
            return new PermanentSurvivalEstimate(result);
        }

        final double perTurnHazard = hazardForSource(source, auraHost);
        double survival = 1;
        for (final SurvivalCheckpoint checkpoint : SurvivalCheckpoint.values()) {
            if (checkpoint.isTurnEnd()) {
                final double exposure = checkpoint == SurvivalCheckpoint.END_OF_FIRST_TURN
                        ? entryTiming.firstTurnExposure() : 1;
                survival *= 1 - scaledHazard(perTurnHazard, exposure);
            }
            result.put(checkpoint, clamp(survival));
        }
        return new PermanentSurvivalEstimate(result);
    }

    private double hazardForSource(final PermanentProfile source,
            final CreatureProfile auraHost) {
        final double sourceHazard;
        if (source.kind() == PermanentKind.AURA) {
            final double auraHazard = causeHazard(model.survivalProfile(PermanentKind.AURA),
                    source, null);
            final CreatureProfile host = auraHost == null ? DEFAULT_AURA_HOST : auraHost;
            final double hostHazard = causeHazard(model.survivalProfile(PermanentKind.CREATURE),
                    null, host);
            // An Aura is lost if either it is removed or its attached creature dies.
            sourceHazard = combineIndependentHazards(auraHazard, hostHazard);
        } else {
            sourceHazard = causeHazard(model.survivalProfile(source.kind()), source, null);
        }
        return combineIndependentHazards(sourceHazard, model.gameEndHazardPerTurn());
    }

    private static double causeHazard(final SurvivalProfile profile,
            final PermanentProfile permanent, final CreatureProfile creature) {
        double nonDamageRemoval = profile.nonDamageRemovalHazard();
        double damageRemoval = profile.damageRemovalHazard();
        double combat = profile.combatHazard();
        if (permanent != null) {
            final boolean hexproof = hasKeyword(permanent.keywords(), "hexproof")
                    || hasKeyword(permanent.keywords(), "shroud");
            final boolean indestructible = hasKeyword(permanent.keywords(), "indestructible");
            final boolean ward = hasKeyword(permanent.keywords(), "ward");
            nonDamageRemoval = adjustNonDamageRemoval(nonDamageRemoval, hexproof, indestructible,
                    ward, permanent.kind() == PermanentKind.LAND && permanent.basicLand());
            damageRemoval = adjustDamageRemoval(damageRemoval, hexproof, indestructible, ward,
                    permanent.kind() == PermanentKind.CREATURE
                            || permanent.kind() == PermanentKind.TOKEN,
                    permanent.toughness());
            if (permanent.kind() == PermanentKind.CREATURE
                    || permanent.kind() == PermanentKind.TOKEN) {
                combat = adjustCombatHazard(combat, permanent.power(), permanent.toughness(),
                        permanent.keywords());
            }
        } else if (creature != null) {
            final boolean hexproof = creature.hexproof()
                    || hasKeyword(creature.keywords(), "shroud");
            final boolean indestructible = creature.indestructible()
                    || hasKeyword(creature.keywords(), "indestructible");
            final boolean ward = hasKeyword(creature.keywords(), "ward");
            nonDamageRemoval = adjustNonDamageRemoval(nonDamageRemoval, hexproof, indestructible,
                    ward, false);
            damageRemoval = adjustDamageRemoval(damageRemoval, hexproof, indestructible, ward,
                    true, creature.toughness());
            combat = adjustCombatHazard(combat, creature.power(), creature.toughness(),
                    creature.keywords());
        }
        return combineIndependentHazards(
                combineIndependentHazards(nonDamageRemoval, damageRemoval), combat);
    }

    private static double adjustNonDamageRemoval(final double base, final boolean hexproof,
            final boolean indestructible, final boolean ward, final boolean basicLand) {
        double result = base;
        if (hexproof) {
            result *= HEXPROOF_NON_DAMAGE_REMOVAL_FACTOR;
        }
        if (indestructible) {
            result *= INDESTRUCTIBLE_NON_DAMAGE_REMOVAL_FACTOR;
        }
        if (ward) {
            result *= WARD_NON_DAMAGE_REMOVAL_FACTOR;
        }
        if (basicLand) {
            result *= BASIC_LAND_REMOVAL_FACTOR;
        }
        return result;
    }

    private static double adjustDamageRemoval(final double base, final boolean hexproof,
            final boolean indestructible, final boolean ward, final boolean creatureLike,
            final int toughness) {
        double result = base;
        if (hexproof) {
            result *= HEXPROOF_DAMAGE_REMOVAL_FACTOR;
        }
        if (indestructible) {
            result *= INDESTRUCTIBLE_DAMAGE_REMOVAL_FACTOR;
        }
        if (ward) {
            result *= WARD_DAMAGE_REMOVAL_FACTOR;
        }
        if (creatureLike) {
            result *= toughnessRemovalFactor(toughness);
        }
        return result;
    }

    private static double toughnessRemovalFactor(final int toughness) {
        return clamp(3.0 / Math.max(1, toughness), HIGH_TOUGHNESS_REMOVAL_FLOOR,
                LOW_TOUGHNESS_REMOVAL_FACTOR);
    }

    private static double adjustCombatHazard(final double base, final int power,
            final int toughness, final Set<String> keywords) {
        if (base == 0) {
            return 0;
        }
        double result = base;
        if (toughness <= 1) {
            result *= LOW_TOUGHNESS_COMBAT_FACTOR;
        } else if (toughness == 2) {
            result *= MEDIUM_TOUGHNESS_COMBAT_FACTOR;
        } else if (toughness > 3) {
            result *= Math.max(HIGH_TOUGHNESS_COMBAT_FLOOR, 3.0 / toughness);
        }
        if (hasKeyword(keywords, "flying") || hasKeyword(keywords, "unblockable")
                || hasKeyword(keywords, "can't be blocked")) {
            result *= EVASION_COMBAT_FACTOR;
        }
        // Double strike and first strike both provide first-strike damage. The extra normal
        // damage from double strike belongs in combat-damage/attack value, not survival odds.
        if (hasKeyword(keywords, "double strike") || hasKeyword(keywords, "first strike")) {
            result *= FIRST_STRIKE_COMBAT_FACTOR;
            if (power >= 4) {
                result *= STRONG_FIRST_STRIKE_COMBAT_FACTOR;
            }
        }
        return clamp(result);
    }

    private static boolean hasKeyword(final Set<String> keywords, final String expected) {
        final String normalized = expected.toLowerCase(Locale.ROOT);
        return keywords.stream().map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals);
    }

    private static double combineIndependentHazards(final double first, final double second) {
        return clamp(1 - (1 - clamp(first)) * (1 - clamp(second)));
    }

    private static double scaledHazard(final double hazard, final double exposure) {
        return clamp(1 - Math.pow(1 - clamp(hazard), clamp(exposure)));
    }

    private static double clamp(final double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static double clamp(final double value, final double lower, final double upper) {
        return Math.max(lower, Math.min(upper, value));
    }
}
