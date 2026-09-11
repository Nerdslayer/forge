package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Compiles inert descriptions to the existing planner, delegating environment-specific leaves. */
public final class OutcomeDescriptionCompiler<S> {
    public interface Backend<S> {
        Outcome<S> atomic(AbilityOutcomeDescription description);
        boolean maximize(AbilityOutcomeDescription node, boolean opponentChooses);
        default boolean acceptsNode(final AbilityOutcomeDescription node) { return true; }
        default String decisionId(final AbilityOutcomeDescription node, final boolean random) { return node.path(); }
        default int amount(final AbilityOutcomeDescription node, final String expression) {
            return Integer.parseInt(expression);
        }
        default Outcome<S> bindTargets(final List<AbilityOutcomeDescription> chain, final Outcome<S> child) {
            return child;
        }
    }

    private final Backend<S> backend;
    public OutcomeDescriptionCompiler(final Backend<S> backend) { this.backend = backend; }

    public Outcome<S> compile(final AbilityOutcomeDescription description) {
        return compile(description, 0);
    }

    private Outcome<S> compile(final AbilityOutcomeDescription description, final int depth) {
        if (depth > 24 || !description.issue().isEmpty()) {
            return unresolved(description.path() + ": " + description.issue());
        }
        final List<Outcome<S>> steps = new ArrayList<>();
        final List<AbilityOutcomeDescription> chain = new ArrayList<>();
        for (AbilityOutcomeDescription node = description; node != null; node = node.next()) {
            if (chain.size() >= 1024) { return unresolved(description.path()); }
            chain.add(node);
            steps.add(node.issue().isEmpty() ? part(node, depth) : unresolved(node.path() + ": " + node.issue()));
        }
        return backend.bindTargets(chain, new Outcome.Sequence<>(steps));
    }

    private Outcome<S> part(final AbilityOutcomeDescription node, final int depth) {
        if (!backend.acceptsNode(node)) { return unresolved(node.path() + ": unsupported backend semantics"); }
        if (!Set.of("Charm", "GenericChoice").contains(node.api())) { return backend.atomic(node); }
        // TODO: Targets across modes and stochastic chains, dynamic counts, optional costs and
        // simultaneous batches require shared binding/timing descriptors before intrinsic use.
        if (!Set.of("DB", "AB", "SP", "Cost", "Choices", "CharmNum", "MinCharmNum", "CanRepeatModes",
                "ChoiceAmount", "Defined", "Chooser", "Random", "AtRandom", "SubAbility",
                "SpellDescription", "StackDescription").containsAll(node.parameters().keySet())
                || node.choices().isEmpty()) { return unresolved(node.path()); }
        final String recipient = node.parameters().getOrDefault("Defined", "You");
        if (!Set.of("You", "Opponent").contains(recipient)
                || node.parameters().containsKey("Chooser") && !"Opponent".equals(node.parameters().get("Chooser"))) {
            return unresolved(node.path());
        }
        final List<Outcome<S>> options = node.choices().stream().map(c -> compile(c, depth + 1)).toList();
        try {
            final int maximum = backend.amount(node, node.parameters().getOrDefault(
                    "Charm".equals(node.api()) ? "CharmNum" : "ChoiceAmount", "1"));
            final int minimum = backend.amount(node, node.parameters().getOrDefault("MinCharmNum", Integer.toString(maximum)));
            if (minimum < 0 || maximum < minimum || maximum > 8) { return unresolved(node.path()); }
            if (node.parameters().containsKey("Random") || node.parameters().containsKey("AtRandom")) {
                if (minimum != maximum || node.parameters().containsKey("CanRepeatModes")
                        || !"True".equalsIgnoreCase(node.parameters().getOrDefault("Random", "True"))
                        || !"True".equalsIgnoreCase(node.parameters().getOrDefault("AtRandom", "True"))) {
                    return unresolved(node.path());
                }
                final List<Outcome.Weighted<S>> branches = new ArrayList<>();
                groups(options, maximum, 0, new ArrayList<>(), branches);
                return branches.isEmpty() ? unresolved(node.path()) : new Outcome.Random<>(backend.decisionId(node, true), branches);
            }
            return new Outcome.Choice<>(backend.decisionId(node, false), options, minimum, maximum,
                    node.parameters().containsKey("CanRepeatModes"), backend.maximize(node,
                            "Opponent".equals(recipient) || node.parameters().containsKey("Chooser")));
        } catch (final IllegalArgumentException unsupported) { return unresolved(node.path()); }
    }

    private void groups(final List<Outcome<S>> options, final int count, final int start,
            final List<Outcome<S>> selected, final List<Outcome.Weighted<S>> branches) {
        if (branches.size() >= 1024) { throw new IllegalArgumentException("Random branch limit"); }
        if (selected.size() == count) {
            branches.add(new Outcome.Weighted<>(new Outcome.Sequence<>(selected), 1));
            return;
        }
        for (int i = start; i < options.size(); i++) {
            selected.add(options.get(i));
            groups(options, count, i + 1, selected, branches);
            selected.remove(selected.size() - 1);
        }
    }

    private Outcome<S> unresolved(final String path) { return new Outcome.Unresolved<>("Unsupported structure: " + path); }
}
