package forge.ai.effect;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import forge.ai.effect.IntrinsicReferenceModel.CreatureProfile;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;

/**
 * Pure reference backend for the small intrinsic outcome slice that has trustworthy semantics.
 *
 * <p>The historical class name is retained for source compatibility with the original draw
 * evaluator. The backend admits only fixed, mandatory outcome forms whose reference state and
 * target semantics are modeled below. Relationship analysis has broader live-game support, but
 * dynamic, replacement-modified and complex outcomes must remain unresolved here.</p>
 */
public final class IntrinsicDrawOutcomeBackend
        implements OutcomeDescriptionCompiler.Backend<IntrinsicDrawOutcomeBackend.State> {
    public static final String CONTROLLER_HAND = "controllerHand";
    public static final String OPPONENT_HAND = "opponentHand";
    public static final String CONTROLLER_LIFE = "controllerLife";
    public static final String OPPONENT_LIFE = "opponentLife";
    public static final String CONTROLLER_CREATURE = "controllerCreature";
    public static final String OPPONENT_CREATURE = "opponentCreature";
    public static final String CONTROLLER_PERMANENT = "controllerPermanent";
    public static final String OPPONENT_PERMANENT = "opponentPermanent";
    public static final String CONTROLLER_CREATURE_COUNT = "controllerCreatureCount";
    public static final String OPPONENT_CREATURE_COUNT = "opponentCreatureCount";
    public static final String SOURCE_P1P1 = "P1P1_COUNTERS";
    public static final String SOURCE_INITIAL_P1P1 = "sourceInitialP1p1Counters";
    public static final String CONTROLLER_P1P1 = "controllerP1p1Counters";
    public static final String OPPONENT_P1P1 = "opponentP1p1Counters";

    private static final int MAX_DIMENSION_DEPTH = 24;
    private static final Set<String> COMMON_METADATA = Set.of("DB", "SubAbility",
            "SpellDescription", "StackDescription", "Secondary", "PrecostDesc", "CostDesc");
    private static final Set<String> CHOICE_PARAMETERS = Set.of(
            "DB", "AB", "SP", "Cost", "Choices", "CharmNum", "MinCharmNum", "CanRepeatModes",
            "ChoiceAmount", "Defined", "Chooser", "Random", "AtRandom", "SubAbility",
            "SpellDescription", "StackDescription");
    private static final Set<String> DRAW_PARAMETERS = parameters("NumCards", "Defined");
    private static final Set<String> DIG_PARAMETERS = parameters("Defined", "DigNum", "ChangeNum", "ChangeValid",
            "ChangeValidDesc", "DestinationZone", "DestinationZone2", "SourceZone", "RestRandomOrder", "Reveal",
            "ForceRevealToController", "PrimaryPrompt");
    private static final Set<String> SCRY_PARAMETERS = parameters("Defined", "ScryNum");
    private static final Set<String> SURVEIL_PARAMETERS = parameters("Defined", "Amount");
    private static final Set<String> COUNTER_PARAMETERS = parameters("CounterType", "CounterNum",
            "Defined", "ValidCards", "ValidTgts", "TgtPrompt", "TargetMin", "TargetMax", "TgtZone");
    private static final Set<String> MULTIPLY_COUNTER_PARAMETERS = parameters("CounterType", "Multiplier", "Defined");
    private static final Set<String> PUMP_PARAMETERS = parameters("Defined", "ValidCards",
            "ValidTgts", "TgtPrompt", "TargetMin", "TargetMax", "TgtZone", "Duration",
            "NumAtt", "NumDef", "KW");
    private static final Set<String> DEBUFF_PARAMETERS = parameters("Defined", "Keywords",
            "ValidTgts", "ValidTgtsDesc", "TgtPrompt", "TargetMin", "TargetMax", "TgtZone",
            "Duration");
    private static final Set<String> ANIMATE_PARAMETERS = parameters("Defined", "ValidTgts",
            "ValidTgtsDesc", "TgtPrompt", "TargetMin", "TargetMax", "TgtZone", "Duration",
            "Power", "Toughness", "Types", "Keywords");
    private static final Set<String> ANIMATE_ALL_PARAMETERS = parameters("ValidCards", "Duration",
            "Power", "Toughness", "Types", "Keywords");
    private static final Set<String> TOKEN_PARAMETERS = parameters("TokenScript", "TokenOwner",
            "TokenAmount", "TokenPower", "TokenToughness", "TokenTypes", "TokenColors",
            "TokenTapped", "TokenAttacking", "TokenBlocking");
    private static final Set<String> INVESTIGATE_PARAMETERS = parameters("Defined", "Num");
    private static final Set<String> LIFE_PARAMETERS = parameters("Defined", "LifeAmount",
            "ValidTgts", "ValidTgtsDesc", "TgtPrompt", "TargetMin", "TargetMax");
    private static final Set<String> DISCARD_PARAMETERS = parameters("Defined", "Mode", "NumCards",
            "ValidTgts", "ValidTgtsDesc", "TgtPrompt", "TargetMin", "TargetMax", "DiscardValid", "DiscardValidDesc");
    private static final Set<String> MANA_PARAMETERS = parameters("Defined", "Produced", "Amount");
    private static final Set<String> MANA_REFLECTED_PARAMETERS = parameters("Defined", "ColorOrType",
            "ReflectProperty", "Amount");
    private static final Set<String> DAMAGE_PARAMETERS = parameters("Defined", "NumDmg", "DamageSource",
            "ValidTgts", "ValidTgtsDesc", "TgtPrompt", "TargetMin", "TargetMax", "ReplaceDyingDefined");
    private static final Set<String> DAMAGE_ALL_PARAMETERS = parameters("ValidPlayers", "ValidCards", "ValidDescription",
            "NumDmg", "DamageSource");
    private static final Set<String> FIGHT_PARAMETERS = parameters("Defined", "ValidTgts",
            "ValidTgtsDesc", "TgtPrompt", "TargetMin", "TargetMax", "TgtZone");
    private static final Set<String> REMOVAL_PARAMETERS = parameters("Defined", "ValidTgts",
            "ValidTgtsDesc", "TgtPrompt", "TargetMin", "TargetMax", "TgtZone", "Origin",
            "Destination", "NoRegen", "Radiance", "Duration", "ChangeNum", "ChangeType",
            "Chooser", "DefinedPlayer", "GainControl", "Tapped", "RememberChanged");
    private static final Set<String> CONTROL_PARAMETERS = parameters("Defined", "ValidTgts",
            "ValidTgtsDesc", "TgtPrompt", "TargetMin", "TargetMax", "TgtZone", "NewController",
            "Duration");
    private static final Set<String> SACRIFICE_PARAMETERS = parameters("Defined", "SacValid", "Amount",
            "ValidTgts", "ValidTgtsDesc", "TgtPrompt", "TargetMin", "TargetMax", "TgtZone");
    private static final Set<String> SACRIFICE_ALL_PARAMETERS = parameters("ValidCards");
    private static final Set<String> DESTROY_ALL_PARAMETERS = parameters("ValidCards", "NoRegen");
    private static final Set<String> CHANGE_ZONE_ALL_PARAMETERS = parameters("ChangeType", "Origin",
            "Destination", "RememberChanged");
    private static final Set<String> COPY_PARAMETERS = parameters("Defined", "Controller",
            "NumCopies");
    private static final Set<String> REMOVE_COUNTER_ALL_PARAMETERS = parameters("CounterType",
            "CounterNum", "ValidCards", "ValidZone");
    private static final Set<String> SIMPLE_CREATURE_KEYWORDS = Set.of("flying", "first strike", "double strike",
            "haste", "flash", "reach", "menace", "fear", "intimidate", "vigilance", "trample", "deathtouch", "lifelink", "defender",
            "hexproof", "shroud", "indestructible", "shield", "stun", "ward", "detain",
            "can't attack", "cantattack", "can't block", "cantblock", "can't untap", "cantuntap");
    private static final Set<String> VALUED_KEYWORD_COUNTERS = Set.of("FLYING", "DEATHTOUCH", "LIFELINK",
            "TRAMPLE", "VIGILANCE", "DEFENDER", "CANTATTACK", "CANTBLOCK", "DETAIN", "CANTUNTAP",
            "HEXPROOF", "SHROUD", "INDESTRUCTIBLE", "WARD");
    private static final Set<String> INTRINSIC_COUNTER_TYPES = Set.of("P1P1", "M1M1", "SHIELD", "STUN",
            "LOYALTY");

    public enum TargetRef {
        CONTROLLER_PLAYER, OPPONENT_PLAYER,
        CONTROLLER_CREATURE, OPPONENT_CREATURE,
        CONTROLLER_PERMANENT, OPPONENT_PERMANENT, SOURCE, WATCHED_CREATURE
    }

    /** Immutable reference state. The two-argument constructor preserves the original API. */
    public record State(int controllerHand, int opponentHand, int controllerLife, int opponentLife,
            int controllerMana, int opponentMana, int controllerCreatureCount,
            int opponentCreatureCount, CreatureProfile controllerCreature,
            CreatureProfile opponentCreature, PermanentProfile controllerPermanent,
            PermanentProfile opponentPermanent, PermanentProfile sourcePermanent,
            TargetRef target, java.util.Map<TargetRef, Integer> p1p1Counters, PermanentProfile watchedCreature) {
        public State {
            p1p1Counters = p1p1Counters == null ? java.util.Map.of() : java.util.Map.copyOf(p1p1Counters);
            if (p1p1Counters.values().stream().anyMatch(value -> value < 0)) {
                throw new IllegalArgumentException("Counter inventory must be nonnegative");
            }
            controllerHand = Math.max(0, controllerHand);
            opponentHand = Math.max(0, opponentHand);
            controllerLife = Math.max(0, controllerLife);
            opponentLife = Math.max(0, opponentLife);
            controllerMana = Math.max(0, controllerMana);
            opponentMana = Math.max(0, opponentMana);
            controllerCreatureCount = Math.max(0, controllerCreatureCount);
            opponentCreatureCount = Math.max(0, opponentCreatureCount);
            controllerCreature = controllerCreature == null ? CreatureProfile.absent() : controllerCreature;
            opponentCreature = opponentCreature == null ? CreatureProfile.absent() : opponentCreature;
            controllerPermanent = controllerPermanent == null
                    ? PermanentProfile.absent() : controllerPermanent;
            opponentPermanent = opponentPermanent == null
                    ? PermanentProfile.absent() : opponentPermanent;
            sourcePermanent = sourcePermanent == null ? PermanentProfile.absent() : sourcePermanent;
            watchedCreature = watchedCreature == null ? PermanentProfile.absent() : watchedCreature;
        }

        /** Compatibility constructor for callers without an event-object binding. */
        public State(final int controllerHand, final int opponentHand, final int controllerLife, final int opponentLife,
                final int controllerMana, final int opponentMana, final int controllerCreatureCount,
                final int opponentCreatureCount, final CreatureProfile controllerCreature, final CreatureProfile opponentCreature,
                final PermanentProfile controllerPermanent, final PermanentProfile opponentPermanent,
                final PermanentProfile sourcePermanent, final TargetRef target, final java.util.Map<TargetRef, Integer> p1p1Counters) {
            this(controllerHand, opponentHand, controllerLife, opponentLife, controllerMana, opponentMana,
                    controllerCreatureCount, opponentCreatureCount, controllerCreature, opponentCreature,
                    controllerPermanent, opponentPermanent, sourcePermanent, target, p1p1Counters, PermanentProfile.absent());
        }

        State withWatchedCreature(final PermanentProfile profile) {
            return new State(controllerHand, opponentHand, controllerLife, opponentLife,
                    controllerMana, opponentMana, controllerCreatureCount, opponentCreatureCount,
                    controllerCreature, opponentCreature, controllerPermanent, opponentPermanent,
                    sourcePermanent, target, p1p1Counters, profile);
        }

        public State(final int controllerHand, final int opponentHand, final int controllerLife, final int opponentLife,
                final int controllerMana, final int opponentMana, final int controllerCreatureCount,
                final int opponentCreatureCount, final CreatureProfile controllerCreature, final CreatureProfile opponentCreature,
                final PermanentProfile controllerPermanent, final PermanentProfile opponentPermanent,
                final PermanentProfile sourcePermanent, final TargetRef target) {
            this(controllerHand, opponentHand, controllerLife, opponentLife, controllerMana, opponentMana,
                    controllerCreatureCount, opponentCreatureCount, controllerCreature, opponentCreature,
                    controllerPermanent, opponentPermanent, sourcePermanent, target, java.util.Map.of());
        }

        int p1p1(final TargetRef recipient) { return p1p1Counters.getOrDefault(recipient, 0); }

        State withP1p1(final TargetRef recipient, final int amount) {
            final var counters = new java.util.EnumMap<TargetRef, Integer>(TargetRef.class);
            counters.putAll(p1p1Counters);
            counters.put(recipient, Math.max(0, amount));
            return new State(controllerHand, opponentHand, controllerLife, opponentLife,
                    controllerMana, opponentMana, controllerCreatureCount, opponentCreatureCount,
                    controllerCreature, opponentCreature, controllerPermanent, opponentPermanent,
                    sourcePermanent, target, counters, watchedCreature);
        }

        public State(final int controllerHand, final int opponentHand) {
            this(controllerHand, opponentHand, 20, 20, 3, 3, 1, 1,
                    CreatureProfile.absent(), CreatureProfile.absent(),
                    PermanentProfile.absent(), PermanentProfile.absent(),
                    PermanentProfile.absent(), null);
        }

        State withTarget(final TargetRef value) {
            return new State(controllerHand, opponentHand, controllerLife, opponentLife,
                    controllerMana, opponentMana, controllerCreatureCount, opponentCreatureCount,
                    controllerCreature, opponentCreature, controllerPermanent, opponentPermanent,
                    sourcePermanent, value, p1p1Counters, watchedCreature);
        }

        State clearTarget() {
            return target == null ? this : withTarget(null);
        }

        State withHands(final boolean controller, final int value) {
            return controller
                    ? new State(value, opponentHand, controllerLife, opponentLife, controllerMana,
                            opponentMana, controllerCreatureCount, opponentCreatureCount,
                            controllerCreature, opponentCreature, controllerPermanent, opponentPermanent,
                            sourcePermanent, target, p1p1Counters, watchedCreature)
                    : new State(controllerHand, value, controllerLife, opponentLife, controllerMana,
                            opponentMana, controllerCreatureCount, opponentCreatureCount,
                            controllerCreature, opponentCreature, controllerPermanent, opponentPermanent,
                            sourcePermanent, target, p1p1Counters, watchedCreature);
        }

        State withLife(final boolean controller, final int value) {
            return controller
                    ? new State(controllerHand, opponentHand, value, opponentLife, controllerMana,
                            opponentMana, controllerCreatureCount, opponentCreatureCount,
                            controllerCreature, opponentCreature, controllerPermanent, opponentPermanent,
                            sourcePermanent, target, p1p1Counters, watchedCreature)
                    : new State(controllerHand, opponentHand, controllerLife, value, controllerMana,
                            opponentMana, controllerCreatureCount, opponentCreatureCount,
                            controllerCreature, opponentCreature, controllerPermanent, opponentPermanent,
                            sourcePermanent, target, p1p1Counters, watchedCreature);
        }

        State withMana(final boolean controller, final int value) {
            return controller
                    ? new State(controllerHand, opponentHand, controllerLife, opponentLife, value,
                            opponentMana, controllerCreatureCount, opponentCreatureCount,
                            controllerCreature, opponentCreature, controllerPermanent, opponentPermanent,
                            sourcePermanent, target, p1p1Counters, watchedCreature)
                    : new State(controllerHand, opponentHand, controllerLife, opponentLife, controllerMana,
                            value, controllerCreatureCount, opponentCreatureCount,
                            controllerCreature, opponentCreature, controllerPermanent, opponentPermanent,
                            sourcePermanent, target, p1p1Counters, watchedCreature);
        }

        State withCreatureCount(final boolean controller, final int value) {
            return controller
                    ? new State(controllerHand, opponentHand, controllerLife, opponentLife, controllerMana,
                            opponentMana, Math.max(0, value), opponentCreatureCount, controllerCreature,
                            opponentCreature, controllerPermanent, opponentPermanent, sourcePermanent, target, p1p1Counters, watchedCreature)
                    : new State(controllerHand, opponentHand, controllerLife, opponentLife, controllerMana,
                            opponentMana, controllerCreatureCount, Math.max(0, value), controllerCreature,
                            opponentCreature, controllerPermanent, opponentPermanent, sourcePermanent, target, p1p1Counters, watchedCreature);
        }

        int creatureCount(final boolean controller) {
            return controller ? controllerCreatureCount : opponentCreatureCount;
        }

        State withCreatures(final boolean controller, final CreatureProfile profile) {
            return controller
                    ? new State(controllerHand, opponentHand, controllerLife, opponentLife, controllerMana,
                            opponentMana, controllerCreatureCount, opponentCreatureCount, profile,
                            opponentCreature, controllerPermanent, opponentPermanent, sourcePermanent, target, p1p1Counters, watchedCreature)
                    : new State(controllerHand, opponentHand, controllerLife, opponentLife, controllerMana,
                            opponentMana, controllerCreatureCount, opponentCreatureCount,
                            controllerCreature, profile, controllerPermanent, opponentPermanent,
                            sourcePermanent, target, p1p1Counters, watchedCreature);
        }

        State withPermanent(final boolean controller, final PermanentProfile profile) {
            return controller
                    ? new State(controllerHand, opponentHand, controllerLife, opponentLife, controllerMana,
                            opponentMana, controllerCreatureCount, opponentCreatureCount,
                            controllerCreature, opponentCreature, profile, opponentPermanent,
                            sourcePermanent, target, p1p1Counters, watchedCreature)
                    : new State(controllerHand, opponentHand, controllerLife, opponentLife, controllerMana,
                            opponentMana, controllerCreatureCount, opponentCreatureCount,
                            controllerCreature, opponentCreature, controllerPermanent, profile,
                            sourcePermanent, target, p1p1Counters, watchedCreature);
        }

        State withSourcePermanent(final PermanentProfile profile) {
            return new State(controllerHand, opponentHand, controllerLife, opponentLife,
                    controllerMana, opponentMana, controllerCreatureCount, opponentCreatureCount,
                    controllerCreature, opponentCreature, controllerPermanent, opponentPermanent,
                    profile, target, p1p1Counters, watchedCreature);
        }
    }

    private final IntrinsicOutcomeEvaluator evaluator;
    private final Function<String, Optional<PermanentProfile>> tokenProfileResolver;
    private final IntrinsicLibraryReference library;
    private final WeightedDistribution<Integer> counteredSpellManaValues;
    private final PermanentProfile watchedEventCreature;
    private final boolean initialSourceLifelink;

    public IntrinsicDrawOutcomeBackend(final IntrinsicEvaluationSettings settings) {
        this(settings, PermanentProfile.absent());
    }

    public IntrinsicDrawOutcomeBackend(final IntrinsicEvaluationSettings settings,
            final PermanentProfile source) {
        this(settings, source, script -> Optional.empty());
    }

    /**
     * Creates a backend with a static-data token resolver. The resolver must not inspect a live
     * game; unresolved token scripts stay unsupported rather than receiving a guessed value.
     */
    public IntrinsicDrawOutcomeBackend(final IntrinsicEvaluationSettings settings,
            final PermanentProfile source,
            final Function<String, Optional<PermanentProfile>> tokenProfileResolver) {
        this(settings, source, tokenProfileResolver, IntrinsicLibraryReference.defaults());
    }

    public IntrinsicDrawOutcomeBackend(final IntrinsicEvaluationSettings settings,
            final PermanentProfile source, final Function<String, Optional<PermanentProfile>> tokenProfileResolver,
            final IntrinsicLibraryReference library) {
        this(settings, source, tokenProfileResolver, library, null);
    }

    IntrinsicDrawOutcomeBackend(final IntrinsicEvaluationSettings settings,
            final PermanentProfile source, final Function<String, Optional<PermanentProfile>> tokenProfileResolver,
            final IntrinsicLibraryReference library, final PermanentProfile watchedEventCreature) {
        this(settings, source, tokenProfileResolver, library, watchedEventCreature,
                IntrinsicReferenceQuantities.defaults().distribution(IntrinsicReferenceQuantities.Quantity.CAST_SPELL_MANA_VALUE));
    }

    IntrinsicDrawOutcomeBackend(final IntrinsicEvaluationSettings settings,
            final PermanentProfile source, final Function<String, Optional<PermanentProfile>> tokenProfileResolver,
            final IntrinsicLibraryReference library, final PermanentProfile watchedEventCreature,
            final WeightedDistribution<Integer> counteredSpellManaValues) {
        // Source characteristics are read from each projected State, never a stale initial copy.
        evaluator = new IntrinsicOutcomeEvaluator(settings);
        this.tokenProfileResolver = tokenProfileResolver == null ? script -> Optional.empty()
                : tokenProfileResolver;
        this.library = java.util.Objects.requireNonNull(library);
        this.counteredSpellManaValues = java.util.Objects.requireNonNull(counteredSpellManaValues);
        this.watchedEventCreature = watchedEventCreature;
        initialSourceLifelink = hasKeyword(source, "lifelink");
    }

    @Override
    public Outcome<State> conditional(final AbilityOutcomeDescription node,
            final Function<AbilityOutcomeDescription, Outcome<State>> compile) {
        final var condition = IntrinsicOutcomeConditions.describe(node);
        final Outcome<State> effect = compile.apply(condition.node());
        if (condition.predicate() == null) { return effect; }
        return new Outcome.Deferred<>(state -> condition.predicate().test(state) ? effect
                : new Outcome.Atomic<State>(node.path() + ":condition-inactive", current -> new Outcome.Transition<>(0, current)));
    }

    @Override
    public boolean maximize(final AbilityOutcomeDescription node, final boolean opponentChooses) {
        return !opponentChooses;
    }

    @Override
    public boolean acceptsNode(final AbilityOutcomeDescription node) {
        if (node == null || !node.issue().isEmpty() || node.parameters().containsKey("Cost")
                || node.parameters().containsKey("AB") || node.parameters().containsKey("SP")) {
            return false;
        }
        // TODO: Add other watched-object counter inventories, control changes and token-aware bounce.
        // A bound event object must not silently become a generic board target or receive
        // card-in-hand credit for a token.
        if (watchedRecipient(node.parameters().get("Defined")) && (watchedEventCreature == null
                || !Set.of("DealDamage", "Destroy", "ChangeZone", "PutCounter", "RemoveCounter", "MultiplyCounter", "Pump", "Debuff").contains(node.api())
                || Set.of("RemoveCounter", "MultiplyCounter").contains(node.api()) && !"P1P1".equals(counterType(node))
                || "ChangeZone".equals(node.api()) && "Hand".equalsIgnoreCase(node.parameters().get("Destination")))) {
            return false;
        }
        if ("Charm".equals(node.api()) || "GenericChoice".equals(node.api())) {
            return !node.choices().isEmpty() && node.choices().size() <= 32
                    && CHOICE_PARAMETERS.containsAll(node.parameters().keySet())
                    && (!node.parameters().containsKey("CanRepeatModes")
                            || "True".equalsIgnoreCase(node.parameters().get("CanRepeatModes")));
        }
        if (!node.choices().isEmpty()) { return false; }
        return switch (node.api()) {
        case "Draw" -> acceptsDraw(node);
        case "Dig" -> acceptsDig(node);
        case "Scry", "Surveil" -> acceptsFiltering(node);
        case "PutCounter" -> acceptsCounter(node) || acceptsCounterChoice(node);
        case "MultiplyCounter" -> acceptsMultiplyCounter(node);
        case "RemoveCounter" -> acceptsRemoveCounter(node);
        case "RemoveCounterAll" -> acceptsRemoveCounterAll(node);
        case "PutCounterAll" -> acceptsCounterAll(node);
        case "Pump" -> acceptsPump(node);
        case "PumpAll" -> acceptsPumpAll(node);
        case "Debuff" -> acceptsDebuff(node);
        case "Animate" -> acceptsAnimate(node);
        case "AnimateAll" -> acceptsAnimateAll(node);
        case "Token" -> acceptsToken(node);
        case "Investigate" -> investigateToken(node) != null;
        case "GainLife", "LoseLife", "SetLife" -> acceptsLife(node);
        case "Discard" -> acceptsDiscard(node);
        case "Mana" -> acceptsMana(node);
        case "ManaReflected" -> acceptsManaReflected(node);
        case "DealDamage", "DamageAll" -> acceptsDamage(node);
        case "Fight" -> acceptsFight(node);
        case "Counter" -> IntrinsicCounterSpellOutcome.parse(node, library).isPresent();
        case "Destroy" -> acceptsRemoval(node);
        case "ChangeZone" -> acceptsRemoval(node) || IntrinsicLibrarySearchOutcome.parse(node, library).isPresent();
        case "DestroyAll" -> acceptsDestroyAll(node);
        case "ChangeZoneAll" -> acceptsChangeZoneAll(node);
        case "GainControl" -> acceptsGainControl(node);
        case "CopyPermanent" -> acceptsCopyPermanent(node);
        case "Sacrifice" -> acceptsSacrifice(node);
        case "SacrificeAll" -> acceptsSacrificeAll(node);
        default -> false;
        };
    }

    /**
     * Collects the reference variables required by the complete outcome tree, not only its root.
     * The tree is bounded independently from the planner so malformed descriptions cannot cause
     * unbounded traversal.
     */
    @Override
    public Set<String> referenceDimensions(final AbilityOutcomeDescription node) {
        final Set<String> dimensions = new LinkedHashSet<>();
        collectDimensions(node, dimensions,
                Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        if (initialSourceLifelink || watchedEventCreature != null && hasKeyword(watchedEventCreature, "lifelink")
                || containsLifelinkGrant(node, Collections.newSetFromMap(new IdentityHashMap<>()), 0)) {
            // A source's lifelink gain can matter even when the damage recipient is a creature.
            // Include both owners for projected control changes; mechanics read current/LKI state.
            dimensions.add(CONTROLLER_LIFE);
            dimensions.add(OPPONENT_LIFE);
        }
        return Set.copyOf(dimensions);
    }

    private static boolean containsLifelinkGrant(final AbilityOutcomeDescription node,
            final Set<AbilityOutcomeDescription> visited, final int depth) {
        if (node == null || !visited.add(node)) { return false; }
        if (depth > 24) { return true; }
        if (Set.of("KW", "AddKeyword").stream().anyMatch(field -> node.parameters()
                .getOrDefault(field, "").toLowerCase(java.util.Locale.ROOT).contains("lifelink"))) { return true; }
        return node.choices().stream().anyMatch(choice -> containsLifelinkGrant(choice, visited, depth + 1))
                || containsLifelinkGrant(node.next(), visited, depth + 1);
    }

    @Override
    public Outcome<State> bindTargets(final List<AbilityOutcomeDescription> chain, final Outcome<State> child) {
        if (chain.stream().anyMatch(IntrinsicDrawOutcomeBackend::containsModalCounter)) {
            // TODO: Sample one shared stack spell before choosing modes. Independently
            // averaging each counter option undervalues complementary target restrictions.
            return new Outcome.Unresolved<>("Unsupported intrinsic modal counter without shared spell context");
        }
        if (!chain.isEmpty() && OutcomeDescriptionMultiplicity.maximumOccurrences(chain.get(0), node ->
                "Discard".equals(node.api()) && !"Hand".equals(node.parameters().get("Mode"))
                        && library.hitProbability(node.parameters().getOrDefault("DiscardValid", "Card")).orElse(1) < 1, 2) > 1) {
            // TODO: Restricted discards must deplete one correlated hand composition rather
            // than independently rolling the same subset after a known card was discarded.
            return new Outcome.Unresolved<>("Unsupported intrinsic repeated restricted discard inventory");
        }
        // TODO: Repeated searches in one resolution must consume the same library inventory.
        // Independent occurrence estimates may reuse the reference snapshot, but these steps cannot.
        if (!chain.isEmpty() && librarySearchCount(chain.get(0)) > 1) {
            return new Outcome.Unresolved<>("Unsupported intrinsic repeated library search in one resolution");
        }
        // TODO: Correlate multiple counter instructions with actual stack-object identity.
        if (!chain.isEmpty() && counterSpellCount(chain.get(0)) > 1) {
            return new Outcome.Unresolved<>("Unsupported intrinsic multiple spell-counter instructions");
        }
        // TODO: Correlate the entrant with population samples, and retain marked damage across
        // resolutions before admitting sweeps or repeated damage to the watched object.
        if (chain.stream().anyMatch(IntrinsicDrawOutcomeBackend::containsWatchedRecipient)
                && (chain.stream().anyMatch(node -> containsApi(node, Set.of("DamageAll", "DestroyAll",
                        "ChangeZoneAll", "PumpAll", "PutCounterAll", "SacrificeAll", "AnimateAll")))
                        || !chain.isEmpty() && (watchedDamageCount(chain.get(0)) > 1
                                || watchedDamageCount(chain.get(0)) > 0 && chain.stream().anyMatch(node ->
                                        containsWatchedModification(node))))) {
            return new Outcome.Unresolved<>("Unsupported intrinsic overlapping watched-object sequence");
        }
        // TODO: Counter identity across these transitions needs explicit inventory transfer /
        // removal. Do not claim a complete multiply sequence using stale representative counts.
        if (chain.stream().anyMatch(node -> containsApi(node, Set.of("MultiplyCounter")))
                && chain.stream().anyMatch(node -> containsApi(node, Set.of(
                        "MoveCounter", "Token", "Investigate", "CopyPermanent", "GainControl", "Animate", "AnimateAll"))
                        || containsM1m1(node))) {
            return new Outcome.Unresolved<>("Unsupported intrinsic counter-inventory transition before/after multiplication");
        }
        // A chain with one targeted child is safe: the planner resolves that child and then
        // continues with the fixed or already-defined steps. Multiple targeted children still
        // need shared-target bindings and all-targets-illegal resolution rules.
        final int targetedChildren = chain.isEmpty() ? 0 : OutcomeDescriptionMultiplicity.maximumOccurrences(
                chain.get(0), node -> node.parameters().containsKey("ValidTgts"), 2);
        if (targetedChildren > 1) {
            return new Outcome.Unresolved<>("Unsupported intrinsic multiple-target sequence");
        }
        return child;
    }

    private int librarySearchCount(final AbilityOutcomeDescription node) {
        return OutcomeDescriptionMultiplicity.maximumOccurrences(node,
                current -> IntrinsicLibrarySearchOutcome.parse(current, library).isPresent(), 2);
    }

    private static int counterSpellCount(final AbilityOutcomeDescription node) {
        return OutcomeDescriptionMultiplicity.maximumOccurrences(node, current -> "Counter".equals(current.api()), 2);
    }

    private static boolean containsModalCounter(final AbilityOutcomeDescription node) {
        return node != null && (!node.choices().isEmpty()
                && node.choices().stream().anyMatch(choice -> containsApi(choice, Set.of("Counter")))
                || node.choices().stream().anyMatch(IntrinsicDrawOutcomeBackend::containsModalCounter)
                || containsModalCounter(node.next()));
    }

    private static boolean containsApi(final AbilityOutcomeDescription node, final Set<String> apis) {
        return node != null && (apis.contains(node.api()) || node.choices().stream().anyMatch(choice -> containsApi(choice, apis))
                || containsApi(node.next(), apis));
    }

    private static boolean containsWatchedRecipient(final AbilityOutcomeDescription node) {
        return node != null && (watchedRecipient(node.parameters().get("Defined"))
                || node.choices().stream().anyMatch(IntrinsicDrawOutcomeBackend::containsWatchedRecipient)
                || containsWatchedRecipient(node.next()));
    }

    private static int watchedDamageCount(final AbilityOutcomeDescription node) {
        return OutcomeDescriptionMultiplicity.maximumOccurrences(node,
                current -> "DealDamage".equals(current.api()) && watchedRecipient(current.parameters().get("Defined")), 2);
    }

    private static boolean containsWatchedModification(final AbilityOutcomeDescription node) {
        return node != null && (watchedRecipient(node.parameters().get("Defined"))
                && Set.of("PutCounter", "RemoveCounter", "MultiplyCounter", "Pump", "Debuff").contains(node.api())
                || node.choices().stream().anyMatch(IntrinsicDrawOutcomeBackend::containsWatchedModification)
                || containsWatchedModification(node.next()));
    }

    private static boolean containsM1m1(final AbilityOutcomeDescription node) {
        return node != null && (node.parameters().getOrDefault("CounterType", "").toUpperCase(Locale.ROOT).contains("M1M1")
                || node.choices().stream().anyMatch(IntrinsicDrawOutcomeBackend::containsM1m1) || containsM1m1(node.next()));
    }

    private static boolean containsTarget(final AbilityOutcomeDescription node) {
        final java.util.ArrayDeque<AbilityOutcomeDescription> pending = new java.util.ArrayDeque<>();
        pending.add(node);
        int remaining = 1024;
        while (!pending.isEmpty()) {
            // Treat an oversized tree conservatively as requiring unsupported shared binding.
            if (--remaining < 0) { return true; }
            final AbilityOutcomeDescription current = pending.removeLast();
            if (current.parameters().containsKey("ValidTgts")) { return true; }
            if (current.choices().size() > remaining) { return true; }
            pending.addAll(current.choices());
            if (current.next() != null) { pending.add(current.next()); }
        }
        return false;
    }

    @Override
    public Outcome<State> atomic(final AbilityOutcomeDescription node) {
        if (!acceptsNode(node)) {
            return unresolved(node, "Unsupported intrinsic outcome form");
        }
        return switch (node.api()) {
        case "Draw" -> draw(node);
        case "Dig" -> dig(node);
        case "Scry", "Surveil" -> filtering(node);
        case "PutCounter", "PutCounterAll" -> counter(node);
        case "MultiplyCounter" -> multiplyCounter(node);
        case "RemoveCounter" -> removeCounter(node);
        case "RemoveCounterAll" -> removeCounterAll(node);
        case "Pump", "PumpAll" -> pump(node);
        case "Debuff" -> debuff(node);
        case "Animate" -> animate(node);
        case "AnimateAll" -> animateAll(node);
        case "Token" -> token(node);
        case "Investigate" -> token(investigateToken(node));
        case "GainLife", "LoseLife", "SetLife" -> life(node);
        case "Discard" -> discard(node);
        case "Mana" -> mana(node);
        case "ManaReflected" -> manaReflected(node);
        case "DealDamage", "DamageAll" -> damage(node);
        case "Fight" -> fight(node);
        case "Counter" -> IntrinsicCounterSpellOutcome.parse(node, library).orElseThrow()
                .outcome(node.path(), evaluator, counteredSpellManaValues);
        case "Destroy" -> removal(node);
        case "ChangeZone" -> IntrinsicLibrarySearchOutcome.parse(node, library)
                .map(search -> search.outcome(node.path(), evaluator)).orElseGet(() -> removal(node));
        case "DestroyAll" -> destroyAll(node);
        case "ChangeZoneAll" -> changeZoneAll(node);
        case "GainControl" -> gainControl(node);
        case "CopyPermanent" -> copyPermanent(node);
        case "Sacrifice" -> sacrifice(node);
        case "SacrificeAll" -> sacrificeAll(node);
        default -> unresolved(node, "Unsupported intrinsic outcome API " + node.api());
        };
    }

    private Outcome<State> draw(final AbilityOutcomeDescription node) {
        final List<DrawOutcomeDescription> draws = DrawOutcomeDescription.parseFixedRecipients(node.api(), node.parameters()).orElseThrow();
        return new Outcome.Atomic<>(node.path(), state -> {
            int value = 0;
            State projected = state;
            for (final var draw : draws) {
                final int hand = draw.controller() ? state.controllerHand() : state.opponentHand();
                value = EffectMath.add(value, evaluator.evaluateCardDraw(hand, draw.amount(), draw.controller()));
                projected = projected.withHands(draw.controller(), EffectMath.add(hand, draw.amount()));
            }
            return new Outcome.Transition<>((double) value, projected.clearTarget(), node.api());
        });
    }

    private boolean acceptsDig(final AbilityOutcomeDescription node) {
        // TODO: Battlefield/exile placement, graveyard follow-ups, remembered card identity,
        // depletion, exact selection quality and ordering require richer library state.
        if (!DIG_PARAMETERS.containsAll(node.parameters().keySet())
                || !Set.of("You", "Opponent").contains(node.parameters().getOrDefault("Defined", "You"))
                || !"Library".equals(node.parameters().getOrDefault("SourceZone", "Library"))
                || !"Hand".equals(node.parameters().getOrDefault("DestinationZone", "Hand"))
                || !"Library".equals(node.parameters().getOrDefault("DestinationZone2", "Library"))
                || !node.parameters().containsKey("DigNum") || !literalNonnegativeOrAbsent(node, "DigNum")) { return false; }
        final String selection = node.parameters().getOrDefault("ChangeNum", "1");
        if (!"All".equals(selection) && !literalNonnegativeOrAbsent(node, "ChangeNum")) { return false; }
        return integer(node, "DigNum", 0) <= 32 && ("All".equals(selection) || integer(node, "ChangeNum", 1) <= 32)
                && library.hitProbability(node.parameters().getOrDefault("ChangeValid", "Card")).isPresent();
    }

    private Outcome<State> dig(final AbilityOutcomeDescription node) {
        final int lookedAt = integer(node, "DigNum", 0);
        final int limit = "All".equals(node.parameters().get("ChangeNum")) ? lookedAt : integer(node, "ChangeNum", 1);
        final boolean controller = "You".equals(node.parameters().getOrDefault("Defined", "You"));
        final var counts = IntrinsicLibraryReference.selectedCounts(lookedAt, limit,
                library.hitProbability(node.parameters().getOrDefault("ChangeValid", "Card")).orElseThrow());
        return new Outcome.Random<>(node.path() + ":library-hits", counts.entries().stream().map(entry ->
                new Outcome.Weighted<State>(new Outcome.Atomic<State>(node.path(), state -> {
                    final int hand = controller ? state.controllerHand() : state.opponentHand();
                    return new Outcome.Transition<>((double) evaluator.evaluateCardDraw(hand, entry.value(), controller),
                            state.withHands(controller, EffectMath.add(hand, entry.value())).clearTarget(), "Dig: " + entry.value());
                }), entry.weight())).toList());
    }

    private static boolean acceptsFiltering(final AbilityOutcomeDescription node) {
        final boolean scry = "Scry".equals(node.api());
        return (scry ? SCRY_PARAMETERS : SURVEIL_PARAMETERS).containsAll(node.parameters().keySet())
                && Set.of("You", "Opponent").contains(node.parameters().getOrDefault("Defined", "You"))
                && literalNonnegativeOrAbsent(node, scry ? "ScryNum" : "Amount");
    }

    private Outcome<State> filtering(final AbilityOutcomeDescription node) {
        final int lookedAt = integer(node, "Scry".equals(node.api()) ? "ScryNum" : "Amount", 1);
        final boolean controller = "You".equals(node.parameters().getOrDefault("Defined", "You"));
        // TODO: Surveil's additional graveyard benefits, library exhaustion, replacements and
        // remembered cards. This is only generic future-draw quality; it never adds a hand card
        // or projects a specific library order/number of graveyard cards.
        return new Outcome.Atomic<>(node.path(), state -> {
            final int hand = controller ? state.controllerHand() : state.opponentHand();
            return new Outcome.Transition<>(evaluator.evaluateCardDraw(hand, 1, controller)
                    * library.filteringCardFraction(lookedAt), state.clearTarget(), node.api());
        });
    }

    private Outcome<State> token(final AbilityOutcomeDescription node) {
        final TokenSpec spec = tokenSpec(node);
        if (spec == null) {
            return unresolved(node, "Unsupported intrinsic token form");
        }
        if (spec.amount() == 0) {
            // A known zero quantity is understood, not unsupported; no prototype is needed.
            return new Outcome.Atomic<>(node.path(), state -> new Outcome.Transition<>(0, state.clearTarget(), node.api()));
        }
        return new Outcome.Deferred<>(state -> {
            final List<IntrinsicTokenResolver.Definition> definitions = new java.util.ArrayList<>();
            for (final String script : spec.scripts()) {
                final IntrinsicTokenResolver.Definition definition = tokenProfileResolver instanceof IntrinsicTokenResolver resolver
                        ? resolver.resolveToken(script).orElse(null)
                        : tokenProfileResolver.apply(script).map(profile -> new IntrinsicTokenResolver.Definition(profile, null)).orElse(null);
                if (definition == null || !isCreature(definition.profile()) && definition.resourceValue() == null) {
                    return unresolved(node, "Token definition or its noncreature ability is unavailable");
                }
                if (definition.requiresPowerOverride() && !node.parameters().containsKey("TokenPower")
                        || definition.requiresToughnessOverride() && !node.parameters().containsKey("TokenToughness")) {
                    return unresolved(node, "Variable token prototype requires explicit resolved dimensions");
                }
                if (definition.resourceValue() != null && (node.parameters().containsKey("TokenPower")
                        || node.parameters().containsKey("TokenToughness") || node.parameters().containsKey("TokenTypes"))) {
                    return unresolved(node, "Resource token characteristic overrides are unsupported");
                }
                if (definition.resourceValue() == null) {
                    final var profile = withTokenOverrides(withControl(definition.profile(), spec.recipientIsController()), node);
                    // TODO: Tokens dying to state-based actions after resolution may still
                    // matter to later instructions/death triggers; do not invent a surviving body.
                    if (profile.toughness() <= 0) { return unresolved(node, "Nonpositive token toughness requires state-based-action projection"); }
                    definitions.add(new IntrinsicTokenResolver.Definition(profile, null));
                } else { definitions.add(definition); }
            }
            return new Outcome.Atomic<>(node.path(), current -> {
                double value = 0;
                State projected = current;
                for (final var definition : definitions) {
                    final double tokenValue = definition.resourceValue() == null ? evaluator.evaluatePermanent(definition.profile())
                            : definition.resourceValue().evaluate(spec.recipientIsController() ? current.controllerHand() : current.opponentHand(),
                                    spec.recipientIsController() ? current.controllerLife() : current.opponentLife(),
                                    "True".equalsIgnoreCase(node.parameters().get("TokenTapped")));
                    value += spec.recipientIsController() ? tokenValue : -tokenValue;
                    if (definition.resourceValue() == null) {
                        projected = projected.withCreatureCount(spec.recipientIsController(),
                            EffectMath.add(projected.creatureCount(spec.recipientIsController()),
                                    spec.amount()));
                    }
                    // TODO: Track created noncreature token objects for later sacrifice/control/
                    // attachment steps. They provide option value here, not immediate resources,
                    // and must never inflate the projected creature population.
                }
                return new Outcome.Transition<>((double) value * spec.amount(),
                        projected.clearTarget(), node.api());
            });
        });
    }

    private static AbilityOutcomeDescription investigateToken(final AbilityOutcomeDescription node) {
        // Forge investigates once per Num, creating one Clue each time. The intrinsic value
        // is first-order creation value, not any extra "whenever you investigate" reactions.
        // TODO: Targeted/all-player, optional and remembered-investigator event bindings.
        if (!INVESTIGATE_PARAMETERS.containsAll(node.parameters().keySet())
                || !Set.of("You", "Opponent").contains(node.parameters().getOrDefault("Defined", "You"))
                || !literalNonnegativeOrAbsent(node, "Num")) { return null; }
        final java.util.Map<String, String> parameters = new java.util.LinkedHashMap<>(node.parameters());
        parameters.put("TokenOwner", parameters.getOrDefault("Defined", "You"));
        parameters.put("TokenAmount", parameters.getOrDefault("Num", "1"));
        parameters.put("TokenScript", "c_a_clue_draw");
        parameters.remove("Defined");
        parameters.remove("Num");
        return new AbilityOutcomeDescription(node.path(), "Token", parameters, node.choices(), node.next(), node.issue());
    }

    private Outcome<State> life(final AbilityOutcomeDescription node) {
        final List<TargetRef> fixed = fixedPlayerRecipients(node);
        if (fixed != null) { return new Outcome.Sequence<>(fixed.stream().map(target -> lifeAtomic(node, target)).toList()); }
        final PlayerTarget target = playerTarget(node);
        if (target == null) {
            return unresolved(node, "Unsupported intrinsic life recipient");
        }
        if (target.fixed() != null) {
            return optionalSetLife(node, lifeAtomic(node, target.fixed()));
        }
        return optionalSetLife(node, new Outcome.Deferred<>(state -> new Outcome.Target<>(node.path() + ":target",
                current -> playerCandidates(current, target), State::withTarget,
                lifeAtomic(node, null), true)));
    }

    private static Outcome<State> optionalSetLife(final AbilityOutcomeDescription node, final Outcome<State> effect) {
        return "SetLife".equals(node.api()) && "0".equals(node.parameters().get("TargetMin"))
                ? OutcomeChoices.optional(node.path() + ":up-to-player", effect, true) : effect;
    }

    private Outcome<State> lifeAtomic(final AbilityOutcomeDescription node,
            final TargetRef fixedTarget) {
        return new Outcome.Atomic<>(node.path(), current -> {
            final TargetRef target = fixedTarget == null ? current.target() : fixedTarget;
            if (!isPlayer(target)) {
                return null;
            }
            final int amount = integer(node, "LifeAmount", 0);
            final boolean controller = controls(target, current);
            final int before = controller ? current.controllerLife() : current.opponentLife();
            final int after = "SetLife".equals(node.api()) ? amount : "GainLife".equals(node.api())
                    ? boundedAdd(before, amount) : Math.max(0, before - amount);
            final int value = "SetLife".equals(node.api()) ? netLifeValue(before, after, controller) : "GainLife".equals(node.api())
                    ? evaluator.evaluateLifeGain(before, amount, controller)
                    : evaluator.evaluateLifeLoss(before, amount, controller);
            return new Outcome.Transition<>((double) value,
                    current.withLife(controller, after).clearTarget(), node.api());
        });
    }

    private Outcome<State> discard(final AbilityOutcomeDescription node) {
        final List<TargetRef> fixed = fixedPlayerRecipients(node);
        if (fixed != null) { return new Outcome.Sequence<>(fixed.stream().map(target -> discardAtomic(node, target)).toList()); }
        final PlayerTarget target = playerTarget(node);
        if (target == null) {
            return unresolved(node, "Unsupported intrinsic discard recipient");
        }
        if (target.fixed() != null) {
            return discardAtomic(node, target.fixed());
        }
        return new Outcome.Deferred<>(state -> new Outcome.Target<>(node.path() + ":target",
                current -> playerCandidates(current, target), State::withTarget,
                discardAtomic(node, null), true));
    }

    private Outcome<State> discardAtomic(final AbilityOutcomeDescription node,
            final TargetRef fixedTarget) {
        return new Outcome.Deferred<>(state -> {
            final TargetRef target = fixedTarget == null ? state.target() : fixedTarget;
            if (!isPlayer(target)) { return unresolved(node, "Unsupported intrinsic discard recipient"); }
            final int hand = controls(target, state) ? state.controllerHand() : state.opponentHand();
            final double eligible = library.hitProbability(node.parameters().getOrDefault("DiscardValid", "Card")).orElseThrow();
            if (eligible == 1 || "Hand".equals(node.parameters().get("Mode"))) {
                return discardAtomic(node, target, hand);
            }
            // TODO: Conditional hand composition, selection bias, exact revealed identities,
            // graveyard value and correlated restricted discards across a resolution.
            if (hand > 32) { return unresolved(node, "Restricted discard hand distribution limit"); }
            return new Outcome.Random<>(node.path() + ":eligible-discard-cards",
                    IntrinsicLibraryReference.selectedCounts(hand, hand, eligible).entries().stream().map(entry ->
                            new Outcome.Weighted<State>(discardAtomic(node, target, entry.value()), entry.weight())).toList());
        });
    }

    private Outcome<State> discardAtomic(final AbilityOutcomeDescription node,
            final TargetRef fixedTarget, final int eligibleCards) {
        return new Outcome.Atomic<>(node.path(), current -> {
            final TargetRef target = fixedTarget == null ? current.target() : fixedTarget;
            if (!isPlayer(target)) {
                return null;
            }
            final boolean controller = controls(target, current);
            final int hand = controller ? current.controllerHand() : current.opponentHand();
            final String mode = node.parameters().getOrDefault("Mode", "Random");
            final int requested = "Hand".equals(mode) ? hand : integer(node, "NumCards", 1);
            final int discarded = Math.min(Math.min(hand, eligibleCards), Math.max(0, requested));
            // RevealYouChoose targeting oneself still gives the affected player the choice.
            // TODO: Opponent-hand best-card selection premium; random-discard utility is a
            // conservative generic-quality proxy, not a claim that the actual choices are random.
            final int value = "TgtChoose".equals(mode) || "RevealYouChoose".equals(mode) && controller
                    ? evaluator.evaluateChosenDiscard(hand, discarded, eligibleCards, controller)
                    : evaluator.evaluateRandomDiscard(hand, discarded, controller);
            return new Outcome.Transition<>((double) value,
                    current.withHands(controller, hand - discarded).clearTarget(), node.api());
        });
    }

    private Outcome<State> mana(final AbilityOutcomeDescription node) {
        final boolean controller = "You".equals(node.parameters().getOrDefault("Defined", "You"));
        final int amount = integer(node, "Amount", 1);
        return new Outcome.Atomic<>(node.path(), current -> new Outcome.Transition<>(
                (double) evaluator.evaluateMana(amount, controller),
                current.withMana(controller, EffectMath.add(
                        controller ? current.controllerMana() : current.opponentMana(), amount)),
                node.api()));
    }

    /**
     * Values the common "add one mana of the type just produced" trigger form. The reference
     * state does not retain a color-specific mana pool, so this deliberately treats one reflected
     * produced mana as one unrestricted mana. Other reflected properties and dynamic amounts need
     * an event-linked resource model.
     */
    private Outcome<State> manaReflected(final AbilityOutcomeDescription node) {
        final boolean controller = "You".equals(node.parameters().getOrDefault("Defined", "You"));
        final int amount = integer(node, "Amount", 1);
        return new Outcome.Atomic<>(node.path(), current -> new Outcome.Transition<>(
                (double) evaluator.evaluateMana(amount, controller),
                current.withMana(controller, EffectMath.add(
                        controller ? current.controllerMana() : current.opponentMana(), amount)),
                node.api()));
    }

    private Outcome<State> damage(final AbilityOutcomeDescription node) {
        if ("DamageAll".equals(node.api())) {
            return damageAll(node);
        }
        final DamageTarget target = damageTarget(node);
        if (target == null) {
            return unresolved(node, "Unsupported intrinsic damage recipient");
        }
        if (target.fixed() != null) {
            return damageAtomic(node, target.fixed());
        }
        return new Outcome.Deferred<>(state -> new Outcome.Target<>(node.path() + ":target",
                current -> damageCandidates(current, target), State::withTarget,
                damageAtomic(node, null), true));
    }

    private Outcome<State> damageAll(final AbilityOutcomeDescription node) {
        final List<TargetRef> targets = damageAllTargets(node);
        return new Outcome.Atomic<>(node.path(), current -> {
            int value = 0;
            State projected = current;
            long creatureDamage = 0;
            final int amount = integer(node, "NumDmg", 0);
            final CreatureGroupTarget creatureTarget = creatureGroupTarget(node);
            if (creatureTarget != null) {
                if (creatureTarget.controller()) {
                    creatureDamage += groupDamageDealt(current, true, creatureTarget.other(), amount);
                    final GroupApplication application = applyDamageGroup(projected, node, true,
                            creatureTarget.other(), damageSource(node, current));
                    if (!application.supported()) {
                        return null;
                    }
                    projected = application.state();
                    value = EffectMath.add(value, application.value());
                }
                if (creatureTarget.opponent()) {
                    creatureDamage += groupDamageDealt(current, false, creatureTarget.other(), amount);
                    final GroupApplication application = applyDamageGroup(projected, node, false,
                            creatureTarget.other(), damageSource(node, current));
                    if (!application.supported()) {
                        return null;
                    }
                    projected = application.state();
                    value = EffectMath.add(value, application.value());
                }
            }
            return damageLifeTransition(node, current, projected.clearTarget(),
                    targets.contains(TargetRef.CONTROLLER_PLAYER) ? amount : 0,
                    targets.contains(TargetRef.OPPONENT_PLAYER) ? amount : 0, creatureDamage, value);
        });
    }

    private static long groupDamageDealt(final State state, final boolean controller,
            final boolean other, final int amount) {
        final CreatureProfile representative = controller ? state.controllerCreature() : state.opponentCreature();
        final long others = representative.present() ? state.creatureCount(controller) : 0;
        final PermanentProfile host = state.sourcePermanent();
        final long source = !other && isCreature(host) && host.controlledByAi() == controller ? 1 : 0;
        return (others + source) * amount;
    }

    private GroupApplication applyDamageGroup(final State state,
            final AbilityOutcomeDescription node, final boolean controller, final boolean other,
            final PermanentProfile damageSource) {
        final CreatureProfile representative = controller
                ? state.controllerCreature() : state.opponentCreature();
        if (!simpleKeywords(representative.keywords())) {
            return GroupApplication.unsupported(state);
        }

        State projected = state;
        int value = 0;
        final int amount = integer(node, "NumDmg", 0);
        final int count = state.creatureCount(controller);
        // All recipients take this damage simultaneously. Losing the source in the first
        // recipient group must not erase deathtouch for later groups in the same batch.
        final boolean lethal = amount > 0 && representative.present() && (amount >= representative.toughness()
                || hasKeyword(damageSource, "deathtouch"));
        if (count > 0 && lethal && !representative.indestructible()
                && !hasKeyword(representative, "indestructible")) {
            value = EffectMath.multiply(count, evaluator.evaluateCreatureDelta(
                    representative, CreatureProfile.absent(), controller));
            projected = projected.withCreatures(controller, CreatureProfile.absent())
                    .withCreatureCount(controller, 0);
        }

        final PermanentProfile source = projected.sourcePermanent();
        final boolean sourceLethal = amount > 0 && isCreature(source) && !hasKeyword(source, "indestructible")
                && (amount >= source.toughness() || hasKeyword(damageSource, "deathtouch"));
        if (!other && sourceLethal && source.controlledByAi() == controller) {
            value = EffectMath.add(value, evaluator.evaluatePermanentDelta(source,
                    PermanentProfile.absent(), controller));
            projected = projected.withSourcePermanent(PermanentProfile.absent());
        }
        return new GroupApplication(projected, value, true);
    }

    private Outcome<State> damageAtomic(final AbilityOutcomeDescription node,
            final TargetRef fixedTarget) {
        return new Outcome.Atomic<>(node.path(), current -> {
            final TargetRef target = fixedTarget == null ? current.target() : fixedTarget;
            if (isPlayer(target)) {
                return damagePlayerTransition(node, current, target);
            }
            if (target != null && permanent(current, target).kind() == PermanentKind.PLANESWALKER) {
                final PermanentProfile before = permanent(current, target);
                final int amount = integer(node, "NumDmg", 0);
                if (!before.present() || !simpleKeywords(before.keywords())) { return null; }
                // Zero loyalty is a state-based death, not destruction. Indestructible cannot
                // save it, and deathtouch does not turn a loyalty loss into a creature death.
                final int loyalty = Math.max(0, before.loyalty() - amount);
                final PermanentProfile after = loyalty == 0 ? PermanentProfile.absent()
                        : new PermanentProfile(true, before.kind(), before.controlledByAi(), before.power(),
                                before.toughness(), before.keywords(), before.basicLand(), loyalty);
                final boolean controller = controls(target, current);
                final int value = evaluator.evaluatePermanentDelta(before, after, controller);
                final State projected = target == TargetRef.SOURCE ? current.withSourcePermanent(after)
                        : current.withPermanent(controller, after);
                // Lifelink uses all damage dealt, not just loyalty removed.
                // TODO: Prevention/replacements, loyalty locks, battles and creature/planeswalker
                // multi-type profiles require richer characteristics and actual dealt amounts.
                return damageLifeTransition(node, current, projected.clearTarget(), 0, 0, amount, value);
            }
            if (!isCreatureTarget(target)) {
                return null;
            }
            final CreatureProfile before = creature(current, target);
            if (target == TargetRef.WATCHED_CREATURE && !before.present()) {
                // A departed Defined recipient is known unavailable, not an unknown outcome.
                // Other instructions in the same resolution still execute.
                return new Outcome.Transition<>(0, current.clearTarget());
            }
            if (before == null || !before.present() || !simpleKeywords(before.keywords())) {
                return null;
            }
            final int amount = integer(node, "NumDmg", 0);
            final boolean lethal = amount > 0 && !before.indestructible()
                    && (amount >= before.toughness() || hasKeyword(damageSource(node, current), "deathtouch"));
            if (!lethal) {
                // Nonlethal marked damage is not represented in the first intrinsic state slice.
                return damageLifeTransition(node, current, current.clearTarget(), 0, 0, amount, 0);
            }
            final boolean controller = controls(target, current);
            final int value = evaluator.evaluateCreatureDelta(before,
                    IntrinsicReferenceModel.CreatureProfile.absent(), controller);
            final State projected = removePermanent(current, target).clearTarget();
            return damageLifeTransition(node, current, projected, 0, 0, amount, value);
        });
    }

    private Outcome.Transition<State> damagePlayerTransition(final AbilityOutcomeDescription node,
            final State current, final TargetRef target) {
        if (!isPlayer(target)) { return null; }
        final boolean controller = controls(target, current);
        final int amount = integer(node, "NumDmg", 0);
        return damageLifeTransition(node, current, current.clearTarget(), controller ? amount : 0,
                controller ? 0 : amount, 0, 0);
    }

    /** Damage and lifelink are simultaneous: evaluate only the final net change for each player. */
    private Outcome.Transition<State> damageLifeTransition(final AbilityOutcomeDescription node,
            final State before, final State projected, final long controllerDamage, final long opponentDamage,
            final long creatureDamage, final int permanentValue) {
        final PermanentProfile source = damageSource(node, before);
        if ((controllerDamage > 0 || opponentDamage > 0) && hasKeyword(source, "infect")) { return null; }
        // Supported DealDamage/DamageAll scripts represent noncombat damage. Toxic applies only
        // to combat damage (Keyword.TOXIC), so it neither prevents life loss nor adds poison here.
        final long gain = hasKeyword(source, "lifelink") ? controllerDamage + opponentDamage + creatureDamage : 0;
        // Damage isn't capped by toughness or remaining life. Indestructibility doesn't prevent
        // damage or lifelink. TODO: Prevention, replacements and protection need actual dealt amounts.
        return netLifeTransition(node, before, projected,
                -controllerDamage + (source.controlledByAi() ? gain : 0),
                -opponentDamage + (source.controlledByAi() ? 0 : gain), permanentValue);
    }

    private Outcome.Transition<State> netLifeTransition(final AbilityOutcomeDescription node,
            final State before, final State projected, final long controllerChange,
            final long opponentChange, final int permanentValue) {
        final int controllerAfter = boundedLife((long) before.controllerLife() + controllerChange);
        final int opponentAfter = boundedLife((long) before.opponentLife() + opponentChange);
        int value = EffectMath.add(permanentValue, netLifeValue(before.controllerLife(), controllerAfter, true));
        value = EffectMath.add(value, netLifeValue(before.opponentLife(), opponentAfter, false));
        return new Outcome.Transition<>((double) value,
                projected.withLife(true, controllerAfter).withLife(false, opponentAfter), node.api());
    }

    private int netLifeValue(final int before, final int after, final boolean controller) {
        return after < before ? evaluator.evaluateLifeLoss(before, before - after, controller)
                : evaluator.evaluateLifeGain(before, after - before, controller);
    }

    private static int boundedLife(final long life) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, life));
    }

    private Outcome<State> fight(final AbilityOutcomeDescription node) {
        final PermanentTarget target = fightTarget(node);
        if (target == null) {
            return unresolved(node, "Unsupported intrinsic fight target");
        }
        return new Outcome.Deferred<>(state -> new Outcome.Target<>(node.path() + ":target",
                current -> permanentCandidates(current, target), State::withTarget,
                fightAtomic(node), true));
    }

    private Outcome<State> fightAtomic(final AbilityOutcomeDescription node) {
        return new Outcome.Atomic<>(node.path(), current -> {
            final TargetRef target = current.target();
            final PermanentProfile source = current.sourcePermanent();
            final PermanentProfile opposing = target == null ? PermanentProfile.absent()
                    : permanent(current, target);
            if (!isCreature(source) || !isCreature(opposing)) {
                return null;
            }
            // TODO: Infect/wither need counter-based deltas (including indestructible casualties),
            // while protection/prevention/replacements need actual dealt damage for each fighter.
            if (hasKeyword(source, "infect") || hasKeyword(source, "wither")
                    || hasKeyword(opposing, "infect") || hasKeyword(opposing, "wither")) { return null; }

            // Fight damage is simultaneous. Marked damage on survivors is intentionally omitted
            // from this bounded reference state, so only deaths affect the projected profiles.
            final PermanentProfile sourceAfter = survivesFight(source, opposing)
                    ? source : PermanentProfile.absent();
            final PermanentProfile targetAfter = survivesFight(opposing, source)
                    ? opposing : PermanentProfile.absent();
            int value = evaluator.evaluatePermanentDelta(source, sourceAfter,
                    source.controlledByAi());
            value = EffectMath.add(value, evaluator.evaluatePermanentDelta(opposing, targetAfter,
                    controls(target, current)));

            State projected = sourceAfter.present()
                    ? current : current.withSourcePermanent(PermanentProfile.absent());
            projected = targetAfter.present()
                    ? replacePermanent(projected, target, targetAfter)
                    : removePermanent(projected, target);
            final long sourceGain = hasKeyword(source, "lifelink") ? Math.max(0, source.power()) : 0;
            final long opposingGain = hasKeyword(opposing, "lifelink") ? Math.max(0, opposing.power()) : 0;
            // Both fighters deal full power simultaneously, even if either dies. Gains for the
            // same controller are combined before applying the nonlinear life utility.
            final long controllerGain = (source.controlledByAi() ? sourceGain : 0)
                    + (opposing.controlledByAi() ? opposingGain : 0);
            final long opponentGain = (source.controlledByAi() ? 0 : sourceGain)
                    + (opposing.controlledByAi() ? 0 : opposingGain);
            return netLifeTransition(node, current, projected.clearTarget(), controllerGain, opponentGain, value);
        });
    }

    private static boolean survivesFight(final PermanentProfile fighter,
            final PermanentProfile opponent) {
        final CreatureProfile creature = toCreature(fighter);
        final CreatureProfile enemy = toCreature(opponent);
        if (creature.toughness() <= 0) {
            return false;
        }
        return creature.indestructible()
                || enemy.power() < creature.toughness()
                        && !(enemy.power() > 0 && hasKeyword(enemy, "deathtouch"));
    }

    private Outcome<State> removal(final AbilityOutcomeDescription node) {
        final PermanentTarget target = permanentTarget(node);
        if (target == null) {
            return unresolved(node, "Unsupported intrinsic removal target");
        }
        if (target.fixed() != null) {
            return removalAtomic(node, target.fixed());
        }
        return new Outcome.Deferred<>(state -> new Outcome.Target<>(node.path() + ":target",
                current -> permanentCandidates(current, target), State::withTarget,
                removalAtomic(node, null), true));
    }

    private Outcome<State> removalAtomic(final AbilityOutcomeDescription node,
            final TargetRef fixedTarget) {
        return new Outcome.Atomic<>(node.path(), current -> {
            final TargetRef target = fixedTarget == null ? current.target() : fixedTarget;
            if (target == null) {
                return null;
            }
            final PermanentProfile before = permanent(current, target);
            if (!before.present()) {
                if (target == TargetRef.WATCHED_CREATURE) {
                    return new Outcome.Transition<>(0, current.clearTarget());
                }
                return null;
            }
            final boolean destroy = "Destroy".equals(node.api());
            if (destroy && hasKeyword(before, "indestructible")) {
                if (target == TargetRef.WATCHED_CREATURE) {
                    return new Outcome.Transition<>(0, current.clearTarget());
                }
                return null;
            }
            if (fixedTarget == null && !canTarget(before, controls(target, current))) {
                return null;
            }
            final boolean controller = controls(target, current);
            int value = evaluator.evaluatePermanentDelta(before, PermanentProfile.absent(), controller);
            State projected = removePermanent(current, target);
            if (!destroy && "Hand".equalsIgnoreCase(node.parameters().get("Destination"))
                    && before.kind() != PermanentKind.TOKEN) {
                // The reference model has no token flag on creature profiles, so a modeled
                // creature target represents a card. Live token bounce remains a separate case.
                final int hand = controller ? current.controllerHand() : current.opponentHand();
                value = EffectMath.add(value, evaluator.evaluateCardDraw(hand, 1, controller));
                projected = projected.withHands(controller, EffectMath.add(hand, 1));
            }
            return new Outcome.Transition<>((double) value, projected.clearTarget(), node.api());
        });
    }

    private Outcome<State> sacrifice(final AbilityOutcomeDescription node) {
        if (playerSacrifice(node)) {
            final List<TargetRef> fixed = fixedPlayerRecipients(node);
            if (fixed != null) {
                return new Outcome.Sequence<>(fixed.stream().map(player ->
                        playerCreatureSacrifice(node, player == TargetRef.CONTROLLER_PLAYER, integer(node, "Amount", 1))).toList());
            }
            final PlayerTarget player = playerTarget(node);
            if (player.fixed() != null) {
                return playerCreatureSacrifice(node, player.fixed() == TargetRef.CONTROLLER_PLAYER, integer(node, "Amount", 1));
            }
            return new Outcome.Target<>(node.path() + ":affected-player", state -> playerCandidates(state, player),
                    State::withTarget, new Outcome.Deferred<>(state -> playerCreatureSacrifice(node,
                            state.target() == TargetRef.CONTROLLER_PLAYER, integer(node, "Amount", 1))), true);
        }
        final SacrificeTarget target = sacrificeTarget(node);
        if (target == null) {
            return unresolved(node, "Unsupported intrinsic sacrifice target");
        }
        if (target.scope() == SacrificeTargetScope.SELF) {
            return sacrificeAtomic(node, TargetRef.SOURCE);
        }
        return new Outcome.Deferred<>(state -> new Outcome.Target<>(node.path() + ":target",
                current -> sacrificeCandidates(current, target), State::withTarget,
                sacrificeAtomic(node, null), true));
    }

    private Outcome<State> playerCreatureSacrifice(final AbilityOutcomeDescription node,
            final boolean controller, final int remaining) {
        return new Outcome.Deferred<>(state -> {
            final List<Outcome<State>> options = new java.util.ArrayList<>();
            final TargetRef representative = controller ? TargetRef.CONTROLLER_CREATURE : TargetRef.OPPONENT_CREATURE;
            final CreatureProfile profile = controller ? state.controllerCreature() : state.opponentCreature();
            if (remaining > 0 && state.creatureCount(controller) > 0 && profile.present()) {
                final Outcome<State> removeOne = new Outcome.Atomic<>(node.path(), current -> {
                    final int count = current.creatureCount(controller) - 1;
                    final int value = evaluator.evaluateCreatureDelta(profile, CreatureProfile.absent(), controller);
                    State projected = current.withCreatureCount(controller, count).clearTarget();
                    if (count == 0) {
                        projected = projected.withCreatures(controller, CreatureProfile.absent()).withP1p1(representative, 0);
                    }
                    return new Outcome.Transition<>((double) value, projected, "Sacrifice reference creature");
                });
                options.add(new Outcome.Sequence<>(List.of(removeOne, playerCreatureSacrifice(node, controller, remaining - 1))));
            }
            final PermanentProfile source = state.sourcePermanent();
            if (remaining > 0 && !"Creature.Other".equals(node.parameters().get("SacValid"))
                    && isCreature(source) && source.controlledByAi() == controller) {
                options.add(new Outcome.Sequence<>(List.of(sacrificeAtomic(node, TargetRef.SOURCE),
                        playerCreatureSacrifice(node, controller, remaining - 1))));
            }
            final PermanentProfile watched = state.watchedCreature();
            if (remaining > 0 && isCreature(watched) && watched.controlledByAi() == controller) {
                // Like the source, the bound entrant is a separate object, not the generic
                // population representative. Creature.Other excludes the source, not this entrant.
                options.add(new Outcome.Sequence<>(List.of(sacrificeAtomic(node, TargetRef.WATCHED_CREATURE),
                        playerCreatureSacrifice(node, controller, remaining - 1))));
            }
            // No creature is an understood no-op, not an illegal player target. Independent
            // follow-ups still resolve. TODO: Heterogeneous populations, sacrifice restrictions,
            // replacement/death triggers and remembered identities need richer object state.
            if (options.isEmpty()) {
                return new Outcome.Atomic<>(current -> new Outcome.Transition<>(0, current.clearTarget()));
            }
            // The affected player chooses its own sacrifices, not the spell's controller.
            return new Outcome.Choice<>(node.path() + ":sacrifice-choice", options, 1, 1, false, controller);
        });
    }

    private Outcome<State> sacrificeAtomic(final AbilityOutcomeDescription node,
            final TargetRef fixedTarget) {
        return new Outcome.Atomic<>(node.path(), current -> {
            final TargetRef target = fixedTarget == null ? current.target() : fixedTarget;
            if (target == null) {
                return null;
            }
            final PermanentProfile before = permanent(current, target);
            if (!before.present()) {
                return null;
            }
            // Sacrifice is not destruction: indestructible does not prevent it.
            final int value = evaluator.evaluatePermanentDelta(before, PermanentProfile.absent(),
                    controls(target, current));
            return new Outcome.Transition<>((double) value,
                    removePermanent(current, target).clearTarget(), node.api());
        });
    }

    private Outcome<State> sacrificeAll(final AbilityOutcomeDescription node) {
        // The reference state has one representative creature and a count per side. Keep this
        // group adapter separate from single-target sacrifice so both sides are applied to the
        // same pre-event state and source self-sacrifice is not accidentally counted twice.
        final CreatureGroupTarget target = creatureGroupTarget(node);
        if (target == null) {
            return unresolved(node, "Unsupported intrinsic sacrifice group");
        }
        return new Outcome.Atomic<>(node.path(), current -> {
            State projected = current;
            int value = 0;
            if (target.controller()) {
                final GroupApplication application = applySacrificeGroup(projected, true,
                        target.other());
                if (!application.supported()) {
                    return null;
                }
                projected = application.state();
                value = EffectMath.add(value, application.value());
            }
            if (target.opponent()) {
                final GroupApplication application = applySacrificeGroup(projected, false,
                        target.other());
                if (!application.supported()) {
                    return null;
                }
                projected = application.state();
                value = EffectMath.add(value, application.value());
            }
            return new Outcome.Transition<>((double) value, projected.clearTarget(), node.api());
        });
    }

    private Outcome<State> destroyAll(final AbilityOutcomeDescription node) {
        // Only explicit creature groups are represented. The reference state tracks one
        // representative non-source creature per side, so mass removal of other permanent types
        // or effects with unknown recipient sets must remain unresolved.
        final CreatureGroupTarget target = creatureGroupTarget(node);
        if (target == null) {
            return unresolved(node, "Unsupported intrinsic destruction group");
        }
        return new Outcome.Atomic<>(node.path(), current -> {
            State projected = current;
            int value = 0;
            if (target.controller()) {
                final GroupApplication application = applyDestroyGroup(projected, true,
                        target.other());
                if (!application.supported()) {
                    return null;
                }
                projected = application.state();
                value = EffectMath.add(value, application.value());
            }
            if (target.opponent()) {
                final GroupApplication application = applyDestroyGroup(projected, false,
                        target.other());
                if (!application.supported()) {
                    return null;
                }
                projected = application.state();
                value = EffectMath.add(value, application.value());
            }
            return new Outcome.Transition<>((double) value, projected.clearTarget(), node.api());
        });
    }

    private GroupApplication applyDestroyGroup(final State state, final boolean controller,
            final boolean other) {
        final CreatureProfile representative = controller
                ? state.controllerCreature() : state.opponentCreature();
        if (!simpleKeywords(representative.keywords())) {
            return GroupApplication.unsupported(state);
        }

        State projected = state;
        int value = 0;
        final boolean representativeCanBeDestroyed = !representative.keywords().stream()
                .anyMatch(keyword -> keyword.equalsIgnoreCase("indestructible"));
        final int count = state.creatureCount(controller);
        if (representativeCanBeDestroyed && count > 0 && representative.present()) {
            value = EffectMath.multiply(evaluator.evaluateCreatureDelta(
                    representative, CreatureProfile.absent(), controller), count);
            projected = projected.withCreatures(controller, CreatureProfile.absent())
                    .withCreatureCount(controller, 0);
        }

        final PermanentProfile source = projected.sourcePermanent();
        if (!other && isCreature(source) && source.controlledByAi() == controller
                && !hasKeyword(source, "indestructible")) {
            value = EffectMath.add(value, evaluator.evaluatePermanentDelta(source,
                    PermanentProfile.absent(), controller));
            projected = projected.withSourcePermanent(PermanentProfile.absent());
        }
        return new GroupApplication(projected, value, true);
    }

    private Outcome<State> changeZoneAll(final AbilityOutcomeDescription node) {
        // Only fixed creature groups and battlefield-to-exile/hand movement are modeled. The
        // reference state has no complete library, token population, or return-link model, so
        // other group zone changes remain unresolved.
        final CreatureGroupTarget target = creatureGroupTarget(node, "ChangeType");
        if (target == null) {
            return unresolved(node, "Unsupported intrinsic zone-change group");
        }
        return new Outcome.Atomic<>(node.path(), current -> {
            State projected = current;
            int value = 0;
            if (target.controller()) {
                final GroupApplication application = applyChangeZoneGroup(projected, node, true,
                        target.other());
                if (!application.supported()) {
                    return null;
                }
                projected = application.state();
                value = EffectMath.add(value, application.value());
            }
            if (target.opponent()) {
                final GroupApplication application = applyChangeZoneGroup(projected, node, false,
                        target.other());
                if (!application.supported()) {
                    return null;
                }
                projected = application.state();
                value = EffectMath.add(value, application.value());
            }
            return new Outcome.Transition<>((double) value, projected.clearTarget(), node.api());
        });
    }

    private GroupApplication applyChangeZoneGroup(final State state,
            final AbilityOutcomeDescription node, final boolean controller, final boolean other) {
        final CreatureProfile representative = controller
                ? state.controllerCreature() : state.opponentCreature();
        if (!simpleKeywords(representative.keywords())) {
            return GroupApplication.unsupported(state);
        }

        final boolean handDestination = "Hand".equalsIgnoreCase(node.parameters().get("Destination"));
        State projected = state;
        int value = 0;
        final int count = state.creatureCount(controller);
        if (count > 0 && representative.present()) {
            value = EffectMath.multiply(evaluator.evaluateCreatureDelta(
                    representative, CreatureProfile.absent(), controller), count);
            if (handDestination) {
                final int hand = controller ? state.controllerHand() : state.opponentHand();
                value = EffectMath.add(value, evaluator.evaluateCardDraw(hand, count, controller));
                projected = projected.withHands(controller, EffectMath.add(hand, count));
            }
            projected = projected.withCreatures(controller, CreatureProfile.absent())
                    .withCreatureCount(controller, 0);
        }

        final PermanentProfile source = projected.sourcePermanent();
        if (!other && isCreature(source) && source.controlledByAi() == controller) {
            value = EffectMath.add(value, evaluator.evaluatePermanentDelta(source,
                    PermanentProfile.absent(), controller));
            projected = projected.withSourcePermanent(PermanentProfile.absent());
            if (handDestination && source.kind() != PermanentKind.TOKEN) {
                final int hand = controller ? projected.controllerHand() : projected.opponentHand();
                value = EffectMath.add(value, evaluator.evaluateCardDraw(hand, 1, controller));
                projected = projected.withHands(controller, EffectMath.add(hand, 1));
            }
        }
        return new GroupApplication(projected, value, true);
    }

    private GroupApplication applySacrificeGroup(final State state, final boolean controller,
            final boolean other) {
        final CreatureProfile representative = controller
                ? state.controllerCreature() : state.opponentCreature();
        if (!simpleKeywords(representative.keywords())) {
            return GroupApplication.unsupported(state);
        }

        final int count = state.creatureCount(controller);
        State projected = state.withCreatures(controller, CreatureProfile.absent())
                .withCreatureCount(controller, 0);
        int value = count > 0 && representative.present()
                ? EffectMath.multiply(evaluator.evaluateCreatureDelta(
                        representative, CreatureProfile.absent(), controller), count) : 0;

        final PermanentProfile source = projected.sourcePermanent();
        if (!other && isCreature(source) && source.controlledByAi() == controller) {
            value = EffectMath.add(value, evaluator.evaluatePermanentDelta(source,
                    PermanentProfile.absent(), controller));
            projected = projected.withSourcePermanent(PermanentProfile.absent());
        }
        return new GroupApplication(projected, value, true);
    }

    private Outcome<State> gainControl(final AbilityOutcomeDescription node) {
        final PermanentTarget target = permanentTarget(node);
        if (target == null || newControllerIsAi(node) == null) {
            return unresolved(node, "Unsupported intrinsic control-change target");
        }
        if (target.fixed() != null) {
            return gainControlAtomic(node, target.fixed());
        }
        return new Outcome.Deferred<>(state -> new Outcome.Target<>(node.path() + ":target",
                current -> permanentCandidates(current, target), State::withTarget,
                gainControlAtomic(node, null), true));
    }

    private Outcome<State> gainControlAtomic(final AbilityOutcomeDescription node,
            final TargetRef fixedTarget) {
        return new Outcome.Atomic<>(node.path(), current -> {
            final TargetRef target = fixedTarget == null ? current.target() : fixedTarget;
            if (target == null) {
                return null;
            }
            final PermanentProfile before = permanent(current, target);
            final Boolean newController = newControllerIsAi(node);
            if (!before.present() || newController == null) {
                return null;
            }
            final boolean oldController = controls(target, current);
            if (oldController == newController) {
                return new Outcome.Transition<>(0d, current.clearTarget(), node.api());
            }
            final int boardValue = evaluator.evaluatePermanent(before);
            final int value = newController ? EffectMath.multiply(boardValue, 2)
                    : EffectMath.multiply(boardValue, -2);
            final PermanentProfile after = controlledPermanent(before, newController);
            return new Outcome.Transition<>((double) value,
                    moveControl(current, target, after, newController).clearTarget(), node.api());
        });
    }

    private Outcome<State> copyPermanent(final AbilityOutcomeDescription node) {
        return new Outcome.Atomic<>(node.path(), current -> {
            final PermanentProfile source = current.sourcePermanent();
            if (!source.present()) {
                return null;
            }
            final int amount = integer(node, "NumCopies", 1);
            final boolean copyControllerIsAi = copyControllerIsAi(node, source);
            final PermanentProfile copy = copiedPermanent(source, copyControllerIsAi);
            final int value = EffectMath.multiply(amount,
                    evaluator.evaluatePermanentDelta(PermanentProfile.absent(), copy,
                            copyControllerIsAi));
            State projected = current;
            if (isCreature(copy)) {
                final CreatureProfile representative = copyCreature(copy);
                if (!(copyControllerIsAi ? current.controllerCreature() : current.opponentCreature())
                        .present()) {
                    projected = projected.withCreatures(copyControllerIsAi, representative);
                }
                projected = projected.withCreatureCount(copyControllerIsAi,
                        current.creatureCount(copyControllerIsAi) + amount);
            } else if (!(copyControllerIsAi ? current.controllerPermanent()
                    : current.opponentPermanent()).present()) {
                // The reference state has one generic noncreature slot per side. It can record
                // that a copy exists, but not the exact multiplicity of noncreature copies.
                projected = projected.withPermanent(copyControllerIsAi, copy);
            }
            // TODO: Account for copy modifiers, copied abilities, ETB effects, and the tactical
            // value of TokenTapped/TokenAttacking/TokenBlocking once those states are modeled.
            return new Outcome.Transition<>((double) value, projected.clearTarget(), node.api());
        });
    }

    private static boolean copyControllerIsAi(final AbilityOutcomeDescription node,
            final PermanentProfile source) {
        final boolean sourceControllerIsAi = source.controlledByAi();
        final boolean followsSource = "You".equalsIgnoreCase(
                node.parameters().getOrDefault("Controller", "You"));
        return followsSource ? sourceControllerIsAi : !sourceControllerIsAi;
    }

    private static PermanentProfile copiedPermanent(final PermanentProfile source,
            final boolean controllerIsAi) {
        final PermanentKind kind = isCreature(source) ? PermanentKind.TOKEN : source.kind();
        return new PermanentProfile(true, kind, controllerIsAi, source.power(), source.toughness(),
                source.keywords(), source.basicLand(), source.loyalty());
    }

    private static CreatureProfile copyCreature(final PermanentProfile permanent) {
        return toCreature(permanent);
    }

    private static TokenSpec tokenSpec(final AbilityOutcomeDescription node) {
        final String rawScripts = node.parameters().get("TokenScript");
        if (rawScripts == null || rawScripts.isBlank()) {
            return null;
        }
        final List<String> scripts = List.of(rawScripts.split(",")).stream()
                .map(String::trim).toList();
        if (scripts.isEmpty() || scripts.stream().anyMatch(String::isBlank)) {
            return null;
        }
        final String owner = node.parameters().getOrDefault("TokenOwner", "You");
        if (!Set.of("You", "Opponent").contains(owner)
                || !literalNonnegativeOrAbsent(node, "TokenAmount")
                || !literalNonnegativeOrAbsent(node, "TokenPower")
                || !literalNonnegativeOrAbsent(node, "TokenToughness")) {
            return null;
        }
        return new TokenSpec(scripts, integer(node, "TokenAmount", 1), "You".equals(owner));
    }

    /** Fixed affected players, not the candidates of a single targeted-player decision. */
    private static List<TargetRef> fixedPlayerRecipients(final AbilityOutcomeDescription node) {
        if (node.parameters().containsKey("ValidTgts")) { return null; }
        final Set<TargetRef> recipients = new LinkedHashSet<>();
        for (final String raw : node.parameters().getOrDefault("Defined", "You").split("&", -1)) {
            switch (raw.trim()) {
            case "You" -> recipients.add(TargetRef.CONTROLLER_PLAYER);
            case "Opponent", "Player.Opponent" -> recipients.add(TargetRef.OPPONENT_PLAYER);
            case "Player", "Players" -> {
                recipients.add(TargetRef.CONTROLLER_PLAYER);
                recipients.add(TargetRef.OPPONENT_PLAYER);
            }
            default -> { return null; }
            }
        }
        // TODO: Multiplayer identity, teams, per-player restrictions and simultaneous terminal
        // resolution. The intrinsic reference contains exactly one controller and one opponent.
        return List.copyOf(recipients);
    }

    private static PlayerTarget playerTarget(final AbilityOutcomeDescription node) {
        final String defined = node.parameters().get("Defined");
        final String validTargets = node.parameters().get("ValidTgts");
        if (defined != null && validTargets != null) {
            return null;
        }
        if (defined != null || validTargets == null) {
            final String recipient = defined == null ? "You" : defined;
            return switch (recipient) {
            case "You" -> new PlayerTarget(TargetRef.CONTROLLER_PLAYER, null);
            case "Opponent", "Player.Opponent" -> new PlayerTarget(TargetRef.OPPONENT_PLAYER, null);
            default -> null;
            };
        }
        if (!oneTarget(node) && !("SetLife".equals(node.api()) && "0".equals(node.parameters().get("TargetMin"))
                && "1".equals(node.parameters().getOrDefault("TargetMax", "1")))) {
            return null;
        }
        return switch (validTargets.toLowerCase(Locale.ROOT)) {
        case "player", "players" -> new PlayerTarget(null, true);
        case "you", "player.you" -> new PlayerTarget(TargetRef.CONTROLLER_PLAYER, null);
        case "opponent", "player.opponent" -> new PlayerTarget(TargetRef.OPPONENT_PLAYER, null);
        default -> null;
        };
    }

    private static DamageTarget typedDamageTarget(final String validity) {
        final var filter = IntrinsicPermanentTargetFilter.parse(validity).orElse(null);
        if (filter == null || !Set.of(PermanentKind.CREATURE, PermanentKind.TOKEN, PermanentKind.PLANESWALKER)
                .containsAll(filter.kinds())) { return null; }
        final boolean creature = filter.kinds().contains(PermanentKind.CREATURE);
        final boolean planeswalker = filter.kinds().contains(PermanentKind.PLANESWALKER);
        final DamageTargetScope scope = switch (filter.controller()) {
        case ANY -> creature ? DamageTargetScope.ANY_CREATURE : DamageTargetScope.ANY_PLANESWALKER;
        case FRIENDLY -> creature ? DamageTargetScope.CONTROLLER_CREATURE : DamageTargetScope.CONTROLLER_PLANESWALKER;
        case OPPOSING -> creature ? DamageTargetScope.OPPONENT_CREATURE : DamageTargetScope.OPPONENT_PLANESWALKER;
        };
        return new DamageTarget(scope, null, planeswalker);
    }

    private static List<TargetRef> playerCandidates(final State state, final PlayerTarget target) {
        if (target.any() == null) {
            return target.fixed() == null ? List.of() : List.of(target.fixed());
        }
        return List.of(TargetRef.CONTROLLER_PLAYER, TargetRef.OPPONENT_PLAYER);
    }

    private static DamageTarget damageTarget(final AbilityOutcomeDescription node) {
        final String defined = node.parameters().get("Defined");
        final String validTargets = node.parameters().get("ValidTgts");
        if (defined != null && validTargets != null) {
            return null;
        }
        if (defined != null || validTargets == null) {
            if (defined == null) {
                return null;
            }
            final String recipient = defined;
            return switch (recipient) {
            case "You" -> new DamageTarget(DamageTargetScope.CONTROLLER_PLAYER, TargetRef.CONTROLLER_PLAYER);
            case "Opponent", "Player.Opponent" ->
                    new DamageTarget(DamageTargetScope.OPPONENT_PLAYER, TargetRef.OPPONENT_PLAYER);
            case "TriggeredCard", "TriggeredCardLKICopy" ->
                    new DamageTarget(DamageTargetScope.WATCHED_CREATURE, TargetRef.WATCHED_CREATURE);
            default -> null;
            };
        }
        if (!oneTarget(node)) {
            return null;
        }
        return switch (validTargets.toLowerCase(Locale.ROOT)) {
        case "player", "players" -> new DamageTarget(DamageTargetScope.ANY_PLAYER, null);
        case "any", "anytarget" -> new DamageTarget(DamageTargetScope.ANY_TARGET, null);
        case "you", "player.you" -> new DamageTarget(DamageTargetScope.CONTROLLER_PLAYER,
                TargetRef.CONTROLLER_PLAYER);
        case "opponent", "player.opponent" -> new DamageTarget(DamageTargetScope.OPPONENT_PLAYER,
                TargetRef.OPPONENT_PLAYER);
        case "creature" -> new DamageTarget(DamageTargetScope.ANY_CREATURE, null);
        case "creature.youctrl" -> new DamageTarget(DamageTargetScope.CONTROLLER_CREATURE, null);
        case "creature.oppctrl" -> new DamageTarget(DamageTargetScope.OPPONENT_CREATURE, null);
        default -> typedDamageTarget(validTargets);
        };
    }

    private static List<TargetRef> damageCandidates(final State state, final DamageTarget target) {
        final List<TargetRef> candidates = new java.util.ArrayList<>(switch (target.scope()) {
        case WATCHED_CREATURE -> state.watchedCreature().present() ? List.of(TargetRef.WATCHED_CREATURE) : List.of();
        case CONTROLLER_PLAYER -> List.of(TargetRef.CONTROLLER_PLAYER);
        case OPPONENT_PLAYER -> List.of(TargetRef.OPPONENT_PLAYER);
        case ANY_PLAYER -> List.of(TargetRef.CONTROLLER_PLAYER, TargetRef.OPPONENT_PLAYER);
        case ANY_TARGET -> {
            final List<TargetRef> result = new java.util.ArrayList<>();
            result.add(TargetRef.CONTROLLER_PLAYER);
            result.add(TargetRef.OPPONENT_PLAYER);
            result.addAll(creatureTarget(state, true));
            result.addAll(creatureTarget(state, false));
            yield result;
        }
        case CONTROLLER_CREATURE -> creatureTarget(state, true);
        case OPPONENT_CREATURE -> creatureTarget(state, false);
        case ANY_CREATURE -> {
            final List<TargetRef> result = new java.util.ArrayList<>();
            result.addAll(creatureTarget(state, true));
            result.addAll(creatureTarget(state, false));
            yield result;
        }
        case ANY_PLANESWALKER, CONTROLLER_PLANESWALKER, OPPONENT_PLANESWALKER -> List.of();
        });
        if (target.planeswalkers()) {
            final boolean friendly = target.scope() != DamageTargetScope.OPPONENT_CREATURE
                    && target.scope() != DamageTargetScope.OPPONENT_PLANESWALKER;
            final boolean opposing = target.scope() != DamageTargetScope.CONTROLLER_CREATURE
                    && target.scope() != DamageTargetScope.CONTROLLER_PLANESWALKER;
            if (friendly && state.controllerPermanent().kind() == PermanentKind.PLANESWALKER
                    && canTarget(state.controllerPermanent(), true)) { candidates.add(TargetRef.CONTROLLER_PERMANENT); }
            if (opposing && state.opponentPermanent().kind() == PermanentKind.PLANESWALKER
                    && canTarget(state.opponentPermanent(), false)) { candidates.add(TargetRef.OPPONENT_PERMANENT); }
            final PermanentProfile source = state.sourcePermanent();
            if (source.kind() == PermanentKind.PLANESWALKER && (source.controlledByAi() ? friendly : opposing)
                    && canTarget(source, source.controlledByAi())) { candidates.add(TargetRef.SOURCE); }
        }
        return candidates;
    }

    private static List<TargetRef> creatureTarget(final State state, final boolean controller) {
        final CreatureProfile profile = controller ? state.controllerCreature() : state.opponentCreature();
        if (!canTarget(profile, controller)) {
            return List.of();
        }
        return List.of(controller ? TargetRef.CONTROLLER_CREATURE : TargetRef.OPPONENT_CREATURE);
    }

    private static List<TargetRef> damageAllTargets(final AbilityOutcomeDescription node) {
        return switch (node.parameters().getOrDefault("ValidPlayers", "")) {
        case "You", "Player.You" -> List.of(TargetRef.CONTROLLER_PLAYER);
        case "Opponent", "Player.Opponent" -> List.of(TargetRef.OPPONENT_PLAYER);
        case "Player", "Players" -> List.of(TargetRef.CONTROLLER_PLAYER, TargetRef.OPPONENT_PLAYER);
        default -> List.of();
        };
    }

    private static boolean acceptsToken(final AbilityOutcomeDescription node) {
        if (!TOKEN_PARAMETERS.containsAll(node.parameters().keySet())) {
            return false;
        }
        // TokenAttacking/TokenBlocking are intentionally accepted with ordinary token value.
        // TODO: Adjust their value when an intrinsic combat-state model exists.
          // TODO: Add broader noncreature, copied, targeted, creature-token-ability, ETB, and duration semantics.
        return tokenSpec(node) != null;
    }

    private static boolean acceptsLife(final AbilityOutcomeDescription node) {
        // SetLife uses the same before/after gain/loss utility. TODO: Payment/exchange,
        // redistribution, gain/loss restrictions, replacements and dynamic resolution-time values.
        if (!LIFE_PARAMETERS.containsAll(node.parameters().keySet())
                || !node.parameters().containsKey("LifeAmount") || !literalNonnegativeOrAbsent(node, "LifeAmount")) {
            return false;
        }
        return fixedPlayerRecipients(node) != null || playerTarget(node) != null;
    }

    private boolean acceptsDiscard(final AbilityOutcomeDescription node) {
        // TODO: Add card identity/quality, optional, dynamic, replacement and multiplayer forms.
        if (!DISCARD_PARAMETERS.containsAll(node.parameters().keySet())
                || !Set.of("Random", "TgtChoose", "Hand", "RevealYouChoose")
                        .contains(node.parameters().getOrDefault("Mode", "Random"))) {
            return false;
        }
        if (library.hitProbability(node.parameters().getOrDefault("DiscardValid", "Card")).isEmpty()) { return false; }
        if (!"Hand".equals(node.parameters().get("Mode")) && !literalNonnegativeOrAbsent(node, "NumCards")) {
            return false;
        }
        return fixedPlayerRecipients(node) != null || playerTarget(node) != null;
    }

    private static boolean acceptsMana(final AbilityOutcomeDescription node) {
        // TODO: Add color restrictions, spending/timing opportunity cost, and conditional mana.
        if (!MANA_PARAMETERS.containsAll(node.parameters().keySet())
                || !Set.of("You", "Opponent").contains(node.parameters().getOrDefault("Defined", "You"))
                || node.parameters().getOrDefault("Produced", "").isBlank()
                || !literalNonnegativeOrAbsent(node, "Amount")) {
            return false;
        }
        return true;
    }

    private static boolean acceptsManaReflected(final AbilityOutcomeDescription node) {
        // TODO: Retain the reflected mana color/type and connect the outcome to the triggering
        // land's produced mana instead of using an unrestricted one-mana reference estimate.
        if (!MANA_REFLECTED_PARAMETERS.containsAll(node.parameters().keySet())
                || !Set.of("You", "Opponent").contains(node.parameters().getOrDefault("Defined", "You"))
                || !"Produced".equals(node.parameters().get("ReflectProperty"))
                || !"Type".equals(node.parameters().getOrDefault("ColorOrType", "Type"))) {
            return false;
        }
        return literalNonnegativeOrAbsent(node, "Amount");
    }

    private PermanentProfile damageSource(final AbilityOutcomeDescription node, final State state) {
        if (!"TriggeredCard".equals(node.parameters().get("DamageSource"))) { return state.sourcePermanent(); }
        // Projected entrants use current characteristics. Departed objects retain their last
        // modeled characteristics for damage/LKI; legacy callers may supply only the event snapshot.
        return state.watchedCreature().kind() == PermanentKind.CREATURE
                ? state.watchedCreature() : watchedEventCreature;
    }

    private boolean acceptsDamage(final AbilityOutcomeDescription node) {
        // TODO: Add planeswalkers to broad any-target references without exceeding Cartesian
        // limits, battles/multi-types, combat/prevention/replacements, infect and persistent
        // nonlethal marked damage. Explicit planeswalker type filters are modeled below.
        if (!node.parameters().containsKey("NumDmg") || !literalNonnegativeOrAbsent(node, "NumDmg")
                || !("Self".equals(node.parameters().getOrDefault("DamageSource", "Self"))
                        || watchedEventCreature != null && "TriggeredCard".equals(node.parameters().get("DamageSource")))) {
            return false;
        }
        if ("DamageAll".equals(node.api())) {
            return DAMAGE_ALL_PARAMETERS.containsAll(node.parameters().keySet())
                    && (!damageAllTargets(node).isEmpty() || creatureGroupTarget(node) != null);
        }
        return DAMAGE_PARAMETERS.containsAll(node.parameters().keySet())
                // The intrinsic state does not retain graveyard identities: immediate lethal
                // removal has the same body delta with exile-on-death. TODO: Value suppressed
                // death/graveyard triggers and track the replacement for later damage this turn.
                && (!node.parameters().containsKey("ReplaceDyingDefined")
                        || "Targeted".equals(node.parameters().get("ReplaceDyingDefined")) && node.parameters().containsKey("ValidTgts"))
                && damageTarget(node) != null;
    }

    private static boolean acceptsFight(final AbilityOutcomeDescription node) {
        // The source is the first fighter and one mandatory creature target is the second. Fight
        // does not use first/double strike, but deathtouch and indestructible affect its deaths.
        return FIGHT_PARAMETERS.containsAll(node.parameters().keySet())
                && "Self".equalsIgnoreCase(node.parameters().get("Defined"))
                && oneTarget(node)
                && fightTarget(node) != null;
    }

    private static boolean acceptsRemoval(final AbilityOutcomeDescription node) {
        if (!REMOVAL_PARAMETERS.containsAll(node.parameters().keySet())) {
            return false;
        }
        if ("Destroy".equals(node.api())) {
            return !node.parameters().containsKey("Radiance") && permanentTarget(node) != null;
        }
        return "ChangeZone".equals(node.api())
                && ("Exile".equalsIgnoreCase(node.parameters().get("Destination"))
                        || "Hand".equalsIgnoreCase(node.parameters().get("Destination")))
                && (!node.parameters().containsKey("Origin")
                        || "Battlefield".equalsIgnoreCase(node.parameters().get("Origin")))
                && !node.parameters().containsKey("Duration")
                && !node.parameters().containsKey("ChangeNum")
                && !node.parameters().containsKey("ChangeType")
                && !node.parameters().containsKey("Chooser")
                && !node.parameters().containsKey("DefinedPlayer")
                && !node.parameters().containsKey("GainControl")
                && !node.parameters().containsKey("Tapped")
                && !node.parameters().containsKey("RememberChanged")
                && permanentTarget(node) != null;
    }

    private static boolean acceptsGainControl(final AbilityOutcomeDescription node) {
        // Only a single battlefield target and a persistent, explicitly identified controller are
        // reference-safe. Temporary control, exchanges, untapping and downstream static changes
        // need a longer-lived projected-control model.
        final String duration = node.parameters().get("Duration");
        return CONTROL_PARAMETERS.containsAll(node.parameters().keySet())
                && (duration == null || Set.of("Permanent", "Perpetual").contains(duration))
                && newControllerIsAi(node) != null
                && permanentTarget(node) != null;
    }

    private static boolean acceptsCopyPermanent(final AbilityOutcomeDescription node) {
        // Only copies of the known intrinsic source are reference-safe. Targeted copies, copied
        // cards with characteristic modifiers, temporary copies and downstream copy abilities
        // need a richer public target/profile model. Noncreature copy multiplicity is represented
        // by one generic permanent slot, so later sequence steps must not infer exact counts.
        return COPY_PARAMETERS.containsAll(node.parameters().keySet())
                && "Self".equalsIgnoreCase(node.parameters().get("Defined"))
                && (!node.parameters().containsKey("Controller")
                        || Set.of("You", "Opponent").contains(node.parameters().get("Controller")))
                && literalPositive(node, "NumCopies", 1)
                && integer(node, "NumCopies", 1) <= 16;
    }

    private static boolean acceptsSacrifice(final AbilityOutcomeDescription node) {
        // Player-scoped creature amounts use the homogeneous counted population below.
        // TODO: Noncreature choices, restrictions/replacements and heterogeneous inventories.
        if (!SACRIFICE_PARAMETERS.containsAll(node.parameters().keySet())) {
            return false;
        }
        if (playerSacrifice(node)) {
            return literalNonnegativeOrAbsent(node, "Amount") && integer(node, "Amount", 1) <= 8
                    && "Battlefield".equals(node.parameters().getOrDefault("TgtZone", "Battlefield"))
                    && (fixedPlayerRecipients(node) != null || playerTarget(node) != null);
        }
        return sacrificeTarget(node) != null
                && (!node.parameters().containsKey("Amount")
                        || "1".equals(node.parameters().get("Amount")));
    }

    private static boolean playerSacrifice(final AbilityOutcomeDescription node) {
        return Set.of("Creature", "Creature.Other").contains(node.parameters().getOrDefault("SacValid", ""))
                && (node.parameters().containsKey("Defined") || node.parameters().containsKey("ValidTgts"));
    }

    private static boolean acceptsSacrificeAll(final AbilityOutcomeDescription node) {
        // Only explicitly filtered creature groups are represented. An unfiltered SacrificeAll
        // may include noncreature permanents that the intrinsic reference state does not count.
        return SACRIFICE_ALL_PARAMETERS.containsAll(node.parameters().keySet())
                && creatureGroupTarget(node) != null;
    }

    private static boolean acceptsDestroyAll(final AbilityOutcomeDescription node) {
        // NoRegen is accepted as metadata, but regeneration and shield replacement are not
        // modeled in the reference state; those refinements remain conservative TODOs.
        return DESTROY_ALL_PARAMETERS.containsAll(node.parameters().keySet())
                && creatureGroupTarget(node) != null;
    }

    private static boolean acceptsChangeZoneAll(final AbilityOutcomeDescription node) {
        final String destination = node.parameters().get("Destination");
        return CHANGE_ZONE_ALL_PARAMETERS.containsAll(node.parameters().keySet())
                && "Battlefield".equalsIgnoreCase(node.parameters().get("Origin"))
                && ("Exile".equalsIgnoreCase(destination) || "Hand".equalsIgnoreCase(destination))
                && creatureGroupTarget(node, "ChangeType") != null;
    }

    private static boolean oneTarget(final AbilityOutcomeDescription node) {
        return "1".equals(node.parameters().getOrDefault("TargetMin", "1"))
                && "1".equals(node.parameters().getOrDefault("TargetMax", "1"));
    }

    private static boolean isPlayer(final TargetRef target) {
        return target == TargetRef.CONTROLLER_PLAYER || target == TargetRef.OPPONENT_PLAYER;
    }

    private static boolean isCreatureTarget(final TargetRef target) {
        return target == TargetRef.CONTROLLER_CREATURE || target == TargetRef.OPPONENT_CREATURE || target == TargetRef.WATCHED_CREATURE;
    }

    private static CreatureProfile creature(final State state, final TargetRef target) {
        return switch (target) {
        case WATCHED_CREATURE -> toCreature(state.watchedCreature());
        case CONTROLLER_CREATURE -> state.controllerCreature();
        case OPPONENT_CREATURE -> state.opponentCreature();
        default -> null;
        };
    }

    private static PermanentProfile withControl(final PermanentProfile profile, final boolean controller) {
        return new PermanentProfile(profile.present(), PermanentKind.TOKEN, controller,
                profile.power(), profile.toughness(), profile.keywords(), profile.basicLand(), profile.loyalty());
    }

    private static PermanentProfile withTokenOverrides(final PermanentProfile profile,
            final AbilityOutcomeDescription node) {
        return new PermanentProfile(profile.present(), profile.kind(), profile.controlledByAi(),
                node.parameters().containsKey("TokenPower")
                        ? integer(node, "TokenPower", profile.power()) : profile.power(),
                node.parameters().containsKey("TokenToughness")
                        ? integer(node, "TokenToughness", profile.toughness()) : profile.toughness(),
                profile.keywords(), profile.basicLand(), profile.loyalty());
    }

    private static boolean literalNonnegativeOrAbsent(final AbilityOutcomeDescription node,
            final String name) {
        final String value = node.parameters().get(name);
        if (value == null) {
            return true;
        }
        try {
            return Integer.parseInt(value.trim()) >= 0;
        } catch (final NumberFormatException ignored) {
            return false;
        }
    }

    private Outcome<State> counter(final AbilityOutcomeDescription node) {
        if ("PutCounterAll".equals(node.api())) {
            return counterAll(node);
        }
        if (counterChoice(node)) {
            final List<Outcome<State>> options = counterTypes(node).stream()
                    .map(type -> counter(withCounterType(node, type))).toList();
            return new Outcome.Choice<>(node.path() + ":counter", options, 1, 1, false,
                    maximize(node, false));
        }
        final CounterTarget target = counterTarget(node);
        if (target == null) {
            return unresolved(node, "Unsupported intrinsic counter target");
        }
        if (target.scope() == CounterTargetScope.WATCHED_CREATURE) {
            return watchedInstruction(counterAtomic(node, TargetRef.WATCHED_CREATURE));
        }
        if (target.scope() == CounterTargetScope.SELF) {
            return new Outcome.Deferred<>(state -> {
                if (!state.sourcePermanent().present()) {
                    // A self-death trigger cannot put counters on its departed battlefield
                    // source. This is understood unavailability, not unsupported semantics.
                    return new Outcome.Target<>(node.path() + ":source", current -> List.<TargetRef>of(),
                            State::withTarget, counterAtomic(node, TargetRef.SOURCE), true);
                }
                return hasCounterTarget(state, TargetRef.SOURCE, node)
                        ? counterAtomic(node, TargetRef.SOURCE)
                        : unresolved(node, "Self counter recipient is not a modeled permanent");
            });
        }
        return new Outcome.Deferred<>(state -> {
            // TODO: Protection, ward costs, conditional hexproof and counter restrictions need
            // richer reference semantics. Do not silently drop an unresolved candidate.
            if (hasUnmodeledCandidate(state, target)) {
                return unresolved(node, "Unmodeled reference target characteristics");
            }
            return new Outcome.Target<>(node.path() + ":target", current -> candidates(current, target),
                    State::withTarget, counterAtomic(node, null), true);
        });
    }

    private Outcome<State> counterAll(final AbilityOutcomeDescription node) {
        // TODO: Intrinsic group valuation currently uses one representative creature and an
        // recipient count (shared with bound root quantities when available). Subtypes,
        // noncreature recipients, broader correlated populations,
        // and effects that distribute different counters or amounts still need richer reference
        // modeling.
        final CreatureGroupTarget target = creatureGroupTarget(node);
        if (target == null) {
            return unresolved(node, "Unsupported intrinsic counter group");
        }
        return new Outcome.Atomic<>(node.path(), current -> {
            State projected = current;
            int value = 0;
            if (target.controller()) {
                final GroupApplication application = applyCounterGroup(projected, node, true,
                        target.other());
                if (!application.supported()) {
                    return null;
                }
                projected = application.state();
                value = EffectMath.add(value, application.value());
            }
            if (target.opponent()) {
                final GroupApplication application = applyCounterGroup(projected, node, false,
                        target.other());
                if (!application.supported()) {
                    return null;
                }
                projected = application.state();
                value = EffectMath.add(value, application.value());
            }
            return new Outcome.Transition<>((double) value, projected.clearTarget(), node.api());
        });
    }

    private GroupApplication applyCounterGroup(final State state,
            final AbilityOutcomeDescription node, final boolean controller, final boolean other) {
        final CreatureProfile representative = controller
                ? state.controllerCreature() : state.opponentCreature();
        if (!simpleKeywords(representative.keywords())) {
            return GroupApplication.unsupported(state);
        }

        final int count = state.creatureCount(controller);
        State projected = state;
        int value = 0;
        if (count > 0 && representative.present()) {
            final PermanentProfile before = new PermanentProfile(true, PermanentKind.CREATURE,
                    controller, representative.power(), representative.toughness(),
                    representative.keywords());
            final PermanentProfile after = addCounter(before, node);
            value = EffectMath.add(value, EffectMath.multiply(count,
                    evaluator.evaluateCreatureDelta(toCreature(before), toCreature(after), controller)));
            projected = projected.withCreatures(controller, toCreature(after));
            if ("P1P1".equalsIgnoreCase(counterType(node))) {
                final var recipient = controller ? TargetRef.CONTROLLER_CREATURE : TargetRef.OPPONENT_CREATURE;
                projected = projected.withP1p1(recipient, EffectMath.add(state.p1p1(recipient), integer(node, "CounterNum", 1)));
            }
        }

        final PermanentProfile source = projected.sourcePermanent();
        if (!other && isCreature(source) && source.controlledByAi() == controller) {
            final PermanentProfile after = addCounter(source, node);
            value = EffectMath.add(value, evaluator.evaluateCreatureDelta(
                    toCreature(source), toCreature(after), controller));
            projected = projected.withSourcePermanent(after);
            if ("P1P1".equalsIgnoreCase(counterType(node))) {
                projected = projected.withP1p1(TargetRef.SOURCE, EffectMath.add(state.p1p1(TargetRef.SOURCE), integer(node, "CounterNum", 1)));
            }
        }
        return new GroupApplication(projected, value, true);
    }

    private Outcome<State> pump(final AbilityOutcomeDescription node) {
        if ("PumpAll".equals(node.api())) {
            return pumpAll(node);
        }
        final CounterTarget target = counterTarget(node);
        if (target == null) {
            return unresolved(node, "Unsupported intrinsic pump target");
        }
        if (target.scope() == CounterTargetScope.WATCHED_CREATURE) {
            return watchedInstruction(pumpAtomic(node, TargetRef.WATCHED_CREATURE));
        }
        if (target.scope() == CounterTargetScope.SELF) {
            return new Outcome.Deferred<>(state -> hasCreatureTarget(state, TargetRef.SOURCE)
                    ? pumpAtomic(node, TargetRef.SOURCE)
                    : unresolved(node, "Self pump recipient is not a modeled creature"));
        }
        return new Outcome.Deferred<>(state -> {
            if (hasUnmodeledCandidate(state, target)) {
                return unresolved(node, "Unmodeled reference target characteristics");
            }
            return new Outcome.Target<>(node.path() + ":target", current -> candidates(current, target),
                    State::withTarget, pumpAtomic(node, null), true);
        });
    }

    private Outcome<State> debuff(final AbilityOutcomeDescription node) {
        final CounterTarget target = counterTarget(node);
        if (target == null) {
            return unresolved(node, "Unsupported intrinsic keyword-loss target");
        }
        if (target.scope() == CounterTargetScope.WATCHED_CREATURE) {
            return watchedInstruction(keywordAtomic(node, TargetRef.WATCHED_CREATURE, false));
        }
        if (target.scope() == CounterTargetScope.SELF) {
            return new Outcome.Deferred<>(state -> hasCreatureTarget(state, TargetRef.SOURCE)
                    ? keywordAtomic(node, TargetRef.SOURCE, false)
                    : unresolved(node, "Self keyword-loss recipient is not a modeled creature"));
        }
        return new Outcome.Deferred<>(state -> {
            if (hasUnmodeledCandidate(state, target)) {
                return unresolved(node, "Unmodeled reference target characteristics");
            }
            return new Outcome.Target<>(node.path() + ":target", current -> candidates(current, target),
                    State::withTarget, keywordAtomic(node, null, false), true);
        });
    }

    private Outcome<State> animate(final AbilityOutcomeDescription node) {
        final PermanentTarget target = permanentTarget(node);
        if (target == null) {
            return unresolved(node, "Unsupported intrinsic animation target");
        }
        if (target.fixed() != null) {
            return animateAtomic(node, target.fixed());
        }
        return new Outcome.Deferred<>(state -> new Outcome.Target<>(node.path() + ":target",
                current -> permanentCandidates(current, target), State::withTarget,
                animateAtomic(node, null), true));
    }

    private Outcome<State> animateAtomic(final AbilityOutcomeDescription node,
            final TargetRef fixedTarget) {
        return new Outcome.Atomic<>(node.path(), current -> {
            final TargetRef target = fixedTarget == null ? current.target() : fixedTarget;
            if (target == null) {
                return null;
            }
            final PermanentProfile before = permanent(current, target);
            if (!before.present() || before.kind() == PermanentKind.PLANESWALKER
                    || !simpleKeywords(before.keywords())) {
                return null;
            }
            final PermanentProfile after = animatePermanent(before, node);
            final int value = evaluator.evaluatePermanentDelta(before, after,
                    controls(target, current));
            return new Outcome.Transition<>((double) value,
                    replacePermanent(current, target, after).clearTarget(), node.api());
        });
    }

    private Outcome<State> animateAll(final AbilityOutcomeDescription node) {
        // TODO: Intrinsic group animation currently models only one representative creature per
        // side. Noncreature permanents, subtype/color retention, and correlated populations need
        // richer reference state before they can be admitted safely.
        final CreatureGroupTarget target = creatureGroupTarget(node);
        if (target == null) {
            return unresolved(node, "Unsupported intrinsic animation group");
        }
        return new Outcome.Atomic<>(node.path(), current -> {
            State projected = current;
            int value = 0;
            if (target.controller()) {
                final GroupApplication application = applyAnimationGroup(projected, node, true,
                        target.other());
                if (!application.supported()) {
                    return null;
                }
                projected = application.state();
                value = EffectMath.add(value, application.value());
            }
            if (target.opponent()) {
                final GroupApplication application = applyAnimationGroup(projected, node, false,
                        target.other());
                if (!application.supported()) {
                    return null;
                }
                projected = application.state();
                value = EffectMath.add(value, application.value());
            }
            return new Outcome.Transition<>((double) value, projected.clearTarget(), node.api());
        });
    }

    private GroupApplication applyAnimationGroup(final State state,
            final AbilityOutcomeDescription node, final boolean controller, final boolean other) {
        final CreatureProfile representative = controller
                ? state.controllerCreature() : state.opponentCreature();
        if (!simpleKeywords(representative.keywords())) {
            return GroupApplication.unsupported(state);
        }

        final int count = state.creatureCount(controller);
        State projected = state;
        int value = 0;
        if (count > 0 && representative.present()) {
            final PermanentProfile before = new PermanentProfile(true, PermanentKind.CREATURE,
                    controller, representative.power(), representative.toughness(),
                    representative.keywords());
            final PermanentProfile after = animatePermanent(before, node);
            value = EffectMath.add(value, EffectMath.multiply(count,
                    evaluator.evaluateCreatureDelta(toCreature(before), toCreature(after), controller)));
            projected = projected.withCreatures(controller, toCreature(after));
        }

        final PermanentProfile source = projected.sourcePermanent();
        if (!other && isCreature(source) && source.controlledByAi() == controller) {
            final PermanentProfile after = animatePermanent(source, node);
            value = EffectMath.add(value, evaluator.evaluateCreatureDelta(
                    toCreature(source), toCreature(after), controller));
            projected = projected.withSourcePermanent(after);
        }
        return new GroupApplication(projected, value, true);
    }

    private Outcome<State> pumpAll(final AbilityOutcomeDescription node) {
        // TODO: Intrinsic group valuation currently uses one representative creature and an
        // independent recipient count. Subtypes, noncreature recipients, correlated populations,
        // temporary durations, lethal changes, and distributed amounts still need richer
        // reference modeling.
        final CreatureGroupTarget target = creatureGroupTarget(node);
        if (target == null) {
            return unresolved(node, "Unsupported intrinsic pump group");
        }
        return new Outcome.Atomic<>(node.path(), current -> {
            State projected = current;
            int value = 0;
            if (target.controller()) {
                final GroupApplication application = applyPumpGroup(projected, node, true,
                        target.other());
                if (!application.supported()) {
                    return null;
                }
                projected = application.state();
                value = EffectMath.add(value, application.value());
            }
            if (target.opponent()) {
                final GroupApplication application = applyPumpGroup(projected, node, false,
                        target.other());
                if (!application.supported()) {
                    return null;
                }
                projected = application.state();
                value = EffectMath.add(value, application.value());
            }
            return new Outcome.Transition<>((double) value, projected.clearTarget(), node.api());
        });
    }

    private GroupApplication applyPumpGroup(final State state,
            final AbilityOutcomeDescription node, final boolean controller, final boolean other) {
        final CreatureProfile representative = controller
                ? state.controllerCreature() : state.opponentCreature();
        if (!simpleKeywords(representative.keywords())) {
            return GroupApplication.unsupported(state);
        }

        final int count = state.creatureCount(controller);
        State projected = state;
        int value = 0;
        if (count > 0 && representative.present()) {
            final PermanentProfile before = new PermanentProfile(true, PermanentKind.CREATURE,
                    controller, representative.power(), representative.toughness(),
                    representative.keywords());
            final PermanentProfile after = pumpPermanent(before, node);
            if (after.toughness() <= 0) {
                return GroupApplication.unsupported(state);
            }
            value = EffectMath.add(value, EffectMath.multiply(count,
                    evaluator.evaluateCreatureDelta(toCreature(before), toCreature(after), controller)));
            projected = projected.withCreatures(controller, toCreature(after));
        }

        final PermanentProfile source = projected.sourcePermanent();
        if (!other && isCreature(source) && source.controlledByAi() == controller) {
            final PermanentProfile after = pumpPermanent(source, node);
            if (after.toughness() <= 0) {
                return GroupApplication.unsupported(state);
            }
            value = EffectMath.add(value, evaluator.evaluateCreatureDelta(
                    toCreature(source), toCreature(after), controller));
            projected = projected.withSourcePermanent(after);
        }
        return new GroupApplication(projected, value, true);
    }

    private Outcome<State> pumpAtomic(final AbilityOutcomeDescription node,
            final TargetRef fixedTarget) {
        return new Outcome.Atomic<>(node.path(), current -> {
            final TargetRef target = fixedTarget == null ? current.target() : fixedTarget;
            if (target == null || !hasCreatureTarget(current, target)) {
                return null;
            }
            final PermanentProfile before = permanent(current, target);
            if (target != TargetRef.SOURCE && !simpleKeywords(before.keywords())) {
                return null;
            }
            final PermanentProfile after = pumpPermanent(before, node);
            if (after.toughness() <= 0) {
                return null;
            }
            final int value = evaluator.evaluateCreatureDelta(toCreature(before), toCreature(after),
                    controls(target, current));
            return new Outcome.Transition<>((double) value,
                    replacePermanent(current, target, after).clearTarget(), node.api());
        });
    }

    private Outcome<State> keywordAtomic(final AbilityOutcomeDescription node,
            final TargetRef fixedTarget, final boolean add) {
        return new Outcome.Atomic<>(node.path(), current -> {
            final TargetRef target = fixedTarget == null ? current.target() : fixedTarget;
            if (target == null || !hasCreatureTarget(current, target)) {
                return null;
            }
            final PermanentProfile before = permanent(current, target);
            if (target != TargetRef.SOURCE && !simpleKeywords(before.keywords())) {
                return null;
            }
            final PermanentProfile after = keywordPermanent(before, node, add);
            final int value = evaluator.evaluateCreatureDelta(toCreature(before), toCreature(after),
                    controls(target, current));
            return new Outcome.Transition<>((double) value,
                    replacePermanent(current, target, after).clearTarget(), node.api());
        });
    }

    private Outcome<State> counterAtomic(final AbilityOutcomeDescription node,
            final TargetRef fixedTarget) {
        return new Outcome.Atomic<>(node.path(), current -> {
            final TargetRef target = fixedTarget == null ? current.target() : fixedTarget;
            if (target == null || !hasCounterTarget(current, target, node)) {
                return null;
            }
            final PermanentProfile before = permanent(current, target);
            // A fixed self recipient has the source profile, so unmodeled abilities on the card
            // do not prevent evaluating this independent P/T delta. Generic reference targets
            // remain conservative because their unmodeled keywords may affect target selection or
            // the meaning of the counter outcome.
            if (target != TargetRef.SOURCE && !simpleKeywords(before.keywords())) { return null; }
            final String counterKeyword = counterKeyword(counterType(node));
            if (counterKeyword != null && hasKeyword(before, counterKeyword)) {
                if (target == TargetRef.WATCHED_CREATURE) {
                    return new Outcome.Transition<>(0, current.clearTarget());
                }
                return null;
            }
            final PermanentProfile after = addCounter(before, node);
            // TODO: State-based deaths occur after resolution, not between counter/pump steps.
            // Do not erase an object which a later instruction could save in this sequence.
            if (target == TargetRef.WATCHED_CREATURE && after.toughness() <= 0) { return null; }
            final int value = "LOYALTY".equalsIgnoreCase(counterType(node))
                    ? evaluator.evaluatePermanentDelta(before, after, controls(target, current))
                    : evaluator.evaluateCreatureDelta(toCreature(before), toCreature(after),
                            controls(target, current));
            State projected = replacePermanent(current, target, after).clearTarget();
            if ("P1P1".equalsIgnoreCase(counterType(node))) {
                projected = projected.withP1p1(target, EffectMath.add(current.p1p1(target), integer(node, "CounterNum", 1)));
            }
            return new Outcome.Transition<>((double) value, projected, node.api());
        });
    }

    private static boolean acceptsMultiplyCounter(final AbilityOutcomeDescription node) {
        // TODO: Other counter inventories, players, targeted/filtered recipients, replacement
        // effects, heterogeneous groups and token/control/animation identity changes.
        return MULTIPLY_COUNTER_PARAMETERS.containsAll(node.parameters().keySet())
                && "P1P1".equals(node.parameters().get("CounterType"))
                && literalNonnegativeOrAbsent(node, "Multiplier") && integer(node, "Multiplier", 2) >= 1
                && integer(node, "Multiplier", 2) <= 16
                && (Set.of("Self", "Card.Self", "Creature.Self").contains(node.parameters().getOrDefault("Defined", ""))
                    || watchedRecipient(node.parameters().get("Defined"))
                    || multiplyCounterGroup(node) != null);
    }

    private static CreatureGroupTarget multiplyCounterGroup(final AbilityOutcomeDescription node) {
        final String defined = node.parameters().getOrDefault("Defined", "");
        if (!defined.startsWith("Valid ")) { return null; }
        return creatureGroupTarget(new AbilityOutcomeDescription(node.path(), node.api(),
                java.util.Map.of("ValidCards", defined.substring(6)), List.of(), null, ""));
    }

    private Outcome<State> multiplyCounter(final AbilityOutcomeDescription node) {
        final CreatureGroupTarget group = multiplyCounterGroup(node);
        return new Outcome.Atomic<>(node.path(), current -> {
            State projected = current;
            int value = 0;
            final List<TargetRef> recipients = new java.util.ArrayList<>();
            if (group == null) {
                recipients.add(watchedRecipient(node.parameters().get("Defined")) ? TargetRef.WATCHED_CREATURE : TargetRef.SOURCE);
            }
            else {
                if (group.controller()) { recipients.add(TargetRef.CONTROLLER_CREATURE); }
                if (group.opponent()) { recipients.add(TargetRef.OPPONENT_CREATURE); }
                if (!group.other() && isCreature(current.sourcePermanent())
                        && (current.sourcePermanent().controlledByAi() ? group.controller() : group.opponent())) {
                    recipients.add(TargetRef.SOURCE);
                }
            }
            for (final var recipient : recipients) {
                final var before = permanent(current, recipient);
                if (!before.present()) { continue; }
                // TODO: A watched creature's initial counter inventory is not inferred from P/T.
                // Only an explicitly initialized or previously projected inventory is understood.
                if (recipient == TargetRef.WATCHED_CREATURE && !current.p1p1Counters().containsKey(recipient)) { return null; }
                if (!isCreature(before) || recipient != TargetRef.SOURCE && recipient != TargetRef.WATCHED_CREATURE
                        && !simpleKeywords(before.keywords())) { return null; }
                final int added = EffectMath.multiply(current.p1p1(recipient), integer(node, "Multiplier", 2) - 1);
                final var put = new AbilityOutcomeDescription(node.path(), "PutCounter",
                        java.util.Map.of("CounterType", "P1P1", "CounterNum", Integer.toString(added)), List.of(), null, "");
                final var after = addCounter(before, put);
                final int count = recipient == TargetRef.SOURCE || recipient == TargetRef.WATCHED_CREATURE
                        ? 1 : current.creatureCount(controls(recipient, current));
                value = EffectMath.add(value, EffectMath.multiply(count,
                        evaluator.evaluateCreatureDelta(toCreature(before), toCreature(after), controls(recipient, current))));
                projected = replacePermanent(projected, recipient, after).withP1p1(recipient,
                        EffectMath.add(current.p1p1(recipient), added));
            }
            return new Outcome.Transition<>((double) value, projected.clearTarget(), node.api());
        });
    }

    static State initializeP1p1(final State state, final TargetRef recipient, final int count) {
        return initializeP1p1(state, recipient, count, 0);
    }

    static State initializeP1p1(final State state, final TargetRef recipient, final int count, final int includedInProfile) {
        // Unconditional literal starting counters may already be in the source profile.
        // TODO: Variable/conditional ETB inventories, replacements and counter-dependent CDAs.
        final var before = permanent(state, recipient);
        if (!isCreature(before) || count == 0) { return state.withP1p1(recipient, 0); }
        final var put = new AbilityOutcomeDescription("reference:counters", "PutCounter",
                java.util.Map.of("CounterType", "P1P1", "CounterNum", Integer.toString(Math.max(0, count - includedInProfile))), List.of(), null, "");
        return replacePermanent(state, recipient, addCounter(before, put)).withP1p1(recipient, count);
    }

    private Outcome<State> removeCounter(final AbilityOutcomeDescription node) {
        final CounterTarget target = counterTarget(node);
        if (target == null) {
            return unresolved(node, "Unsupported intrinsic counter-removal target");
        }
        if (target.scope() == CounterTargetScope.SELF) {
            return removeCounterAtomic(node, TargetRef.SOURCE);
        }
        if (target.scope() == CounterTargetScope.WATCHED_CREATURE) {
            return watchedInstruction(removeCounterAtomic(node, TargetRef.WATCHED_CREATURE));
        }
        return new Outcome.Deferred<>(state -> new Outcome.Target<>(node.path() + ":target",
                current -> candidates(current, target), State::withTarget,
                removeCounterAtomic(node, null), true));
    }

    private Outcome<State> removeCounterAtomic(final AbilityOutcomeDescription node,
            final TargetRef fixedTarget) {
        return new Outcome.Atomic<>(node.path(), current -> {
            final TargetRef target = fixedTarget == null ? current.target() : fixedTarget;
            if (target == null || !hasCounterTarget(current, target, node)) {
                return null;
            }
            final PermanentProfile before = permanent(current, target);
            if (target != TargetRef.SOURCE && target != TargetRef.WATCHED_CREATURE && !simpleKeywords(before.keywords())) {
                return null;
            }
            final String counterKeyword = counterKeyword(counterType(node));
            if (counterKeyword != null && !hasKeyword(before, counterKeyword)) {
                return null;
            }

            // P1P1 uses projected inventory. Other types retain the existing one-counter
            // assumption until their own inventory/replacement/choice semantics are modeled.
            final var removal = clampedCounterRemoval(node, current, target);
            if (removal == null) { return null; }
            final PermanentProfile after = removeCounterPermanent(before, removal);
            if (after == null) {
                return null;
            }
            // TODO: End-of-resolution state-based actions and effects that subsequently restore
            // toughness. Do not remove the watched object prematurely between instructions.
            if (target == TargetRef.WATCHED_CREATURE && (!after.present() || after.toughness() <= 0)) { return null; }
            final int value = "LOYALTY".equalsIgnoreCase(counterType(node))
                    ? evaluator.evaluatePermanentDelta(before, after, controls(target, current))
                    : evaluator.evaluateCreatureDelta(toCreature(before), toCreature(after),
                            controls(target, current));
            State projected = replacePermanent(current, target, after).clearTarget();
            if ("P1P1".equals(counterType(node))) {
                projected = projected.withP1p1(target, Math.max(0, current.p1p1(target) - integer(removal, "CounterNum", 1)));
            }
            return new Outcome.Transition<>((double) value, projected, node.api());
        });
    }

    private Outcome<State> removeCounterAll(final AbilityOutcomeDescription node) {
        // Group counter state is represented by one profile and count per side. This deliberately
        // does not infer counters on absent profiles or invent a planeswalker population.
        final CreatureGroupTarget target = creatureGroupTarget(node);
        if (target == null) {
            return unresolved(node, "Unsupported intrinsic counter-removal group");
        }
        return new Outcome.Atomic<>(node.path(), current -> {
            State projected = current;
            int value = 0;
            if (target.controller()) {
                final GroupApplication application = applyRemoveCounterGroup(projected, node, true,
                        target.other());
                if (!application.supported()) {
                    return null;
                }
                projected = application.state();
                value = EffectMath.add(value, application.value());
            }
            if (target.opponent()) {
                final GroupApplication application = applyRemoveCounterGroup(projected, node, false,
                        target.other());
                if (!application.supported()) {
                    return null;
                }
                projected = application.state();
                value = EffectMath.add(value, application.value());
            }
            return new Outcome.Transition<>((double) value, projected.clearTarget(), node.api());
        });
    }

    private GroupApplication applyRemoveCounterGroup(final State state,
            final AbilityOutcomeDescription node, final boolean controller, final boolean other) {
        final CreatureProfile representative = controller
                ? state.controllerCreature() : state.opponentCreature();
        if (!simpleKeywords(representative.keywords())) {
            return GroupApplication.unsupported(state);
        }

        State projected = state;
        int value = 0;
        final int count = state.creatureCount(controller);
        if (count > 0 && representative.present()) {
            final PermanentProfile before = new PermanentProfile(true, PermanentKind.CREATURE,
                    controller, representative.power(), representative.toughness(),
                    representative.keywords());
            final TargetRef recipient = controller ? TargetRef.CONTROLLER_CREATURE : TargetRef.OPPONENT_CREATURE;
            final var removal = clampedCounterRemoval(node, state, recipient);
            if (removal == null) { return GroupApplication.unsupported(state); }
            final PermanentProfile after = removeCounterPermanent(before, removal);
            if (after == null) {
                return GroupApplication.unsupported(state);
            }
            value = EffectMath.multiply(count, evaluator.evaluateCreatureDelta(
                    representative, toCreature(after), controller));
            projected = projected.withCreatures(controller, toCreature(after));
            if ("P1P1".equals(counterType(node))) {
                projected = projected.withP1p1(recipient, Math.max(0, state.p1p1(recipient) - integer(removal, "CounterNum", 1)));
            }
            if (!after.present()) {
                projected = projected.withCreatureCount(controller, 0);
            }
        }

        final PermanentProfile source = projected.sourcePermanent();
        if (!other && isCreature(source) && source.controlledByAi() == controller) {
            final var removal = clampedCounterRemoval(node, state, TargetRef.SOURCE);
            if (removal == null) { return GroupApplication.unsupported(state); }
            final PermanentProfile after = removeCounterPermanent(source, removal);
            if (after == null) {
                return GroupApplication.unsupported(state);
            }
            value = EffectMath.add(value, evaluator.evaluateCreatureDelta(
                    toCreature(source), toCreature(after), controller));
            projected = projected.withSourcePermanent(after);
            if ("P1P1".equals(counterType(node))) {
                projected = projected.withP1p1(TargetRef.SOURCE, Math.max(0, state.p1p1(TargetRef.SOURCE) - integer(removal, "CounterNum", 1)));
            }
        }
        return new GroupApplication(projected, value, true);
    }

    private static boolean acceptsDraw(final AbilityOutcomeDescription node) {
        // TODO: Library exhaustion, optional draws, replacements, targeted players and symbolic
        // amounts require explicit reference state. Unknown semantic fields fail closed here.
        if (!DRAW_PARAMETERS.containsAll(node.parameters().keySet())) {
            return false;
        }
        return DrawOutcomeDescription.parseFixedRecipients(node.api(), node.parameters())
                .filter(draws -> draws.stream().allMatch(draw -> draw.amount() >= 0)).isPresent();
    }

    private static AbilityOutcomeDescription clampedCounterRemoval(final AbilityOutcomeDescription node,
            final State state, final TargetRef recipient) {
        if (!"P1P1".equals(counterType(node))) { return node; }
        if (!state.p1p1Counters().containsKey(recipient)) {
            if (recipient == TargetRef.WATCHED_CREATURE) { return null; }
            // Compatibility for directly constructed legacy reference states. Definition
            // evaluation supplies inventory dimensions; larger/All removals cannot guess it.
            return "1".equals(node.parameters().getOrDefault("CounterNum", "1")) ? node : null;
        }
        final int available = state.p1p1(recipient);
        final int removed = "All".equals(node.parameters().get("CounterNum")) ? available
                : Math.min(available, integer(node, "CounterNum", 1));
        final var parameters = new java.util.LinkedHashMap<>(node.parameters());
        parameters.put("CounterNum", Integer.toString(removed));
        return new AbilityOutcomeDescription(node.path(), node.api(), parameters, node.choices(), node.next(), node.issue());
    }

    private static boolean acceptsCounter(final AbilityOutcomeDescription node) {
        // TODO: Other counters, divided/optional targets, repeated shield/stun
        // or keyword-counter scaling, counter replacement effects and shared Targeted references
        // need dedicated descriptors and projected state. Comma-separated counter choices also
        // need to be represented as explicit outcome choices before intrinsic evaluation can use
        // them.
        if (!COUNTER_PARAMETERS.containsAll(node.parameters().keySet())
                || node.parameters().containsKey("ValidCards")
                || !supportedCounterType(counterType(node))) {
            return false;
        }
        final CounterTarget target = counterTarget(node);
        return target != null && literalPositive(node, "CounterNum", 1)
                && (!"LOYALTY".equalsIgnoreCase(counterType(node))
                        || target.scope() == CounterTargetScope.SELF)
                && "1".equals(node.parameters().getOrDefault("TargetMin", "1"))
                && "1".equals(node.parameters().getOrDefault("TargetMax", "1"))
                && "Battlefield".equals(node.parameters().getOrDefault("TgtZone", "Battlefield"));
    }

    private static boolean acceptsRemoveCounter(final AbilityOutcomeDescription node) {
        // P1P1 can remove a literal amount or All using projected inventory. Other counter
        // types retain the single-counter approximation; arbitrary inventories remain TODO.
        if (!COUNTER_PARAMETERS.containsAll(node.parameters().keySet())
                || node.parameters().containsKey("ValidCards")
                || !supportedCounterType(counterType(node))
                || !("P1P1".equals(counterType(node))
                        ? "All".equals(node.parameters().get("CounterNum")) || literalPositive(node, "CounterNum", 1)
                        : "1".equals(node.parameters().getOrDefault("CounterNum", "1")))) {
            return false;
        }
        final CounterTarget target = counterTarget(node);
        return target != null
                && (!"LOYALTY".equalsIgnoreCase(counterType(node))
                        || target.scope() == CounterTargetScope.SELF)
                && "1".equals(node.parameters().getOrDefault("TargetMin", "1"))
                && "1".equals(node.parameters().getOrDefault("TargetMax", "1"))
                && "Battlefield".equals(node.parameters().getOrDefault("TgtZone", "Battlefield"));
    }

    private static boolean acceptsRemoveCounterAll(final AbilityOutcomeDescription node) {
        // Literal P1P1 amounts use representative inventory. TODO: AllCounters/AllCounterTypes,
        // other inventories, heterogeneous groups, planeswalkers and non-battlefield recipients.
        return REMOVE_COUNTER_ALL_PARAMETERS.containsAll(node.parameters().keySet())
                && !"LOYALTY".equalsIgnoreCase(counterType(node))
                && supportedCounterType(counterType(node))
                && ("P1P1".equals(counterType(node)) ? literalPositive(node, "CounterNum", 1)
                        : "1".equals(node.parameters().getOrDefault("CounterNum", "1")))
                && (!node.parameters().containsKey("ValidZone")
                        || "Battlefield".equalsIgnoreCase(node.parameters().get("ValidZone")))
                && creatureGroupTarget(node) != null;
    }

    private static boolean acceptsCounterAll(final AbilityOutcomeDescription node) {
        return COUNTER_PARAMETERS.containsAll(node.parameters().keySet())
                && !node.parameters().containsKey("Defined")
                && !node.parameters().containsKey("ValidTgts")
                && supportedCounterType(counterType(node))
                && literalPositive(node, "CounterNum", 1)
                && creatureGroupTarget(node) != null;
    }

    private static boolean acceptsPump(final AbilityOutcomeDescription node) {
        return acceptsPumpParameters(node)
                && !node.parameters().containsKey("ValidCards")
                && counterTarget(node) != null;
    }

    private static boolean acceptsDebuff(final AbilityOutcomeDescription node) {
        // Only explicit persistent removal of a supported keyword is reference-safe. Temporary,
        // dynamic, hidden, and conditional keyword loss needs a richer projected-characteristic model.
        final Set<String> keywords = supportedKeywords(node.parameters().get("Keywords"));
        return DEBUFF_PARAMETERS.containsAll(node.parameters().keySet())
                && persistentDuration(node.parameters().get("Duration"))
                && !node.parameters().containsKey("AllSuffixKeywords")
                && keywords != null && !keywords.isEmpty() && counterTarget(node) != null;
    }

    private static boolean acceptsAnimate(final AbilityOutcomeDescription node) {
        // The reference profile can model a persistent creature conversion, but not temporary
        // animation, subtype/color changes, or a planeswalker that remains a planeswalker.
        final Set<String> keywords = supportedKeywords(node.parameters().get("Keywords"));
        final String types = node.parameters().get("Types");
        return ANIMATE_PARAMETERS.containsAll(node.parameters().keySet())
                && persistentDuration(node.parameters().get("Duration"))
                && node.parameters().containsKey("Power") && node.parameters().containsKey("Toughness")
                && literalNonnegativeOrAbsent(node, "Power")
                && literalNonnegativeOrAbsent(node, "Toughness")
                && types != null && hasCreatureType(types)
                && keywords != null && permanentTarget(node) != null;
    }

    private static boolean acceptsAnimateAll(final AbilityOutcomeDescription node) {
        final Set<String> keywords = supportedKeywords(node.parameters().get("Keywords"));
        final String types = node.parameters().get("Types");
        return ANIMATE_ALL_PARAMETERS.containsAll(node.parameters().keySet())
                && persistentDuration(node.parameters().get("Duration"))
                && node.parameters().containsKey("Power") && node.parameters().containsKey("Toughness")
                && literalNonnegativeOrAbsent(node, "Power")
                && literalNonnegativeOrAbsent(node, "Toughness")
                && types != null && hasCreatureType(types)
                && keywords != null && creatureGroupTarget(node) != null;
    }

    private static boolean acceptsPumpAll(final AbilityOutcomeDescription node) {
        return acceptsPumpParameters(node)
                && !node.parameters().containsKey("Defined")
                && !node.parameters().containsKey("ValidTgts")
                && creatureGroupTarget(node) != null;
    }

    private static boolean acceptsPumpParameters(final AbilityOutcomeDescription node) {
        if (!PUMP_PARAMETERS.containsAll(node.parameters().keySet())
                || !persistentDuration(node.parameters().get("Duration"))) {
            return false;
        }
        final boolean hasPowerChange = node.parameters().containsKey("NumAtt");
        final boolean hasToughnessChange = node.parameters().containsKey("NumDef");
        final Set<String> keywords = supportedKeywords(node.parameters().get("KW"));
        return keywords != null && (hasPowerChange || hasToughnessChange || !keywords.isEmpty())
                && literalSigned(node, "NumAtt") && literalSigned(node, "NumDef");
    }

    private static Set<String> supportedKeywords(final String value) {
        return IntrinsicStaticAbilityEvaluator.parseSupportedKeywords(value);
    }

    private static boolean persistentDuration(final String value) {
        return "Permanent".equals(value) || "Perpetual".equals(value);
    }

    private static boolean hasCreatureType(final String types) {
        return List.of(types.split(",")).stream()
                .map(type -> type.trim()).anyMatch("Creature"::equalsIgnoreCase);
    }

    private static CreatureGroupTarget creatureGroupTarget(final AbilityOutcomeDescription node) {
        return creatureGroupTarget(node, "ValidCards");
    }

    private static CreatureGroupTarget creatureGroupTarget(final AbilityOutcomeDescription node,
            final String parameter) {
        final String definition = node.parameters().get(parameter);
        if (definition == null || definition.isBlank() || definition.contains(",")) {
            return null;
        }
        final String[] parts = definition.toLowerCase(Locale.ROOT).split("[+.]");
        boolean creature = false;
        boolean youControl = false;
        boolean opponentControl = false;
        boolean other = false;
        for (final String part : parts) {
            switch (part) {
            case "creature" -> creature = true;
            case "youctrl" -> youControl = true;
            case "oppctrl" -> opponentControl = true;
            case "other", "strictlyother" -> other = true;
            default -> {
                return null;
            }
            }
        }
        if (!creature || youControl && opponentControl) {
            return null;
        }
        return new CreatureGroupTarget(!opponentControl, !youControl, other);
    }

    private static boolean acceptsCounterChoice(final AbilityOutcomeDescription node) {
        return counterChoice(node) && counterTypes(node).stream()
                .map(type -> withCounterType(node, type)).allMatch(IntrinsicDrawOutcomeBackend::acceptsCounter);
    }

    private static boolean counterChoice(final AbilityOutcomeDescription node) {
        return node.parameters().getOrDefault("CounterType", "").contains(",");
    }

    private static List<String> counterTypes(final AbilityOutcomeDescription node) {
        return List.of(node.parameters().getOrDefault("CounterType", "").split(",")).stream()
                .map(String::trim).filter(type -> !type.isEmpty()).toList();
    }

    private static AbilityOutcomeDescription withCounterType(final AbilityOutcomeDescription node,
            final String type) {
        final java.util.Map<String, String> parameters = new java.util.LinkedHashMap<>(node.parameters());
        parameters.put("CounterType", type);
        return new AbilityOutcomeDescription(node.path(), node.api(), parameters, node.choices(), null,
                node.issue());
    }

    private void collectDimensions(final AbilityOutcomeDescription original,
            final Set<String> dimensions, final Set<AbilityOutcomeDescription> visited,
            final int depth) {
        if (original == null) { return; }
        if (depth > MAX_DIMENSION_DEPTH || visited.size() >= 1024) {
            throw new IllegalArgumentException("Intrinsic reference traversal limit exceeded");
        }
        if (!visited.add(original)) { return; }
        final var condition = IntrinsicOutcomeConditions.describe(original);
        dimensions.addAll(condition.dimensions());
        final AbilityOutcomeDescription node = condition.node();
        final AbilityOutcomeDescription tokenNode = "Investigate".equals(node.api()) ? investigateToken(node) : node;
        if (tokenNode != null && "Token".equals(tokenNode.api()) && acceptsToken(tokenNode)
                && tokenProfileResolver instanceof IntrinsicTokenResolver resolver) {
            final TokenSpec spec = tokenSpec(tokenNode);
            if (spec.amount() > 0) {
                final var resourceValues = spec.scripts().stream().map(resolver::resolveToken)
                        .flatMap(Optional::stream).map(IntrinsicTokenResolver.Definition::resourceValue)
                        .filter(java.util.Objects::nonNull).toList();
                if (resourceValues.stream().anyMatch(IntrinsicTokenResolver.ResourceValue::usesHandSize)) {
                    dimensions.add(spec.recipientIsController() ? CONTROLLER_HAND : OPPONENT_HAND);
                }
                if (resourceValues.stream().anyMatch(IntrinsicTokenResolver.ResourceValue::usesLifeTotal)) {
                    dimensions.add(spec.recipientIsController() ? CONTROLLER_LIFE : OPPONENT_LIFE);
                }
            }
        } else if ("Draw".equals(node.api()) && acceptsDraw(node)) {
            for (final var draw : DrawOutcomeDescription.parseFixedRecipients(node.api(), node.parameters()).orElseThrow()) {
                dimensions.add(draw.controller() ? CONTROLLER_HAND : OPPONENT_HAND);
            }
        } else if ("ChangeZone".equals(node.api()) && IntrinsicLibrarySearchOutcome.parse(node, library).isPresent()) {
            final var search = IntrinsicLibrarySearchOutcome.parse(node, library).orElseThrow();
            dimensions.add(search.controller() ? CONTROLLER_HAND : OPPONENT_HAND);
        } else if ("Dig".equals(node.api()) && acceptsDig(node)
                || Set.of("Scry", "Surveil").contains(node.api()) && acceptsFiltering(node)) {
            final String defined = node.parameters().getOrDefault("Defined", "You");
            if ("You".equalsIgnoreCase(defined)) {
                dimensions.add(CONTROLLER_HAND);
            } else if ("Opponent".equalsIgnoreCase(defined)) {
                dimensions.add(OPPONENT_HAND);
            }
        } else if ("MultiplyCounter".equals(node.api()) && acceptsMultiplyCounter(node)) {
            final var group = multiplyCounterGroup(node);
            if (group == null) {
                if (!watchedRecipient(node.parameters().get("Defined"))) { dimensions.add(SOURCE_P1P1); }
            }
            else {
                addCreatureGroupDimensions(group, dimensions);
                if (group.controller()) { dimensions.add(CONTROLLER_P1P1); }
                if (group.opponent()) { dimensions.add(OPPONENT_P1P1); }
                if (!group.other()) { dimensions.add(SOURCE_P1P1); }
            }
        } else if ("PutCounter".equals(node.api())) {
            final List<AbilityOutcomeDescription> counterNodes = acceptsCounter(node)
                    ? List.of(node) : acceptsCounterChoice(node) ? counterTypes(node).stream()
                            .map(type -> withCounterType(node, type)).toList() : List.of();
            addCounterDimensions(counterNodes, dimensions);
        } else if ("RemoveCounter".equals(node.api()) && acceptsRemoveCounter(node)) {
            addCounterDimensions(List.of(node), dimensions);
            if ("P1P1".equals(counterType(node))) {
                final var target = counterTarget(node);
                if (!target.other() && target.scope() != CounterTargetScope.WATCHED_CREATURE) { dimensions.add(SOURCE_P1P1); }
                if (target.scope() == CounterTargetScope.ANY_CREATURE || target.scope() == CounterTargetScope.CONTROLLER_CREATURE) {
                    dimensions.add(CONTROLLER_P1P1);
                }
                if (target.scope() == CounterTargetScope.ANY_CREATURE || target.scope() == CounterTargetScope.OPPONENT_CREATURE) {
                    dimensions.add(OPPONENT_P1P1);
                }
            }
        } else if (("PutCounterAll".equals(node.api()) && acceptsCounterAll(node))
                || ("RemoveCounterAll".equals(node.api()) && acceptsRemoveCounterAll(node))
                || ("PumpAll".equals(node.api()) && acceptsPumpAll(node))) {
            addCreatureGroupDimensions(creatureGroupTarget(node), dimensions);
            if ("RemoveCounterAll".equals(node.api()) && "P1P1".equals(counterType(node))) {
                final var group = creatureGroupTarget(node);
                if (group.controller()) { dimensions.add(CONTROLLER_P1P1); }
                if (group.opponent()) { dimensions.add(OPPONENT_P1P1); }
                if (!group.other()) { dimensions.add(SOURCE_P1P1); }
            }
        } else if (("Pump".equals(node.api()) && acceptsPump(node))
                || ("Debuff".equals(node.api()) && acceptsDebuff(node))) {
            final CounterTarget target = counterTarget(node);
            if (target.scope() == CounterTargetScope.ANY_CREATURE
                    || target.scope() == CounterTargetScope.CONTROLLER_CREATURE) {
                dimensions.add(CONTROLLER_CREATURE);
            }
            if (target.scope() == CounterTargetScope.ANY_CREATURE
                    || target.scope() == CounterTargetScope.OPPONENT_CREATURE) {
                dimensions.add(OPPONENT_CREATURE);
            }
        } else if ("Animate".equals(node.api()) && acceptsAnimate(node)) {
            addRemovalDimensions(node, dimensions);
        } else if ("AnimateAll".equals(node.api()) && acceptsAnimateAll(node)) {
            addCreatureGroupDimensions(creatureGroupTarget(node), dimensions);
        } else if ("GainControl".equals(node.api()) && acceptsGainControl(node)) {
            addRemovalDimensions(node, dimensions);
        } else if (Set.of("GainLife", "LoseLife", "SetLife").contains(node.api())
                && acceptsLife(node)) {
            addPlayerDimensions(node, dimensions);
        } else if ("Discard".equals(node.api()) && acceptsDiscard(node)) {
            addHandDimensions(node, dimensions);
        } else if (("DealDamage".equals(node.api()) || "DamageAll".equals(node.api()))
                && acceptsDamage(node)) {
            addDamageDimensions(node, dimensions);
        } else if ("Fight".equals(node.api()) && acceptsFight(node)) {
            addFightDimensions(node, dimensions);
        } else if (("Destroy".equals(node.api()) || "ChangeZone".equals(node.api()))
                && acceptsRemoval(node)) {
            addRemovalDimensions(node, dimensions);
        } else if ("Sacrifice".equals(node.api()) && acceptsSacrifice(node)) {
            if (playerSacrifice(node)) {
                final List<TargetRef> fixed = fixedPlayerRecipients(node);
                final PlayerTarget player = fixed == null ? playerTarget(node) : null;
                final boolean controller = fixed != null ? fixed.contains(TargetRef.CONTROLLER_PLAYER)
                        : player.fixed() == null || player.fixed() == TargetRef.CONTROLLER_PLAYER;
                final boolean opponent = fixed != null ? fixed.contains(TargetRef.OPPONENT_PLAYER)
                        : player.fixed() == null || player.fixed() == TargetRef.OPPONENT_PLAYER;
                if (controller) { dimensions.add(CONTROLLER_CREATURE); dimensions.add(CONTROLLER_CREATURE_COUNT); }
                if (opponent) { dimensions.add(OPPONENT_CREATURE); dimensions.add(OPPONENT_CREATURE_COUNT); }
            } else {
                final SacrificeTarget target = sacrificeTarget(node);
                if (target.scope() == SacrificeTargetScope.ANY_CREATURE
                    || target.scope() == SacrificeTargetScope.CONTROLLER_CREATURE) {
                    dimensions.add(CONTROLLER_CREATURE);
                }
                if (target.scope() == SacrificeTargetScope.ANY_CREATURE
                    || target.scope() == SacrificeTargetScope.OPPONENT_CREATURE) {
                    dimensions.add(OPPONENT_CREATURE);
                }
            }
        } else if ("SacrificeAll".equals(node.api()) && acceptsSacrificeAll(node)) {
            addCreatureGroupDimensions(creatureGroupTarget(node), dimensions);
        } else if ("DestroyAll".equals(node.api()) && acceptsDestroyAll(node)) {
            addCreatureGroupDimensions(creatureGroupTarget(node), dimensions);
        } else if ("ChangeZoneAll".equals(node.api()) && acceptsChangeZoneAll(node)) {
            final CreatureGroupTarget target = creatureGroupTarget(node, "ChangeType");
            addCreatureGroupDimensions(target, dimensions);
            if ("Hand".equalsIgnoreCase(node.parameters().get("Destination"))) {
                if (target.controller()) {
                    dimensions.add(CONTROLLER_HAND);
                }
                if (target.opponent()) {
                    dimensions.add(OPPONENT_HAND);
                }
            }
        }
        for (final AbilityOutcomeDescription choice : node.choices()) {
            collectDimensions(choice, dimensions, visited, depth + 1);
        }
        collectDimensions(node.next(), dimensions, visited, depth + 1);
    }

    private static void addCounterDimensions(final List<AbilityOutcomeDescription> nodes,
            final Set<String> dimensions) {
        for (final AbilityOutcomeDescription counterNode : nodes) {
            final CounterTarget target = counterTarget(counterNode);
            if (target != null && (target.scope() == CounterTargetScope.ANY_CREATURE
                    || target.scope() == CounterTargetScope.CONTROLLER_CREATURE)) {
                dimensions.add(CONTROLLER_CREATURE);
            }
            if (target != null && (target.scope() == CounterTargetScope.ANY_CREATURE
                    || target.scope() == CounterTargetScope.OPPONENT_CREATURE)) {
                dimensions.add(OPPONENT_CREATURE);
            }
        }
    }

    private static void addCreatureGroupDimensions(final CreatureGroupTarget target,
            final Set<String> dimensions) {
        if (target.controller()) {
            dimensions.add(CONTROLLER_CREATURE_COUNT);
            dimensions.add(CONTROLLER_CREATURE);
        }
        if (target.opponent()) {
            dimensions.add(OPPONENT_CREATURE_COUNT);
            dimensions.add(OPPONENT_CREATURE);
        }
    }

    private static void addPlayerDimensions(final AbilityOutcomeDescription node,
            final Set<String> dimensions) {
        final List<TargetRef> fixed = fixedPlayerRecipients(node);
        if (fixed != null) {
            for (final var recipient : fixed) {
                dimensions.add(recipient == TargetRef.CONTROLLER_PLAYER ? CONTROLLER_LIFE : OPPONENT_LIFE);
            }
            return;
        }
        final PlayerTarget target = playerTarget(node);
        if (target == null || target.fixed() == null && target.any() == null) {
            return;
        }
        if (target.fixed() == TargetRef.CONTROLLER_PLAYER || Boolean.TRUE.equals(target.any())) {
            dimensions.add(CONTROLLER_LIFE);
        }
        if (target.fixed() == TargetRef.OPPONENT_PLAYER || Boolean.TRUE.equals(target.any())) {
            dimensions.add(OPPONENT_LIFE);
        }
    }

    private static void addHandDimensions(final AbilityOutcomeDescription node,
            final Set<String> dimensions) {
        final List<TargetRef> fixed = fixedPlayerRecipients(node);
        if (fixed != null) {
            for (final var recipient : fixed) {
                dimensions.add(recipient == TargetRef.CONTROLLER_PLAYER ? CONTROLLER_HAND : OPPONENT_HAND);
            }
            return;
        }
        final PlayerTarget target = playerTarget(node);
        if (target == null || target.fixed() == null && target.any() == null) {
            return;
        }
        if (target.fixed() == TargetRef.CONTROLLER_PLAYER || Boolean.TRUE.equals(target.any())) {
            dimensions.add(CONTROLLER_HAND);
        }
        if (target.fixed() == TargetRef.OPPONENT_PLAYER || Boolean.TRUE.equals(target.any())) {
            dimensions.add(OPPONENT_HAND);
        }
    }

    private static void addDamageDimensions(final AbilityOutcomeDescription node,
            final Set<String> dimensions) {
        if ("DamageAll".equals(node.api())) {
            final List<TargetRef> targets = damageAllTargets(node);
            if (targets.contains(TargetRef.CONTROLLER_PLAYER)) {
                dimensions.add(CONTROLLER_LIFE);
            }
            if (targets.contains(TargetRef.OPPONENT_PLAYER)) {
                dimensions.add(OPPONENT_LIFE);
            }
            final CreatureGroupTarget creatureTarget = creatureGroupTarget(node);
            if (creatureTarget != null) {
                addCreatureGroupDimensions(creatureTarget, dimensions);
            }
            return;
        }
        final DamageTarget target = damageTarget(node);
        if (target == null) {
            return;
        }
        switch (target.scope()) {
        case WATCHED_CREATURE -> { } // The event binding supplies its own conditioned profile.
        case CONTROLLER_PLAYER -> dimensions.add(CONTROLLER_LIFE);
        case OPPONENT_PLAYER -> dimensions.add(OPPONENT_LIFE);
        case ANY_PLAYER -> {
            dimensions.add(CONTROLLER_LIFE);
            dimensions.add(OPPONENT_LIFE);
        }
        case ANY_TARGET -> {
            dimensions.add(CONTROLLER_LIFE);
            dimensions.add(OPPONENT_LIFE);
            dimensions.add(CONTROLLER_CREATURE);
            dimensions.add(OPPONENT_CREATURE);
        }
        case CONTROLLER_CREATURE -> dimensions.add(CONTROLLER_CREATURE);
        case OPPONENT_CREATURE -> dimensions.add(OPPONENT_CREATURE);
        case ANY_CREATURE -> {
            dimensions.add(CONTROLLER_CREATURE);
            dimensions.add(OPPONENT_CREATURE);
        }
        case ANY_PLANESWALKER, CONTROLLER_PLANESWALKER, OPPONENT_PLANESWALKER -> { }
        default -> throw new IllegalStateException("Unhandled damage target scope");
        }
        if (target.planeswalkers()) {
            if (target.scope() != DamageTargetScope.OPPONENT_CREATURE && target.scope() != DamageTargetScope.OPPONENT_PLANESWALKER) {
                dimensions.add(CONTROLLER_PERMANENT);
            }
            if (target.scope() != DamageTargetScope.CONTROLLER_CREATURE && target.scope() != DamageTargetScope.CONTROLLER_PLANESWALKER) {
                dimensions.add(OPPONENT_PERMANENT);
            }
        }
    }

    private static void addFightDimensions(final AbilityOutcomeDescription node,
            final Set<String> dimensions) {
        final PermanentTarget target = fightTarget(node);
        if (target == null) {
            return;
        }
        switch (target.scope()) {
        case CONTROLLER_CREATURE -> {
            dimensions.add(CONTROLLER_CREATURE);
            dimensions.add(CONTROLLER_LIFE);
        }
        case OPPONENT_CREATURE -> {
            dimensions.add(OPPONENT_CREATURE);
            dimensions.add(OPPONENT_LIFE);
        }
        case ANY_CREATURE -> {
            dimensions.add(CONTROLLER_CREATURE);
            dimensions.add(OPPONENT_CREATURE);
            dimensions.add(CONTROLLER_LIFE);
            dimensions.add(OPPONENT_LIFE);
        }
        default -> { }
        }
    }

    private static void addRemovalDimensions(final AbilityOutcomeDescription node,
            final Set<String> dimensions) {
        final PermanentTarget target = permanentTarget(node);
        if (target == null) {
            return;
        }
        switch (target.scope()) {
        case ANY_CREATURE -> {
            dimensions.add(CONTROLLER_CREATURE);
            dimensions.add(OPPONENT_CREATURE);
        }
        case CONTROLLER_CREATURE -> dimensions.add(CONTROLLER_CREATURE);
        case OPPONENT_CREATURE -> dimensions.add(OPPONENT_CREATURE);
        case ANY_PERMANENT -> {
            dimensions.add(CONTROLLER_PERMANENT);
            dimensions.add(OPPONENT_PERMANENT);
        }
        case CONTROLLER_PERMANENT -> dimensions.add(CONTROLLER_PERMANENT);
        case OPPONENT_PERMANENT -> dimensions.add(OPPONENT_PERMANENT);
        case SELF -> { }
        }
        if ("ChangeZone".equals(node.api())
                && "Hand".equalsIgnoreCase(node.parameters().get("Destination"))) {
            switch (target.scope()) {
            case ANY_CREATURE, ANY_PERMANENT -> {
                dimensions.add(CONTROLLER_HAND);
                dimensions.add(OPPONENT_HAND);
            }
            case CONTROLLER_CREATURE, CONTROLLER_PERMANENT -> dimensions.add(CONTROLLER_HAND);
            case OPPONENT_CREATURE, OPPONENT_PERMANENT -> dimensions.add(OPPONENT_HAND);
            case SELF -> {
                dimensions.add(CONTROLLER_HAND);
                if (target.fixed() == TargetRef.WATCHED_CREATURE) { dimensions.add(OPPONENT_HAND); }
            }
            }
        }
    }

    private enum CounterTargetScope {
        SELF, ANY_CREATURE, CONTROLLER_CREATURE, OPPONENT_CREATURE, WATCHED_CREATURE
    }

    private record CounterTarget(CounterTargetScope scope, boolean other) { }

    private record CreatureGroupTarget(boolean controller, boolean opponent, boolean other) { }

    private record GroupApplication(State state, int value, boolean supported) {
        private static GroupApplication unsupported(final State state) {
            return new GroupApplication(state, 0, false);
        }
    }

    private record TokenSpec(List<String> scripts, int amount, boolean recipientIsController) { }

    private record PlayerTarget(TargetRef fixed, Boolean any) { }

    private enum DamageTargetScope {
        CONTROLLER_PLAYER, OPPONENT_PLAYER, ANY_PLAYER, ANY_TARGET,
        CONTROLLER_CREATURE, OPPONENT_CREATURE, ANY_CREATURE, WATCHED_CREATURE,
        ANY_PLANESWALKER, CONTROLLER_PLANESWALKER, OPPONENT_PLANESWALKER
    }

    private record DamageTarget(DamageTargetScope scope, TargetRef fixed, boolean planeswalkers) {
        DamageTarget(final DamageTargetScope scope, final TargetRef fixed) { this(scope, fixed, false); }
    }

    private enum PermanentTargetScope {
        SELF, ANY_CREATURE, CONTROLLER_CREATURE, OPPONENT_CREATURE,
        ANY_PERMANENT, CONTROLLER_PERMANENT, OPPONENT_PERMANENT
    }

    private record PermanentTarget(PermanentTargetScope scope, TargetRef fixed, Set<PermanentKind> kinds,
            IntrinsicStaticRecipientFilter.Filter characteristics) {
        PermanentTarget(final PermanentTargetScope scope, final TargetRef fixed) { this(scope, fixed, Set.of(), null); }
        PermanentTarget(final PermanentTargetScope scope, final TargetRef fixed, final Set<PermanentKind> kinds) {
            this(scope, fixed, kinds, null);
        }

        boolean matches(final PermanentProfile profile) {
            return (kinds.isEmpty() || kinds.contains(profile.kind()))
                    && (characteristics == null || characteristics.matches(profile));
        }
    }

    private enum SacrificeTargetScope {
        SELF, ANY_CREATURE, CONTROLLER_CREATURE, OPPONENT_CREATURE
    }

    private record SacrificeTarget(SacrificeTargetScope scope, boolean other) { }

    private static PermanentTarget permanentTarget(final AbilityOutcomeDescription node) {
        final String defined = node.parameters().get("Defined");
        final String validTargets = node.parameters().get("ValidTgts");
        if (defined != null) {
            if (validTargets == null && watchedRecipient(defined)) {
                return new PermanentTarget(PermanentTargetScope.SELF, TargetRef.WATCHED_CREATURE);
            }
            return validTargets == null && "Self".equalsIgnoreCase(defined)
                    ? new PermanentTarget(PermanentTargetScope.SELF, TargetRef.SOURCE) : null;
        }
        if (validTargets == null || !oneTarget(node)
                || node.parameters().containsKey("TgtZone")
                        && !"Battlefield".equalsIgnoreCase(node.parameters().get("TgtZone"))) {
            return null;
        }
        return switch (validTargets.toLowerCase(Locale.ROOT)) {
        case "creature" -> new PermanentTarget(PermanentTargetScope.ANY_CREATURE, null);
        case "creature.youctrl" -> new PermanentTarget(PermanentTargetScope.CONTROLLER_CREATURE, null);
        case "creature.oppctrl" -> new PermanentTarget(PermanentTargetScope.OPPONENT_CREATURE, null);
        case "permanent" ->
                new PermanentTarget(PermanentTargetScope.ANY_PERMANENT, null);
        case "permanent.youctrl" ->
                new PermanentTarget(PermanentTargetScope.CONTROLLER_PERMANENT, null);
        case "permanent.oppctrl" ->
                new PermanentTarget(PermanentTargetScope.OPPONENT_PERMANENT, null);
        default -> typedPermanentTarget(validTargets);
        };
    }

    private static PermanentTarget typedPermanentTarget(final String validity) {
        final var filter = IntrinsicPermanentTargetFilter.parse(validity).orElse(null);
        if (filter == null) {
            final var characteristics = IntrinsicStaticRecipientFilter.describe(validity, null);
            if (!characteristics.profileRestricted()) { return null; }
            final PermanentTargetScope creatureScope = switch (characteristics.affected().toLowerCase(Locale.ROOT)) {
            case "creature" -> PermanentTargetScope.ANY_CREATURE;
            case "creature.youctrl" -> PermanentTargetScope.CONTROLLER_CREATURE;
            case "creature.oppctrl", "creature.youdontctrl" -> PermanentTargetScope.OPPONENT_CREATURE;
            default -> null;
            };
            // TODO: Mixed type/predicate unions need conditional creature profiles inside the
            // generic permanent population; do not treat its vanilla representative as complete.
            return creatureScope == null ? null : new PermanentTarget(creatureScope, null, Set.of(), characteristics);
        }
        final PermanentTargetScope scope = switch (filter.controller()) {
        case ANY -> PermanentTargetScope.ANY_PERMANENT;
        case FRIENDLY -> PermanentTargetScope.CONTROLLER_PERMANENT;
        case OPPOSING -> PermanentTargetScope.OPPONENT_PERMANENT;
        };
        return new PermanentTarget(scope, null, filter.kinds());
    }

    private static PermanentTarget fightTarget(final AbilityOutcomeDescription node) {
        final String defined = node.parameters().get("Defined");
        final String validTargets = node.parameters().get("ValidTgts");
        if (!"Self".equalsIgnoreCase(defined) || validTargets == null
                || !oneTarget(node)
                || node.parameters().containsKey("TgtZone")
                        && !"Battlefield".equalsIgnoreCase(node.parameters().get("TgtZone"))) {
            return null;
        }
        return switch (validTargets.toLowerCase(Locale.ROOT)) {
        case "creature", "creature.other" -> new PermanentTarget(PermanentTargetScope.ANY_CREATURE, null);
        case "creature.youctrl", "creature.youctrl+other", "creature.other+youctrl" ->
                new PermanentTarget(PermanentTargetScope.CONTROLLER_CREATURE, null);
        case "creature.oppctrl", "creature.youdontctrl",
                "creature.oppctrl+other", "creature.other+oppctrl" ->
                new PermanentTarget(PermanentTargetScope.OPPONENT_CREATURE, null);
        default -> null;
        };
    }

    private static List<TargetRef> permanentCandidates(final State state,
            final PermanentTarget target) {
        final List<TargetRef> result = new java.util.ArrayList<>(2);
        final boolean friendly = target.scope() != PermanentTargetScope.OPPONENT_CREATURE
                && target.scope() != PermanentTargetScope.OPPONENT_PERMANENT;
        final boolean opposing = target.scope() != PermanentTargetScope.CONTROLLER_CREATURE
                && target.scope() != PermanentTargetScope.CONTROLLER_PERMANENT;
        final boolean creatures = target.scope() == PermanentTargetScope.ANY_CREATURE
                || target.scope() == PermanentTargetScope.CONTROLLER_CREATURE
                || target.scope() == PermanentTargetScope.OPPONENT_CREATURE;
        final boolean permanents = target.scope() == PermanentTargetScope.ANY_PERMANENT
                || target.scope() == PermanentTargetScope.CONTROLLER_PERMANENT
                || target.scope() == PermanentTargetScope.OPPONENT_PERMANENT;
        if (creatures) {
            if (friendly && target.matches(fromCreature(state.controllerCreature(), true))
                    && canTarget(toPermanent(state.controllerCreature()), true)) {
                result.add(TargetRef.CONTROLLER_CREATURE);
            }
            if (opposing && target.matches(fromCreature(state.opponentCreature(), false))
                    && canTarget(toPermanent(state.opponentCreature()), false)) {
                result.add(TargetRef.OPPONENT_CREATURE);
            }
        }
        if (permanents) {
            if (friendly && target.matches(state.controllerPermanent()) && canTarget(state.controllerPermanent(), true)) {
                result.add(TargetRef.CONTROLLER_PERMANENT);
            }
            if (opposing && target.matches(state.opponentPermanent()) && canTarget(state.opponentPermanent(), false)) {
                result.add(TargetRef.OPPONENT_PERMANENT);
            }
        }
        return result;
    }

    private static SacrificeTarget sacrificeTarget(final AbilityOutcomeDescription node) {
        final String defined = node.parameters().get("Defined");
        final String valid = node.parameters().get("SacValid");
        if (defined != null) {
            return "Self".equalsIgnoreCase(defined)
                    && (valid == null || "Self".equalsIgnoreCase(valid))
                    ? new SacrificeTarget(SacrificeTargetScope.SELF, false) : null;
        }
        if (valid == null || valid.isBlank() || "self".equalsIgnoreCase(valid)) {
            return new SacrificeTarget(SacrificeTargetScope.SELF, false);
        }
        final String normalized = valid.toLowerCase(Locale.ROOT);
        final boolean other = normalized.contains("other");
        return switch (normalized) {
        case "creature", "creature.other" ->
                new SacrificeTarget(SacrificeTargetScope.ANY_CREATURE, other);
        case "creature.youctrl", "creature.youctrl+other", "creature.other+youctrl" ->
                new SacrificeTarget(SacrificeTargetScope.CONTROLLER_CREATURE, other);
        case "creature.oppctrl", "creature.oppctrl+other", "creature.other+oppctrl" ->
                new SacrificeTarget(SacrificeTargetScope.OPPONENT_CREATURE, other);
        default -> null;
        };
    }

    private static List<TargetRef> sacrificeCandidates(final State state,
            final SacrificeTarget target) {
        final List<TargetRef> result = new java.util.ArrayList<>(3);
        final boolean friendly = target.scope() != SacrificeTargetScope.OPPONENT_CREATURE;
        final boolean opposing = target.scope() != SacrificeTargetScope.CONTROLLER_CREATURE;
        if (friendly && state.controllerCreature().present()) {
            result.add(TargetRef.CONTROLLER_CREATURE);
        }
        if (opposing && state.opponentCreature().present()) {
            result.add(TargetRef.OPPONENT_CREATURE);
        }
        final PermanentProfile source = state.sourcePermanent();
        if (!target.other() && isCreature(source)
                && (source.controlledByAi() ? friendly : opposing)) {
            result.add(TargetRef.SOURCE);
        }
        return result;
    }

    private static PermanentProfile toPermanent(final CreatureProfile profile) {
        return new PermanentProfile(profile.present(), PermanentKind.CREATURE, true,
                profile.power(), profile.toughness(), profile.keywords());
    }

    private static boolean canTarget(final PermanentProfile profile, final boolean friendly) {
        return profile.present() && !hasKeyword(profile, "shroud")
                && (friendly || !hasKeyword(profile, "hexproof"));
    }

    private static State removePermanent(final State state, final TargetRef target) {
        return switch (target) {
        // The watched entrant is distinct from the population representative and source.
        // TODO: Correlate its membership with group counts before admitting overlapping sweeps.
        case WATCHED_CREATURE -> {
            final PermanentProfile before = state.watchedCreature();
            yield state.withWatchedCreature(new PermanentProfile(false, before.kind(), before.controlledByAi(),
                    before.power(), before.toughness(), before.keywords(), before.basicLand(), before.loyalty()));
        }
        case SOURCE -> state.withSourcePermanent(PermanentProfile.absent());
        case CONTROLLER_CREATURE -> state.withCreatures(true, CreatureProfile.absent())
                .withCreatureCount(true, state.controllerCreatureCount() - 1);
        case OPPONENT_CREATURE -> state.withCreatures(false, CreatureProfile.absent())
                .withCreatureCount(false, state.opponentCreatureCount() - 1);
        case CONTROLLER_PERMANENT -> state.withPermanent(true, PermanentProfile.absent());
        case OPPONENT_PERMANENT -> state.withPermanent(false, PermanentProfile.absent());
        case CONTROLLER_PLAYER, OPPONENT_PLAYER -> state;
        };
    }

    /**
     * Resolves only literal single-creature scopes. Defined group aliases are intentionally not
     * treated as recipients; their live meaning can contain many objects. The candidate slots
     * used below are separate from sourcePermanent, so the simple +Other forms remain distinct
     * without fabricating a live target identity.
     */
    private static CounterTarget counterTarget(final AbilityOutcomeDescription node) {
        final String defined = node.parameters().get("Defined");
        final String validTargets = node.parameters().get("ValidTgts");
        if (defined != null) {
            if (validTargets == null && watchedRecipient(defined)) {
                return new CounterTarget(CounterTargetScope.WATCHED_CREATURE, false);
            }
            return validTargets == null && "Self".equalsIgnoreCase(defined)
                    ? new CounterTarget(CounterTargetScope.SELF, false) : null;
        }
        if (validTargets == null || validTargets.isBlank()) {
            // Forge uses an omitted recipient for the common "put a counter on CARDNAME" form.
            // This is safe for intrinsic evaluation because the source is the only fixed recipient.
            return new CounterTarget(CounterTargetScope.SELF, false);
        }
        final boolean other = validTargets.toLowerCase(Locale.ROOT).contains("other");
        return switch (validTargets.toLowerCase(Locale.ROOT)) {
        case "creature", "creature.other" -> new CounterTarget(CounterTargetScope.ANY_CREATURE, other);
        case "creature.youctrl", "creature.youctrl+other", "creature.other+youctrl" ->
                new CounterTarget(CounterTargetScope.CONTROLLER_CREATURE, other);
        case "creature.oppctrl", "creature.oppctrl+other", "creature.other+oppctrl" ->
                new CounterTarget(CounterTargetScope.OPPONENT_CREATURE, other);
        default -> null;
        };
    }

    private static List<TargetRef> candidates(final State state, final CounterTarget target) {
        final List<TargetRef> result = new java.util.ArrayList<>(3);
        final boolean friendly = target.scope() != CounterTargetScope.OPPONENT_CREATURE;
        final boolean opposing = target.scope() != CounterTargetScope.CONTROLLER_CREATURE;
        if (friendly && canTarget(state.controllerCreature(), true)) {
            result.add(TargetRef.CONTROLLER_CREATURE);
        }
        if (opposing && canTarget(state.opponentCreature(), false)) {
            result.add(TargetRef.OPPONENT_CREATURE);
        }
        final PermanentProfile source = state.sourcePermanent();
        if (!target.other() && isCreature(source)
                && (source.controlledByAi() ? friendly : opposing)
                && canTarget(toCreature(source), source.controlledByAi())) {
            result.add(TargetRef.SOURCE);
        }
        return result;
    }

    private static boolean canTarget(final CreatureProfile profile, final boolean friendly) {
        return profile.present() && profile.keywords().stream().noneMatch("Shroud"::equalsIgnoreCase)
                && (friendly || !profile.hexproof()
                        && profile.keywords().stream().noneMatch("Hexproof"::equalsIgnoreCase));
    }

    private static boolean hasUnmodeledCandidate(final State state, final CounterTarget target) {
        final boolean friendly = target.scope() != CounterTargetScope.OPPONENT_CREATURE;
        final boolean opposing = target.scope() != CounterTargetScope.CONTROLLER_CREATURE;
        if (friendly && state.controllerCreature().present() && !simpleKeywords(state.controllerCreature().keywords())
                || opposing && state.opponentCreature().present() && !simpleKeywords(state.opponentCreature().keywords())) {
            return true;
        }
        final PermanentProfile source = state.sourcePermanent();
        return !target.other() && isCreature(source) && (source.controlledByAi() ? friendly : opposing)
                && !simpleKeywords(source.keywords());
    }

    private static boolean simpleKeywords(final Set<String> keywords) {
        return keywords.stream().allMatch(keyword -> SIMPLE_CREATURE_KEYWORDS.contains(keyword.toLowerCase(Locale.ROOT)));
    }

    private static boolean hasCreatureTarget(final State state, final TargetRef target) {
        return switch (target) {
        case WATCHED_CREATURE -> isCreature(state.watchedCreature());
        case SOURCE -> isCreature(state.sourcePermanent());
        case CONTROLLER_CREATURE -> state.controllerCreature().present();
        case OPPONENT_CREATURE -> state.opponentCreature().present();
        default -> false;
        };
    }

    /** Defined objects are instructions, not mandatory targets: absence does not fizzle the rest. */
    private static Outcome<State> watchedInstruction(final Outcome<State> instruction) {
        return new Outcome.Deferred<>(state -> state.watchedCreature().present() ? instruction
                : new Outcome.Atomic<>(current -> new Outcome.Transition<>(0, current.clearTarget())));
    }

    private static boolean hasCounterTarget(final State state, final TargetRef target,
            final AbilityOutcomeDescription node) {
        if ("LOYALTY".equalsIgnoreCase(counterType(node))) {
            return target == TargetRef.SOURCE && state.sourcePermanent().present()
                    && state.sourcePermanent().kind() == PermanentKind.PLANESWALKER;
        }
        return hasCreatureTarget(state, target);
    }

    private static boolean controls(final TargetRef target, final State state) {
        return switch (target) {
        case WATCHED_CREATURE -> state.watchedCreature().controlledByAi();
        case CONTROLLER_CREATURE, CONTROLLER_PERMANENT, CONTROLLER_PLAYER -> true;
        case OPPONENT_CREATURE, OPPONENT_PERMANENT, OPPONENT_PLAYER -> false;
        case SOURCE -> state.sourcePermanent().controlledByAi();
        };
    }

    private static Boolean newControllerIsAi(final AbilityOutcomeDescription node) {
        final String value = node.parameters().getOrDefault("NewController", "You");
        return switch (value.toLowerCase(Locale.ROOT)) {
        case "you", "controller", "activatingplayer" -> true;
        case "opponent", "opponentctrl", "opposingplayer" -> false;
        default -> null;
        };
    }

    private static PermanentProfile controlledPermanent(final PermanentProfile profile,
            final boolean controller) {
        return new PermanentProfile(profile.present(), profile.kind(), controller,
                profile.power(), profile.toughness(), profile.keywords(), profile.basicLand(),
                profile.loyalty());
    }

    private static State moveControl(final State state, final TargetRef target,
            final PermanentProfile after, final boolean newController) {
        return switch (target) {
        case WATCHED_CREATURE -> state.withWatchedCreature(after);
        case SOURCE -> state.withSourcePermanent(after);
        case CONTROLLER_CREATURE -> moveCreatureControl(state, true, after, newController);
        case OPPONENT_CREATURE -> moveCreatureControl(state, false, after, newController);
        case CONTROLLER_PERMANENT -> movePermanentControl(state, true, after, newController);
        case OPPONENT_PERMANENT -> movePermanentControl(state, false, after, newController);
        case CONTROLLER_PLAYER, OPPONENT_PLAYER -> state;
        };
    }

    private static State moveCreatureControl(final State state, final boolean fromController,
            final PermanentProfile after, final boolean newController) {
        final boolean destinationPresent = newController
                ? state.controllerCreature().present() : state.opponentCreature().present();
        State moved = state.withCreatures(fromController, CreatureProfile.absent())
                .withCreatureCount(fromController,
                        state.creatureCount(fromController) - 1)
                .withCreatureCount(newController, state.creatureCount(newController) + 1);
        if (!destinationPresent) {
            moved = moved.withCreatures(newController, toCreature(after));
        }
        return moved;
    }

    private static State movePermanentControl(final State state, final boolean fromController,
            final PermanentProfile after, final boolean newController) {
        final boolean destinationPresent = newController
                ? state.controllerPermanent().present() : state.opponentPermanent().present();
        State moved = state.withPermanent(fromController, PermanentProfile.absent());
        if (!destinationPresent) {
            moved = moved.withPermanent(newController, after);
        }
        return moved;
    }

    private static PermanentProfile permanent(final State state, final TargetRef target) {
        return switch (target) {
        case WATCHED_CREATURE -> state.watchedCreature();
        case SOURCE -> state.sourcePermanent();
        case CONTROLLER_CREATURE -> fromCreature(state.controllerCreature(), true);
        case OPPONENT_CREATURE -> fromCreature(state.opponentCreature(), false);
        case CONTROLLER_PERMANENT -> state.controllerPermanent();
        case OPPONENT_PERMANENT -> state.opponentPermanent();
        case CONTROLLER_PLAYER, OPPONENT_PLAYER -> PermanentProfile.absent();
        };
    }

    private static State replacePermanent(final State state, final TargetRef target,
            final PermanentProfile replacement) {
        return switch (target) {
        case WATCHED_CREATURE -> state.withWatchedCreature(replacement);
        case SOURCE -> state.withSourcePermanent(replacement);
        case CONTROLLER_CREATURE -> replaceCreature(state, true, replacement);
        case OPPONENT_CREATURE -> replaceCreature(state, false, replacement);
        case CONTROLLER_PERMANENT -> state.withPermanent(true, replacement);
        case OPPONENT_PERMANENT -> state.withPermanent(false, replacement);
        case CONTROLLER_PLAYER, OPPONENT_PLAYER -> state;
        };
    }

    private static State replaceCreature(final State state, final boolean controller,
            final PermanentProfile replacement) {
        State projected = state.withCreatures(controller, toCreature(replacement));
        if (!replacement.present()) {
            projected = projected.withCreatureCount(controller,
                    state.creatureCount(controller) - 1);
        }
        return projected;
    }

    private static boolean isCreature(final PermanentProfile profile) {
        return profile.present() && (profile.kind() == PermanentKind.CREATURE
                || profile.kind() == PermanentKind.TOKEN);
    }

    private static boolean watchedRecipient(final String defined) {
        return "TriggeredCard".equals(defined) || "TriggeredCardLKICopy".equals(defined);
    }

    private static PermanentProfile addCounter(final PermanentProfile profile,
            final AbilityOutcomeDescription node) {
        final String type = counterType(node);
        if ("LOYALTY".equals(type)) {
            return new PermanentProfile(profile.present(), profile.kind(), profile.controlledByAi(),
                    profile.power(), profile.toughness(), profile.keywords(), profile.basicLand(),
                    boundedAdd(profile.loyalty(), integer(node, "CounterNum", 1)));
        }
        final String keyword = counterKeyword(type);
        if (keyword != null) {
            final Set<String> keywords = new LinkedHashSet<>(profile.keywords());
            keywords.add(keyword);
            return new PermanentProfile(profile.present(), profile.kind(), profile.controlledByAi(),
                    profile.power(), profile.toughness(), keywords, profile.basicLand(), profile.loyalty());
        }
        return addP1P1(profile, counterDelta(node));
    }

    private static PermanentProfile addP1P1(final PermanentProfile profile, final int amount) {
        final int power = boundedAdd(profile.power(), amount);
        final int toughness = boundedAdd(profile.toughness(), amount);
        return new PermanentProfile(profile.present(), profile.kind(), profile.controlledByAi(),
                power, toughness, profile.keywords(), profile.basicLand(), profile.loyalty());
    }

    private static PermanentProfile pumpPermanent(final PermanentProfile profile,
            final AbilityOutcomeDescription node) {
        final int power = boundedAdd(profile.power(), integer(node, "NumAtt", 0));
        final int toughness = boundedAdd(profile.toughness(), integer(node, "NumDef", 0));
        final Set<String> keywords = plusKeywords(profile.keywords(),
                supportedKeywords(node.parameters().get("KW")));
        return new PermanentProfile(profile.present(), profile.kind(), profile.controlledByAi(),
                power, toughness, keywords, profile.basicLand(), profile.loyalty());
    }

    private static PermanentProfile keywordPermanent(final PermanentProfile profile,
            final AbilityOutcomeDescription node, final boolean add) {
        final String parameter = add ? "KW" : "Keywords";
        final Set<String> changed = supportedKeywords(node.parameters().get(parameter));
        final Set<String> keywords;
        if (add) {
            keywords = plusKeywords(profile.keywords(), changed);
        } else {
            keywords = profile.keywords().stream()
                    .filter(keyword -> changed.stream().noneMatch(keyword::equalsIgnoreCase))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        return new PermanentProfile(profile.present(), profile.kind(), profile.controlledByAi(),
                profile.power(), profile.toughness(), keywords, profile.basicLand(), profile.loyalty());
    }

    private static PermanentProfile animatePermanent(final PermanentProfile profile,
            final AbilityOutcomeDescription node) {
        final Set<String> keywords = plusKeywords(profile.keywords(),
                supportedKeywords(node.parameters().get("Keywords")));
        return new PermanentProfile(true, PermanentKind.CREATURE, profile.controlledByAi(),
                integer(node, "Power", profile.power()), integer(node, "Toughness", profile.toughness()),
                keywords, profile.basicLand(), profile.loyalty());
    }

    private static Set<String> plusKeywords(final Set<String> original,
            final Set<String> additions) {
        final Set<String> result = new java.util.LinkedHashSet<>(original);
        if (additions != null) {
            result.addAll(additions);
        }
        return Set.copyOf(result);
    }

    private static PermanentProfile removeCounterPermanent(final PermanentProfile profile,
            final AbilityOutcomeDescription node) {
        final String type = counterType(node);
        final int amount = integer(node, "CounterNum", 1);
        if ("P1P1".equals(type)) {
            if (profile.power() < amount || profile.toughness() < amount) {
                return null;
            }
            final PermanentProfile after = addP1P1(profile, -amount);
            return after.toughness() == 0 ? PermanentProfile.absent() : after;
        }
        if ("M1M1".equals(type)) {
            return addP1P1(profile, amount);
        }
        if ("LOYALTY".equals(type)) {
            final int loyalty = profile.loyalty() - amount;
            if (loyalty < 0) {
                return null;
            }
            return loyalty == 0 ? PermanentProfile.absent() : new PermanentProfile(profile.present(),
                    profile.kind(), profile.controlledByAi(), profile.power(), profile.toughness(),
                    profile.keywords(), profile.basicLand(), loyalty);
        }
        final String keyword = counterKeyword(type);
        if (keyword == null) {
            return null;
        }
        final Set<String> keywords = profile.keywords().stream()
                .filter(value -> !value.equalsIgnoreCase(keyword))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new PermanentProfile(profile.present(), profile.kind(), profile.controlledByAi(),
                profile.power(), profile.toughness(), keywords, profile.basicLand(), profile.loyalty());
    }

    private static int counterDelta(final AbilityOutcomeDescription node) {
        final int amount = integer(node, "CounterNum", 1);
        return "M1M1".equalsIgnoreCase(node.parameters().get("CounterType")) ? -amount : amount;
    }

    private static String counterType(final AbilityOutcomeDescription node) {
        return node.parameters().getOrDefault("CounterType", "").trim().toUpperCase(Locale.ROOT);
    }

    private static boolean supportedCounterType(final String type) {
        return INTRINSIC_COUNTER_TYPES.contains(type) || VALUED_KEYWORD_COUNTERS.contains(type);
    }

    private static String counterKeyword(final String type) {
        return switch (type) {
        case "SHIELD" -> "Shield";
        case "STUN" -> "Stun";
        case "FLYING" -> "Flying";
        case "DEATHTOUCH" -> "Deathtouch";
        case "LIFELINK" -> "Lifelink";
        case "TRAMPLE" -> "Trample";
        case "VIGILANCE" -> "Vigilance";
        case "DEFENDER" -> "Defender";
        case "CANTATTACK" -> "can't attack";
        case "CANTBLOCK" -> "can't block";
        case "DETAIN" -> "detain";
        case "CANTUNTAP" -> "can't untap";
        case "HEXPROOF" -> "Hexproof";
        case "SHROUD" -> "Shroud";
        case "INDESTRUCTIBLE" -> "Indestructible";
        case "WARD" -> "Ward";
        default -> null;
        };
    }

    private static int boundedAdd(final int left, final int right) {
        final long result = (long) left + right;
        return result >= Integer.MAX_VALUE ? Integer.MAX_VALUE
                : result <= 0 ? 0 : (int) result;
    }

    private static PermanentProfile fromCreature(final CreatureProfile profile, final boolean controller) {
        final Set<String> keywords = new LinkedHashSet<>(profile.keywords());
        if (profile.hexproof()) { keywords.add("Hexproof"); }
        if (profile.indestructible()) { keywords.add("Indestructible"); }
        return new PermanentProfile(profile.present(), PermanentKind.CREATURE, controller,
                profile.power(), profile.toughness(), keywords, false, 0);
    }

    private static CreatureProfile toCreature(final PermanentProfile profile) {
        return new CreatureProfile(profile.present(), profile.power(), profile.toughness(),
                profile.keywords(), hasKeyword(profile, "hexproof") || hasKeyword(profile, "shroud"),
                hasKeyword(profile, "indestructible"));
    }

    private static boolean hasKeyword(final PermanentProfile profile, final String keyword) {
        return profile.keywords().stream().anyMatch(value -> value.equalsIgnoreCase(keyword));
    }

    private static boolean hasKeyword(final CreatureProfile profile, final String keyword) {
        return profile.keywords().stream().anyMatch(value -> value.equalsIgnoreCase(keyword));
    }

    private static Outcome<State> unresolved(final AbilityOutcomeDescription node, final String reason) {
        return new Outcome.Unresolved<>(node.path() + ": " + reason);
    }

    private static boolean literalPositive(final AbilityOutcomeDescription node,
            final String name, final int defaultValue) {
        final String raw = node.parameters().get(name);
        if (raw == null) {
            return defaultValue > 0;
        }
        try {
            return Integer.parseInt(raw) > 0;
        } catch (final NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean literalSigned(final AbilityOutcomeDescription node, final String name) {
        final String raw = node.parameters().get(name);
        if (raw == null || raw.isBlank()) {
            return true;
        }
        try {
            final int value = Integer.parseInt(raw.trim());
            return value >= -20 && value <= 20;
        } catch (final NumberFormatException ignored) {
            return false;
        }
    }

    private static int integer(final AbilityOutcomeDescription node, final String name,
            final int defaultValue) {
        final String raw = node.parameters().get(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(raw);
    }

    private static Set<String> parameters(final String... names) {
        final Set<String> result = new LinkedHashSet<>(COMMON_METADATA);
        Collections.addAll(result, names);
        return Set.copyOf(result);
    }
}
