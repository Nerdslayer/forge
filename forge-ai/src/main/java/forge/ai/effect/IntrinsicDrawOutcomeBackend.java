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

    private static final int MAX_DIMENSION_DEPTH = 24;
    private static final Set<String> COMMON_METADATA = Set.of("DB", "SubAbility",
            "SpellDescription", "StackDescription");
    private static final Set<String> CHOICE_PARAMETERS = Set.of(
            "DB", "AB", "SP", "Cost", "Choices", "CharmNum", "MinCharmNum", "CanRepeatModes",
            "ChoiceAmount", "Defined", "Chooser", "Random", "AtRandom", "SubAbility",
            "SpellDescription", "StackDescription");
    private static final Set<String> DRAW_PARAMETERS = parameters("NumCards", "Defined");
    private static final Set<String> COUNTER_PARAMETERS = parameters("CounterType", "CounterNum",
            "Defined", "ValidCards", "ValidTgts", "TgtPrompt", "TargetMin", "TargetMax", "TgtZone");
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
    private static final Set<String> LIFE_PARAMETERS = parameters("Defined", "LifeAmount",
            "ValidTgts", "ValidTgtsDesc", "TgtPrompt", "TargetMin", "TargetMax");
    private static final Set<String> DISCARD_PARAMETERS = parameters("Defined", "Mode", "NumCards",
            "ValidTgts", "ValidTgtsDesc", "TgtPrompt", "TargetMin", "TargetMax");
    private static final Set<String> MANA_PARAMETERS = parameters("Defined", "Produced", "Amount");
    private static final Set<String> MANA_REFLECTED_PARAMETERS = parameters("Defined", "ColorOrType",
            "ReflectProperty", "Amount");
    private static final Set<String> DAMAGE_PARAMETERS = parameters("Defined", "NumDmg", "DamageSource",
            "ValidTgts", "ValidTgtsDesc", "TgtPrompt", "TargetMin", "TargetMax");
    private static final Set<String> DAMAGE_ALL_PARAMETERS = parameters("ValidPlayers", "NumDmg",
            "DamageSource");
    private static final Set<String> REMOVAL_PARAMETERS = parameters("Defined", "ValidTgts",
            "ValidTgtsDesc", "TgtPrompt", "TargetMin", "TargetMax", "TgtZone", "Origin",
            "Destination", "NoRegen", "Radiance", "Duration", "ChangeNum", "ChangeType",
            "Chooser", "DefinedPlayer", "GainControl", "Tapped", "RememberChanged");
    private static final Set<String> SACRIFICE_PARAMETERS = parameters("Defined", "SacValid", "Amount");
    private static final Set<String> SIMPLE_CREATURE_KEYWORDS = Set.of("flying", "first strike", "double strike",
            "haste", "reach", "menace", "fear", "intimidate", "vigilance", "trample", "deathtouch", "lifelink", "defender",
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
        CONTROLLER_PERMANENT, OPPONENT_PERMANENT, SOURCE
    }

    /** Immutable reference state. The two-argument constructor preserves the original API. */
    public record State(int controllerHand, int opponentHand, int controllerLife, int opponentLife,
            int controllerMana, int opponentMana, int controllerCreatureCount,
            int opponentCreatureCount, CreatureProfile controllerCreature,
            CreatureProfile opponentCreature, PermanentProfile controllerPermanent,
            PermanentProfile opponentPermanent, PermanentProfile sourcePermanent,
            TargetRef target) {
        public State {
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
                    sourcePermanent, value);
        }

        State clearTarget() {
            return target == null ? this : withTarget(null);
        }

        State withHands(final boolean controller, final int value) {
            return controller
                    ? new State(value, opponentHand, controllerLife, opponentLife, controllerMana,
                            opponentMana, controllerCreatureCount, opponentCreatureCount,
                            controllerCreature, opponentCreature, controllerPermanent, opponentPermanent,
                            sourcePermanent, target)
                    : new State(controllerHand, value, controllerLife, opponentLife, controllerMana,
                            opponentMana, controllerCreatureCount, opponentCreatureCount,
                            controllerCreature, opponentCreature, controllerPermanent, opponentPermanent,
                            sourcePermanent, target);
        }

        State withLife(final boolean controller, final int value) {
            return controller
                    ? new State(controllerHand, opponentHand, value, opponentLife, controllerMana,
                            opponentMana, controllerCreatureCount, opponentCreatureCount,
                            controllerCreature, opponentCreature, controllerPermanent, opponentPermanent,
                            sourcePermanent, target)
                    : new State(controllerHand, opponentHand, controllerLife, value, controllerMana,
                            opponentMana, controllerCreatureCount, opponentCreatureCount,
                            controllerCreature, opponentCreature, controllerPermanent, opponentPermanent,
                            sourcePermanent, target);
        }

        State withMana(final boolean controller, final int value) {
            return controller
                    ? new State(controllerHand, opponentHand, controllerLife, opponentLife, value,
                            opponentMana, controllerCreatureCount, opponentCreatureCount,
                            controllerCreature, opponentCreature, controllerPermanent, opponentPermanent,
                            sourcePermanent, target)
                    : new State(controllerHand, opponentHand, controllerLife, opponentLife, controllerMana,
                            value, controllerCreatureCount, opponentCreatureCount,
                            controllerCreature, opponentCreature, controllerPermanent, opponentPermanent,
                            sourcePermanent, target);
        }

        State withCreatureCount(final boolean controller, final int value) {
            return controller
                    ? new State(controllerHand, opponentHand, controllerLife, opponentLife, controllerMana,
                            opponentMana, Math.max(0, value), opponentCreatureCount, controllerCreature,
                            opponentCreature, controllerPermanent, opponentPermanent, sourcePermanent, target)
                    : new State(controllerHand, opponentHand, controllerLife, opponentLife, controllerMana,
                            opponentMana, controllerCreatureCount, Math.max(0, value), controllerCreature,
                            opponentCreature, controllerPermanent, opponentPermanent, sourcePermanent, target);
        }

        int creatureCount(final boolean controller) {
            return controller ? controllerCreatureCount : opponentCreatureCount;
        }

        State withCreatures(final boolean controller, final CreatureProfile profile) {
            return controller
                    ? new State(controllerHand, opponentHand, controllerLife, opponentLife, controllerMana,
                            opponentMana, controllerCreatureCount, opponentCreatureCount, profile,
                            opponentCreature, controllerPermanent, opponentPermanent, sourcePermanent, target)
                    : new State(controllerHand, opponentHand, controllerLife, opponentLife, controllerMana,
                            opponentMana, controllerCreatureCount, opponentCreatureCount,
                            controllerCreature, profile, controllerPermanent, opponentPermanent,
                            sourcePermanent, target);
        }

        State withPermanent(final boolean controller, final PermanentProfile profile) {
            return controller
                    ? new State(controllerHand, opponentHand, controllerLife, opponentLife, controllerMana,
                            opponentMana, controllerCreatureCount, opponentCreatureCount,
                            controllerCreature, opponentCreature, profile, opponentPermanent,
                            sourcePermanent, target)
                    : new State(controllerHand, opponentHand, controllerLife, opponentLife, controllerMana,
                            opponentMana, controllerCreatureCount, opponentCreatureCount,
                            controllerCreature, opponentCreature, controllerPermanent, profile,
                            sourcePermanent, target);
        }

        State withSourcePermanent(final PermanentProfile profile) {
            return new State(controllerHand, opponentHand, controllerLife, opponentLife,
                    controllerMana, opponentMana, controllerCreatureCount, opponentCreatureCount,
                    controllerCreature, opponentCreature, controllerPermanent, opponentPermanent,
                    profile, target);
        }
    }

    private final IntrinsicOutcomeEvaluator evaluator;
    private final Function<String, Optional<PermanentProfile>> tokenProfileResolver;

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
        // Source characteristics are read from each projected State, never a stale initial copy.
        evaluator = new IntrinsicOutcomeEvaluator(settings);
        this.tokenProfileResolver = tokenProfileResolver == null ? script -> Optional.empty()
                : tokenProfileResolver;
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
        if ("Charm".equals(node.api()) || "GenericChoice".equals(node.api())) {
            return !node.choices().isEmpty() && node.choices().size() <= 32
                    && CHOICE_PARAMETERS.containsAll(node.parameters().keySet())
                    && (!node.parameters().containsKey("CanRepeatModes")
                            || "True".equalsIgnoreCase(node.parameters().get("CanRepeatModes")));
        }
        if (!node.choices().isEmpty()) { return false; }
        return switch (node.api()) {
        case "Draw" -> acceptsDraw(node);
        case "PutCounter" -> acceptsCounter(node) || acceptsCounterChoice(node);
        case "PutCounterAll" -> acceptsCounterAll(node);
        case "Pump" -> acceptsPump(node);
        case "PumpAll" -> acceptsPumpAll(node);
        case "Debuff" -> acceptsDebuff(node);
        case "Animate" -> acceptsAnimate(node);
        case "AnimateAll" -> acceptsAnimateAll(node);
        case "Token" -> acceptsToken(node);
        case "GainLife", "LoseLife" -> acceptsLife(node);
        case "Discard" -> acceptsDiscard(node);
        case "Mana" -> acceptsMana(node);
        case "ManaReflected" -> acceptsManaReflected(node);
        case "DealDamage", "DamageAll" -> acceptsDamage(node);
        case "Destroy", "ChangeZone" -> acceptsRemoval(node);
        case "Sacrifice" -> acceptsSacrifice(node);
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
        return Set.copyOf(dimensions);
    }

    @Override
    public Outcome<State> bindTargets(final List<AbilityOutcomeDescription> chain, final Outcome<State> child) {
        // A chain with one targeted child is safe: the planner resolves that child and then
        // continues with the fixed or already-defined steps. Multiple targeted children still
        // need shared-target bindings and all-targets-illegal resolution rules.
        final long targetedChildren = chain.stream().filter(IntrinsicDrawOutcomeBackend::containsTarget).count();
        if (targetedChildren > 1) {
            return new Outcome.Unresolved<>("Unsupported intrinsic multiple-target sequence");
        }
        return child;
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
        case "PutCounter", "PutCounterAll" -> counter(node);
        case "Pump", "PumpAll" -> pump(node);
        case "Debuff" -> debuff(node);
        case "Animate" -> animate(node);
        case "AnimateAll" -> animateAll(node);
        case "Token" -> token(node);
        case "GainLife", "LoseLife" -> life(node);
        case "Discard" -> discard(node);
        case "Mana" -> mana(node);
        case "ManaReflected" -> manaReflected(node);
        case "DealDamage", "DamageAll" -> damage(node);
        case "Destroy", "ChangeZone" -> removal(node);
        case "Sacrifice" -> sacrifice(node);
        default -> unresolved(node, "Unsupported intrinsic outcome API " + node.api());
        };
    }

    private Outcome<State> draw(final AbilityOutcomeDescription node) {
        final DrawOutcomeDescription draw = DrawOutcomeDescription.parse(node.api(), node.parameters()).orElseThrow();
        final int amount = draw.amount();
        final boolean controller = draw.controller();
        return new Outcome.Atomic<>(node.path(), state -> {
            final int hand = controller ? state.controllerHand() : state.opponentHand();
            final int value = evaluator.evaluateCardDraw(hand, amount, controller);
            return new Outcome.Transition<>((double) value,
                    state.withHands(controller, EffectMath.add(hand, amount)).clearTarget(), node.api());
        });
    }

    private Outcome<State> token(final AbilityOutcomeDescription node) {
        final TokenSpec spec = tokenSpec(node);
        if (spec == null) {
            return unresolved(node, "Unsupported intrinsic token form");
        }
        return new Outcome.Deferred<>(state -> {
            final List<PermanentProfile> profiles = new java.util.ArrayList<>();
            for (final String script : spec.scripts()) {
                final PermanentProfile profile = tokenProfileResolver.apply(script).orElse(null);
                if (profile == null || !isCreature(profile)) {
                    return unresolved(node, "Token definition is unavailable or noncreature");
                }
                profiles.add(withTokenOverrides(withControl(profile, spec.recipientIsController()), node));
            }
            return new Outcome.Atomic<>(node.path(), current -> {
                int value = 0;
                State projected = current;
                for (final PermanentProfile profile : profiles) {
                    final int tokenValue = evaluator.evaluatePermanent(profile);
                    value = EffectMath.add(value, spec.recipientIsController()
                            ? tokenValue : EffectMath.negate(tokenValue));
                    projected = projected.withCreatureCount(spec.recipientIsController(),
                            EffectMath.add(projected.creatureCount(spec.recipientIsController()),
                                    spec.amount()));
                }
                return new Outcome.Transition<>((double) value * spec.amount(),
                        projected.clearTarget(), node.api());
            });
        });
    }

    private Outcome<State> life(final AbilityOutcomeDescription node) {
        final PlayerTarget target = playerTarget(node);
        if (target == null) {
            return unresolved(node, "Unsupported intrinsic life recipient");
        }
        if (target.fixed() != null) {
            return lifeAtomic(node, target.fixed());
        }
        return new Outcome.Deferred<>(state -> new Outcome.Target<>(node.path() + ":target",
                current -> playerCandidates(current, target), State::withTarget,
                lifeAtomic(node, null), true));
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
            final int value = "GainLife".equals(node.api())
                    ? evaluator.evaluateLifeGain(before, amount, controller)
                    : evaluator.evaluateLifeLoss(before, amount, controller);
            final int after = "GainLife".equals(node.api())
                    ? boundedAdd(before, amount) : Math.max(0, before - amount);
            return new Outcome.Transition<>((double) value,
                    current.withLife(controller, after).clearTarget(), node.api());
        });
    }

    private Outcome<State> discard(final AbilityOutcomeDescription node) {
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
        return new Outcome.Atomic<>(node.path(), current -> {
            final TargetRef target = fixedTarget == null ? current.target() : fixedTarget;
            if (!isPlayer(target)) {
                return null;
            }
            final boolean controller = controls(target, current);
            final int hand = controller ? current.controllerHand() : current.opponentHand();
            final String mode = node.parameters().getOrDefault("Mode", "Random");
            final int requested = "Hand".equals(mode) ? hand : integer(node, "NumCards", 0);
            final int value = "TgtChoose".equals(mode)
                    ? evaluator.evaluateChosenDiscard(hand, requested, controller)
                    : evaluator.evaluateRandomDiscard(hand, requested, controller);
            final int discarded = Math.min(hand, Math.max(0, requested));
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
            for (final TargetRef target : targets) {
                final Outcome.Transition<State> transition = damagePlayerTransition(node, projected, target);
                if (transition == null) {
                    return null;
                }
                value = EffectMath.add(value, (int) Math.round(transition.value()));
                projected = transition.state();
            }
            return new Outcome.Transition<>((double) value, projected.clearTarget(), node.api());
        });
    }

    private Outcome<State> damageAtomic(final AbilityOutcomeDescription node,
            final TargetRef fixedTarget) {
        return new Outcome.Atomic<>(node.path(), current -> {
            final TargetRef target = fixedTarget == null ? current.target() : fixedTarget;
            if (isPlayer(target)) {
                return damagePlayerTransition(node, current, target);
            }
            if (!isCreatureTarget(target)) {
                return null;
            }
            final CreatureProfile before = creature(current, target);
            if (before == null || !simpleKeywords(before.keywords())) {
                return null;
            }
            final int amount = integer(node, "NumDmg", 0);
            final boolean lethal = !before.indestructible()
                    && (amount >= before.toughness() || hasKeyword(current.sourcePermanent(), "deathtouch"));
            if (!lethal) {
                // Nonlethal marked damage is not represented in the first intrinsic state slice.
                return new Outcome.Transition<>(0, current.clearTarget(), node.api());
            }
            final boolean controller = controls(target, current);
            final int value = evaluator.evaluateCreatureDelta(before,
                    IntrinsicReferenceModel.CreatureProfile.absent(), controller);
            final boolean friendly = target == TargetRef.CONTROLLER_CREATURE;
            final State projected = current.withCreatures(friendly, CreatureProfile.absent())
                    .withCreatureCount(friendly,
                            Math.max(0, current.creatureCount(friendly) - 1)).clearTarget();
            return new Outcome.Transition<>((double) value, projected, node.api());
        });
    }

    private Outcome.Transition<State> damagePlayerTransition(final AbilityOutcomeDescription node,
            final State current, final TargetRef target) {
        if (!isPlayer(target) || hasKeyword(current.sourcePermanent(), "infect")
                || hasKeyword(current.sourcePermanent(), "toxic")
                || hasKeyword(current.sourcePermanent(), "lifelink")) {
            return null;
        }
        final boolean controller = controls(target, current);
        final int before = controller ? current.controllerLife() : current.opponentLife();
        final int amount = integer(node, "NumDmg", 0);
        final int value = evaluator.evaluatePlayerDamage(before, amount, controller);
        return new Outcome.Transition<>((double) value,
                current.withLife(controller, Math.max(0, before - amount)), node.api());
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
                return null;
            }
            final boolean destroy = "Destroy".equals(node.api());
            if (destroy && hasKeyword(before, "indestructible")) {
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
        return new Outcome.Atomic<>(node.path(), current -> {
            final PermanentProfile source = current.sourcePermanent();
            if (!source.present() || hasKeyword(source, "indestructible")
                    || !"Self".equalsIgnoreCase(node.parameters().getOrDefault("SacValid", "Self"))) {
                return null;
            }
            final int value = evaluator.evaluatePermanentDelta(source, PermanentProfile.absent(), true);
            return new Outcome.Transition<>((double) value,
                    current.withSourcePermanent(PermanentProfile.absent()).clearTarget(), node.api());
        });
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
                || !literalPositive(node, "TokenAmount", 1)
                || !literalNonnegativeOrAbsent(node, "TokenPower")
                || !literalNonnegativeOrAbsent(node, "TokenToughness")) {
            return null;
        }
        return new TokenSpec(scripts, integer(node, "TokenAmount", 1), "You".equals(owner));
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
        if (!oneTarget(node)) {
            return null;
        }
        return switch (validTargets.toLowerCase(Locale.ROOT)) {
        case "player", "players" -> new PlayerTarget(null, true);
        case "you", "player.you" -> new PlayerTarget(TargetRef.CONTROLLER_PLAYER, null);
        case "opponent", "player.opponent" -> new PlayerTarget(TargetRef.OPPONENT_PLAYER, null);
        default -> null;
        };
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
        default -> null;
        };
    }

    private static List<TargetRef> damageCandidates(final State state, final DamageTarget target) {
        return switch (target.scope()) {
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
        };
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
        // TODO: Add noncreature, copied, targeted, token-ability, ETB, and duration semantics.
        return tokenSpec(node) != null;
    }

    private static boolean acceptsLife(final AbilityOutcomeDescription node) {
        // TODO: Add payment, exchange, set-life, replacement, prevention, optional and dynamic forms.
        if (!LIFE_PARAMETERS.containsAll(node.parameters().keySet())
                || !literalPositive(node, "LifeAmount", 0)) {
            return false;
        }
        return playerTarget(node) != null;
    }

    private static boolean acceptsDiscard(final AbilityOutcomeDescription node) {
        // TODO: Add card identity/quality, optional, dynamic, replacement and multiplayer forms.
        if (!DISCARD_PARAMETERS.containsAll(node.parameters().keySet())
                || !Set.of("Random", "TgtChoose", "Hand")
                        .contains(node.parameters().getOrDefault("Mode", "Random"))) {
            return false;
        }
        if (!"Hand".equals(node.parameters().get("Mode"))
                && !literalPositive(node, "NumCards", 0)) {
            return false;
        }
        return playerTarget(node) != null;
    }

    private static boolean acceptsMana(final AbilityOutcomeDescription node) {
        // TODO: Add color restrictions, spending/timing opportunity cost, and conditional mana.
        if (!MANA_PARAMETERS.containsAll(node.parameters().keySet())
                || !Set.of("You", "Opponent").contains(node.parameters().getOrDefault("Defined", "You"))
                || node.parameters().getOrDefault("Produced", "").isBlank()
                || !literalPositive(node, "Amount", 1)) {
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
        return !node.parameters().containsKey("Amount") || literalPositive(node, "Amount", 1);
    }

    private static boolean acceptsDamage(final AbilityOutcomeDescription node) {
        // TODO: Add planeswalkers, combat/prevention/replacement semantics, infect/toxic, and
        // nonlethal marked damage that persists in projected state.
        if (!literalPositive(node, "NumDmg", 0)
                || !"Self".equals(node.parameters().getOrDefault("DamageSource", "Self"))) {
            return false;
        }
        if ("DamageAll".equals(node.api())) {
            return DAMAGE_ALL_PARAMETERS.containsAll(node.parameters().keySet())
                    && !damageAllTargets(node).isEmpty();
        }
        return DAMAGE_PARAMETERS.containsAll(node.parameters().keySet())
                && damageTarget(node) != null;
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

    private static boolean acceptsSacrifice(final AbilityOutcomeDescription node) {
        // Only fixed self-sacrifice is independent of an unknown board's choice and destination.
        if (!SACRIFICE_PARAMETERS.containsAll(node.parameters().keySet())) {
            return false;
        }
        return (!node.parameters().containsKey("Defined")
                        || "Self".equalsIgnoreCase(node.parameters().get("Defined")))
                && (!node.parameters().containsKey("SacValid")
                        || "Self".equalsIgnoreCase(node.parameters().get("SacValid")))
                && (!node.parameters().containsKey("Amount")
                        || "1".equals(node.parameters().get("Amount")));
    }

    private static boolean oneTarget(final AbilityOutcomeDescription node) {
        return "1".equals(node.parameters().getOrDefault("TargetMin", "1"))
                && "1".equals(node.parameters().getOrDefault("TargetMax", "1"));
    }

    private static boolean isPlayer(final TargetRef target) {
        return target == TargetRef.CONTROLLER_PLAYER || target == TargetRef.OPPONENT_PLAYER;
    }

    private static boolean isCreatureTarget(final TargetRef target) {
        return target == TargetRef.CONTROLLER_CREATURE || target == TargetRef.OPPONENT_CREATURE;
    }

    private static CreatureProfile creature(final State state, final TargetRef target) {
        return switch (target) {
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
        if (target.scope() == CounterTargetScope.SELF) {
            return new Outcome.Deferred<>(state -> hasCounterTarget(state, TargetRef.SOURCE, node)
                    ? counterAtomic(node, TargetRef.SOURCE)
                    : unresolved(node, "Self counter recipient is not a modeled permanent"));
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
        // independent recipient count. Subtypes, noncreature recipients, correlated populations,
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
        }

        final PermanentProfile source = projected.sourcePermanent();
        if (!other && isCreature(source) && source.controlledByAi() == controller) {
            final PermanentProfile after = addCounter(source, node);
            value = EffectMath.add(value, evaluator.evaluateCreatureDelta(
                    toCreature(source), toCreature(after), controller));
            projected = projected.withSourcePermanent(after);
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
                return null;
            }
            final PermanentProfile after = addCounter(before, node);
            final int value = "LOYALTY".equalsIgnoreCase(counterType(node))
                    ? evaluator.evaluatePermanentDelta(before, after, controls(target, current))
                    : evaluator.evaluateCreatureDelta(toCreature(before), toCreature(after),
                            controls(target, current));
            return new Outcome.Transition<>((double) value,
                    replacePermanent(current, target, after).clearTarget(), node.api());
        });
    }

    private static boolean acceptsDraw(final AbilityOutcomeDescription node) {
        // TODO: Library exhaustion, optional draws, replacements, targeted players and symbolic
        // amounts require explicit reference state. Unknown semantic fields fail closed here.
        if (!DRAW_PARAMETERS.containsAll(node.parameters().keySet())) {
            return false;
        }
        return DrawOutcomeDescription.parse(node.api(), node.parameters())
                .filter(draw -> draw.amount() >= 0).isPresent();
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
                && Set.of("Permanent", "Perpetual").contains(node.parameters().get("Duration"))
                && !node.parameters().containsKey("AllSuffixKeywords")
                && keywords != null && !keywords.isEmpty() && counterTarget(node) != null;
    }

    private static boolean acceptsAnimate(final AbilityOutcomeDescription node) {
        // The reference profile can model a persistent creature conversion, but not temporary
        // animation, subtype/color changes, or a planeswalker that remains a planeswalker.
        final Set<String> keywords = supportedKeywords(node.parameters().get("Keywords"));
        final String types = node.parameters().get("Types");
        return ANIMATE_PARAMETERS.containsAll(node.parameters().keySet())
                && Set.of("Permanent", "Perpetual").contains(node.parameters().get("Duration"))
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
                && Set.of("Permanent", "Perpetual").contains(node.parameters().get("Duration"))
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
                || !Set.of("Permanent", "Perpetual").contains(node.parameters().get("Duration"))) {
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

    private static boolean hasCreatureType(final String types) {
        return List.of(types.split(",")).stream()
                .map(type -> type.trim()).anyMatch("Creature"::equalsIgnoreCase);
    }

    private static CreatureGroupTarget creatureGroupTarget(final AbilityOutcomeDescription node) {
        final String definition = node.parameters().get("ValidCards");
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

    private static void collectDimensions(final AbilityOutcomeDescription node,
            final Set<String> dimensions, final Set<AbilityOutcomeDescription> visited,
            final int depth) {
        if (node == null) { return; }
        if (depth > MAX_DIMENSION_DEPTH || visited.size() >= 1024) {
            throw new IllegalArgumentException("Intrinsic reference traversal limit exceeded");
        }
        if (!visited.add(node)) { return; }
        if ("Draw".equals(node.api()) && acceptsDraw(node)) {
            final String defined = node.parameters().getOrDefault("Defined", "You");
            if ("You".equalsIgnoreCase(defined)) {
                dimensions.add(CONTROLLER_HAND);
            } else if ("Opponent".equalsIgnoreCase(defined)) {
                dimensions.add(OPPONENT_HAND);
            }
        } else if ("PutCounter".equals(node.api())) {
            final List<AbilityOutcomeDescription> counterNodes = acceptsCounter(node)
                    ? List.of(node) : acceptsCounterChoice(node) ? counterTypes(node).stream()
                            .map(type -> withCounterType(node, type)).toList() : List.of();
            for (final AbilityOutcomeDescription counterNode : counterNodes) {
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
        } else if (("PutCounterAll".equals(node.api()) && acceptsCounterAll(node))
                || ("PumpAll".equals(node.api()) && acceptsPumpAll(node))) {
            final CreatureGroupTarget target = creatureGroupTarget(node);
            if (target.controller()) {
                dimensions.add(CONTROLLER_CREATURE_COUNT);
                dimensions.add(CONTROLLER_CREATURE);
            }
            if (target.opponent()) {
                dimensions.add(OPPONENT_CREATURE_COUNT);
                dimensions.add(OPPONENT_CREATURE);
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
            final CreatureGroupTarget target = creatureGroupTarget(node);
            if (target.controller()) {
                dimensions.add(CONTROLLER_CREATURE_COUNT);
                dimensions.add(CONTROLLER_CREATURE);
            }
            if (target.opponent()) {
                dimensions.add(OPPONENT_CREATURE_COUNT);
                dimensions.add(OPPONENT_CREATURE);
            }
        } else if (("GainLife".equals(node.api()) || "LoseLife".equals(node.api()))
                && acceptsLife(node)) {
            addPlayerDimensions(node, dimensions);
        } else if ("Discard".equals(node.api()) && acceptsDiscard(node)) {
            addHandDimensions(node, dimensions);
        } else if (("DealDamage".equals(node.api()) || "DamageAll".equals(node.api()))
                && acceptsDamage(node)) {
            addDamageDimensions(node, dimensions);
        } else if (("Destroy".equals(node.api()) || "ChangeZone".equals(node.api()))
                && acceptsRemoval(node)) {
            addRemovalDimensions(node, dimensions);
        }
        for (final AbilityOutcomeDescription choice : node.choices()) {
            collectDimensions(choice, dimensions, visited, depth + 1);
        }
        collectDimensions(node.next(), dimensions, visited, depth + 1);
    }

    private static void addPlayerDimensions(final AbilityOutcomeDescription node,
            final Set<String> dimensions) {
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
            return;
        }
        final DamageTarget target = damageTarget(node);
        if (target == null) {
            return;
        }
        switch (target.scope()) {
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
        default -> throw new IllegalStateException("Unhandled damage target scope");
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
            case SELF -> dimensions.add(CONTROLLER_HAND);
            }
        }
    }

    private enum CounterTargetScope {
        SELF, ANY_CREATURE, CONTROLLER_CREATURE, OPPONENT_CREATURE
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
        CONTROLLER_CREATURE, OPPONENT_CREATURE, ANY_CREATURE
    }

    private record DamageTarget(DamageTargetScope scope, TargetRef fixed) { }

    private enum PermanentTargetScope {
        SELF, ANY_CREATURE, CONTROLLER_CREATURE, OPPONENT_CREATURE,
        ANY_PERMANENT, CONTROLLER_PERMANENT, OPPONENT_PERMANENT
    }

    private record PermanentTarget(PermanentTargetScope scope, TargetRef fixed) { }

    private static PermanentTarget permanentTarget(final AbilityOutcomeDescription node) {
        final String defined = node.parameters().get("Defined");
        final String validTargets = node.parameters().get("ValidTgts");
        if (defined != null) {
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
        case "permanent", "permanent.nonland" ->
                new PermanentTarget(PermanentTargetScope.ANY_PERMANENT, null);
        case "permanent.youctrl", "permanent.nonland+youctrl" ->
                new PermanentTarget(PermanentTargetScope.CONTROLLER_PERMANENT, null);
        case "permanent.oppctrl", "permanent.nonland+oppctrl" ->
                new PermanentTarget(PermanentTargetScope.OPPONENT_PERMANENT, null);
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
            if (friendly && canTarget(toPermanent(state.controllerCreature()), true)) {
                result.add(TargetRef.CONTROLLER_CREATURE);
            }
            if (opposing && canTarget(toPermanent(state.opponentCreature()), false)) {
                result.add(TargetRef.OPPONENT_CREATURE);
            }
        }
        if (permanents) {
            if (friendly && canTarget(state.controllerPermanent(), true)) {
                result.add(TargetRef.CONTROLLER_PERMANENT);
            }
            if (opposing && canTarget(state.opponentPermanent(), false)) {
                result.add(TargetRef.OPPONENT_PERMANENT);
            }
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
        case SOURCE -> isCreature(state.sourcePermanent());
        case CONTROLLER_CREATURE -> state.controllerCreature().present();
        case OPPONENT_CREATURE -> state.opponentCreature().present();
        default -> false;
        };
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
        case CONTROLLER_CREATURE, CONTROLLER_PERMANENT, CONTROLLER_PLAYER -> true;
        case OPPONENT_CREATURE, OPPONENT_PERMANENT, OPPONENT_PLAYER -> false;
        case SOURCE -> state.sourcePermanent().controlledByAi();
        };
    }

    private static PermanentProfile permanent(final State state, final TargetRef target) {
        return switch (target) {
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
        case SOURCE -> state.withSourcePermanent(replacement);
        case CONTROLLER_CREATURE -> state.withCreatures(true, toCreature(replacement));
        case OPPONENT_CREATURE -> state.withCreatures(false, toCreature(replacement));
        case CONTROLLER_PERMANENT -> state.withPermanent(true, replacement);
        case OPPONENT_PERMANENT -> state.withPermanent(false, replacement);
        case CONTROLLER_PLAYER, OPPONENT_PLAYER -> state;
        };
    }

    private static boolean isCreature(final PermanentProfile profile) {
        return profile.present() && (profile.kind() == PermanentKind.CREATURE
                || profile.kind() == PermanentKind.TOKEN);
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
