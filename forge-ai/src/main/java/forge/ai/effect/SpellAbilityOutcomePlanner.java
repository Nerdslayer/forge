package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import forge.game.GameEntity;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;

/** Adapts Forge's parsed abilities and target references into the shared outcome algebra. */
public final class SpellAbilityOutcomePlanner {
    private static final Set<String> MODAL_PARAMS = Set.of(
            "DB", "AB", "SP", "Cost", "Choices", "CharmNum", "MinCharmNum", "CanRepeatModes",
            "ChoiceAmount", "Defined", "Chooser", "Random", "AtRandom", "SpellDescription", "StackDescription", "SubAbility");
    private static final Set<String> SHARED_PLAYERS = Set.of(
            "Targeted", "TargetedPlayer", "ThisTargetedPlayer", "ParentTarget", "TargetedController");
    private static final Set<String> TARGET_PARAMS = Set.of("TargetMin", "TargetMax", "TargetUnique",
            "TargetsWithDefinedController", "TargetsWithSameController", "TargetsWithDifferentControllers",
            "TargetsWithDifferentCMC", "TargetsWithDifferentNames", "TargetsWithEqualToughness",
            "TargetsWithSameCreatureType", "TargetsWithoutSameCreatureType", "TargetsWithSameCardType",
            "MaxTotalTargetCMC", "MaxTotalTargetPower");

    // TODO(effect analysis): Adapt cost-bearing/restricted modes, secret/simultaneous choices,
    // divided targets, stack/hidden-zone targets and cross-mode targeting restrictions. The core
    // supports explicit binding constraints and weighted randomness; these script forms require
    // adapters that preserve their timing and probability rules. No live AI decisions are mutated.
    private SpellAbilityOutcomePlanner() { }

    public static OutcomePlan<OutcomeState> evaluate(final SpellAbility ability,
            final Player evaluatingAi) {
        return evaluate(ability, evaluatingAi, null);
    }

    static OutcomePlan<OutcomeState> evaluate(final SpellAbility ability,
            final Player evaluatingAi, final EffectEvent event) {
        final OutcomeState state = new OutcomeState();
        try {
            if (!supports(ability)) { return OutcomePlan.unsupported(state, "Unsupported ability form"); }
            return new OutcomePlanner<OutcomeState>().evaluate(compile(ability, evaluatingAi, event, 0), state);
        } catch (final RuntimeException unsupported) {
            return OutcomePlan.unsupported(state, unsupported.getMessage());
        }
    }

    static boolean supports(final SpellAbility ability) {
        try {
            return supports(ability, 0);
        } catch (final RuntimeException unsupported) {
            return false;
        }
    }

    private static boolean supports(final SpellAbility ability, final int depth) {
        if (ability == null || depth > 24 || ability.getActivatingPlayer() == null) { return false; }
        // TODO: Hoist every announcement-time target across stochastic modal/subability chains.
        // Until then, reject ambiguous timing instead of letting a target see future randomness.
        if (ability.getSubAbility() != null && contains(ability, true, 0) && contains(ability, false, 0)) {
            return false;
        }
        if (ability.usesTargeting() && (!simpleTargets(ability)
                || ability.hasParam("TargetsAtRandom"))) { return false; }
        if (modal(ability)) {
            if (!MODAL_PARAMS.containsAll(ability.getMapParams().keySet())
                    || (ability.hasParam("Random") && !"True".equalsIgnoreCase(ability.getParam("Random")))
                    || (ability.hasParam("AtRandom") && !"True".equalsIgnoreCase(ability.getParam("AtRandom")))
                    || !Set.of("You", "Opponent").contains(ability.getParamOrDefault("Defined", "You"))
                    || (ability.hasParam("Chooser") && !"Opponent".equals(ability.getParam("Chooser")))) { return false; }
            if ((ability.hasParam("Chooser") || "Opponent".equals(ability.getParam("Defined")))
                    && ability.getActivatingPlayer().getOpponents().size() != 1) { return false; }
            final List<AbilitySub> options = ability.getAdditionalAbilityList("Choices");
            if (options == null || options.isEmpty()) { return false; }
            for (final SpellAbility option : options) {
                if (!supports(option, depth + 1) || crossModeReference(option, 0)) { return false; }
            }
            final int maximum = AbilityUtils.calculateAmount(ability.getHostCard(),
                    ability.getParamOrDefault(ability.getApi() == ApiType.Charm ? "CharmNum" : "ChoiceAmount", "1"), ability);
            if (maximum > 1 && options.stream().anyMatch(o -> contains(o, true, 0))
                    && options.stream().anyMatch(o -> contains(o, false, 0))) { return false; }
        } else if (counterChoice(ability)) {
            for (final String type : ability.getParam("CounterType").split(",")) {
                final SpellAbility part = leaf(ability);
                part.putParam("CounterType", type.trim());
                if (OutcomeEvaluatorRegistry.findAtomic(part) == null) { return false; }
            }
        } else if (OutcomeEvaluatorRegistry.findAtomic(leaf(ability)) == null) {
            return false;
        }
        return ability.getSubAbility() == null || supports(ability.getSubAbility(), depth + 1);
    }

    private static boolean crossModeReference(final SpellAbility ability, final int depth) {
        if (ability == null || depth > 24) { return false; }
        // Forge chains selected modes together at announcement. Global Targeted definitions and
        // uniqueness can therefore see other modes. ParentTarget/ThisTargeted references keep
        // their local ownership; global cross-mode bindings need a dedicated adapter (TODO).
        if (ability.hasParam("TargetUnique") || ability.hasParam("TargetsWithDefinedController")
                || ability.getMapParams().values().stream().anyMatch(v -> v.startsWith("Targeted"))) {
            return true;
        }
        return crossModeReference(ability.getSubAbility(), depth + 1);
    }

    private static boolean contains(final SpellAbility ability, final boolean random, final int depth) {
        if (ability == null || depth > 24) { return false; }
        if (random ? ability.hasParam("Random") || ability.hasParam("AtRandom")
                || "Random".equals(ability.getParam("Mode")) : ability.usesTargeting()) { return true; }
        if (contains(ability.getSubAbility(), random, depth + 1)) { return true; }
        final List<AbilitySub> choices = ability.getAdditionalAbilityList("Choices");
        return choices != null && choices.stream().anyMatch(c -> contains(c, random, depth + 1));
    }

    private static boolean simpleTargets(final SpellAbility ability) {
        if (ability.getMinTargets() < 0 || ability.getMaxTargets() > 4
                || ability.getMinTargets() > ability.getMaxTargets()) { return false; }
        for (final String param : ability.getMapParams().keySet()) {
            if ((param.startsWith("Target") && !TARGET_PARAMS.contains(param))
                    || param.startsWith("Divid")) { return false; }
        }
        return ability.getTargetRestrictions().getZone().stream().allMatch(z -> z == ZoneType.Battlefield);
    }

    private static boolean modal(final SpellAbility ability) {
        return ability.getApi() == ApiType.Charm || ability.getApi() == ApiType.GenericChoice;
    }

    private static boolean counterChoice(final SpellAbility ability) {
        return ability.getApi() == ApiType.PutCounter && ability.hasParam("CounterType")
                && ability.getParam("CounterType").contains(",");
    }

    private static Outcome<OutcomeState> compile(final SpellAbility first, final Player ai,
            final EffectEvent event, final int depth) {
        final java.util.Map<String, SpellAbility> bindings = new java.util.HashMap<>();
        final AbilityOutcomeDescription description = AbilityOutcomeParser.parse(first, "root", bindings);
        return new OutcomeDescriptionCompiler<>(new OutcomeDescriptionCompiler.Backend<OutcomeState>() {
            @Override
            public Outcome<OutcomeState> atomic(final AbilityOutcomeDescription node) {
                return part(bindings.get(node.path()), ai, event, depth);
            }

            @Override
            public boolean maximize(final AbilityOutcomeDescription node, final boolean opponentChooses) {
                final Player controller = bindings.get(node.path()).getActivatingPlayer();
                return (opponentChooses ? controller.getOpponents().get(0) : controller).isOpponentOf(ai);
            }

            @Override
            public String decisionId(final AbilityOutcomeDescription node, final boolean random) {
                return (random ? "random:" : "choice:") + bindings.get(node.path()).getId();
            }

            @Override
            public int amount(final AbilityOutcomeDescription node, final String expression) {
                final SpellAbility ability = bindings.get(node.path());
                return AbilityUtils.calculateAmount(ability.getHostCard(), expression, ability);
            }

            @Override
            public Outcome<OutcomeState> bindTargets(final List<AbilityOutcomeDescription> chain,
                    final Outcome<OutcomeState> child) {
                Outcome<OutcomeState> result = child;
                for (int i = chain.size() - 1; i >= 0; i--) {
                    final SpellAbility owner = bindings.get(chain.get(i).path());
                    if (owner != null && owner.usesTargeting()) {
                        result = new Outcome.Target<>("target:" + owner.getId(),
                                state -> targets(owner, state, event), (state, selected) -> state.bind(owner, selected),
                                result, owner.getActivatingPlayer().isOpponentOf(ai));
                    }
                }
                return result;
            }
        }).compile(description);
    }

    private static Outcome<OutcomeState> part(final SpellAbility ability, final Player ai,
            final EffectEvent event, final int depth) {
        if (ability.getApi() == ApiType.Sacrifice
                && !"Self".equals(ability.getParamOrDefault("SacValid", "Self"))) {
            return new Outcome.Deferred<>(state -> knownDependencies(ability, state)
                    ? sacrifice(ability, ai, event, state) : new Outcome.Atomic<>(s -> null));
        }

        if (counterChoice(ability)) {
            final List<Outcome<OutcomeState>> options = new ArrayList<>();
            for (final String type : ability.getParam("CounterType").split(",")) {
                options.add(atomic(ability, ai, event, type.trim()));
            }
            return new Outcome.Choice<>("counter:" + ability.getId(), options, 1, 1,
                    false, ability.getActivatingPlayer().isOpponentOf(ai));
        }
        return atomic(ability, ai, event, null);
    }

    private static Outcome<OutcomeState> sacrifice(final SpellAbility ability, final Player ai,
            final EffectEvent event, final OutcomeState initial) {
        final SpellAbility bound = boundCopy(ability, initial);
        final List<Player> affected = PlayerRecipientResolver.resolve(bound, new OutcomeEvaluationContext(ai, event, initial));
        final forge.game.Game game = ai.getGame();
        final List<Player> players = new ArrayList<>(game.getPhaseHandler().getPlayerTurn() == null
                ? game.getPlayersInTurnOrder() : game.getPlayersInTurnOrder(game.getPhaseHandler().getPlayerTurn()));
        players.removeIf(player -> !affected.contains(player));
        final List<java.util.function.Function<OutcomeState, List<forge.game.card.Card>>> preparations = new ArrayList<>();
        for (final Player player : players) {
            final String slot = "sacrifice:" + ability.getId() + ":" + player.getId();
            preparations.add(state -> state.sacrifices.get(slot));
        }
        // TODO(effect analysis): Shared-team choice ordering, replacements and death triggers.
        // Ordinary APNAP choices happen before any departure; the batch has one board delta.
        Outcome<OutcomeState> result = new Outcome.Batch<>("Simultaneous sacrifice " + ability.getMapParams(),
                preparations, (state, groups) -> {
                    final java.util.Map<forge.game.card.Card, forge.game.card.Card> changes = new java.util.LinkedHashMap<>();
                    for (final List<forge.game.card.Card> group : groups) {
                        for (final forge.game.card.Card selected : group) {
                            final forge.game.card.Card card = state.card(selected);
                            if (card != null) { changes.put(card, null); }
                        }
                    }
                    final OutcomeState next = state.copy();
                    final int value = CardStateDeltaEvaluator.evaluateBoardChanges(
                            new OutcomeEvaluationContext(ai, event, next), changes);
                    return new Outcome.Transition<>((double) value, next, bound);
                });
        for (int i = players.size() - 1; i >= 0; i--) {
            final Player player = players.get(i);
            final String slot = "sacrifice:" + ability.getId() + ":" + player.getId();
            result = new Outcome.Target<>(slot,
                    state -> SacrificeOutcomeEvaluator.choices(bound, player, state), (state, selected) -> {
                        final OutcomeState next = state.copy();
                        next.sacrifices.put(slot, selected);
                        return next;
                    }, result, player.isOpponentOf(ai));
        }
        return result;
    }

    private static Outcome<OutcomeState> atomic(final SpellAbility ability, final Player ai,
            final EffectEvent event, final String counterType) {
        final String description = ability.getApi() + " " + ability.getMapParams()
                + (counterType == null ? "" : " selectedCounter=" + counterType);
        return new Outcome.Atomic<>(description, state -> {
            final OutcomeState next = state.copy();
            if (!knownDependencies(ability, next)) { return null; }
            final SpellAbility copy = boundCopy(ability, next);
            final forge.game.card.Card projectedSource = next.card(ability.getHostCard());
            if (projectedSource != null) { copy.setHostCard(projectedSource); }
            normalize(copy);
            if (counterType != null) { copy.putParam("CounterType", counterType); }
            final OutcomeEvaluator evaluator = OutcomeEvaluatorRegistry.findAtomic(copy);
            if (evaluator == null) { return null; }
            final int value = evaluator.evaluateOutcome(copy, new OutcomeEvaluationContext(ai, event, next));
            if (next.unsupported) { return null; }
            if (ability.getMapParams().keySet().stream().anyMatch(p -> p.startsWith("Remember") || p.startsWith("Imprint"))) {
                next.unprojectedBindings = true;
            }
            return new Outcome.Transition<>((double) value, next, copy);
        });
    }

    private static boolean knownDependencies(final SpellAbility ability, final OutcomeState state) {
        // TODO: Project remembered/imprinted objects and evaluate dynamic expressions against
        // the overlay. Never silently read their stale live-game values after earlier effects.
        if (state.unprojectedBindings && ability.getMapParams().values().stream().anyMatch(v ->
                v.contains("Remembered") || v.contains("Imprinted") || v.contains("Chosen"))) { return false; }
        if (state.cards.isEmpty() && state.createdTokens.isEmpty() && state.life.isEmpty() && state.hands.isEmpty() && !state.unprojectedBoard) {
            return true;
        }
        for (final String name : List.of("NumCards", "LifeAmount", "NumDmg", "CounterNum", "NumAtt", "NumDef", "Amount",
                "TokenAmount", "NumCopies")) {
            if (ability.hasParam(name)) {
                final String value = ability.getParam(name);
                if (!value.matches("-?\\d+") && !Set.of("AFLifeLost", "Double", "Triple").contains(value)) { return false; }
            }
        }
        return true;
    }



    private static SpellAbility leaf(final SpellAbility original) {
        final SpellAbility copy = original.copy(original.getHostCard(), false);
        copy.setSubAbility(null);
        normalize(copy);
        return copy;
    }

    private static void normalize(final SpellAbility copy) {
        copy.removeParam("SubAbility");
        if (copy.usesTargeting()) {
            // Group cardinality is enforced once by the planner. Atomic evaluators receive the
            // complete bound group and never select recipients themselves in a planned context.
            final java.util.Map<String, String> params = new java.util.HashMap<>(copy.getMapParams());
            params.put("TargetMin", "1");
            params.put("TargetMax", "1");
            copy.setTargetRestrictions(new TargetRestrictions(params));
            TARGET_PARAMS.forEach(copy::removeParam);
        }
        if (copy.hasParam("AB") || copy.hasParam("SP")) {
            copy.putParam("DB", copy.getApi().name());
            copy.removeParam("AB");
            copy.removeParam("SP");
            copy.removeParam("Cost");
        }
    }

    private static SpellAbility boundCopy(final SpellAbility original, final OutcomeState state) {
        final SpellAbility copy = original.copy(original.getHostCard(), false);
        copy.setSubAbility(null);
        if (original instanceof AbilitySub sub && sub.getParent() != null) {
            final SpellAbility parent = boundCopy(sub.getParent(), state);
            parent.setSubAbility((AbilitySub) copy);
        }
        if (original.usesTargeting()) {
            copy.resetTargets();
            for (final GameEntity target : state.targets.getOrDefault(original, List.of())) {
                copy.getTargets().add(target);
            }
        }
        return copy;
    }

    private static List<List<GameEntity>> targets(final SpellAbility owner, final OutcomeState state,
            final EffectEvent event) {
        final List<GameEntity> candidates = new ArrayList<>();
        candidates.addAll(owner.getHostCard().getGame().getPlayers());
        candidates.addAll(owner.getHostCard().getGame().getCardsIn(ZoneType.Battlefield));
        if (event != null) {
            for (final EffectEvent.Subject subject : event.subjects()) {
                if (subject.value() instanceof forge.game.card.Card card && !candidates.contains(card)) {
                    candidates.add(card);
                }
            }
        }
        final List<List<GameEntity>> result = new ArrayList<>();
        targetGroups(owner, state, candidates, 0, new ArrayList<>(), result);
        return result;
    }

    private static void targetGroups(final SpellAbility owner, final OutcomeState state,
            final List<GameEntity> candidates, final int start, final List<GameEntity> selected,
            final List<List<GameEntity>> result) {
        if (result.size() > 1024) { throw new IllegalArgumentException("Target group limit exceeded"); }
        if (selected.size() >= owner.getMinTargets()) { result.add(List.copyOf(selected)); }
        if (selected.size() == owner.getMaxTargets()) { return; }
        final SpellAbility check = boundCopy(owner, state.bind(owner, selected));
        for (int i = start; i < candidates.size(); i++) {
            final GameEntity candidate = candidates.get(i);
            if (!check.canTarget(candidate)) { continue; }
            selected.add(candidate);
            targetGroups(owner, state, candidates, i + 1, selected, result);
            selected.remove(selected.size() - 1);
        }
    }

    static boolean sharedPlayer(final SpellAbility ability) {
        return SHARED_PLAYERS.contains(ability.getParamOrDefault("Defined", ""));
    }
}
