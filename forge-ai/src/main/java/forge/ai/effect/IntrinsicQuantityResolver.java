package forge.ai.effect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntUnaryOperator;

import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;
import forge.ai.effect.IntrinsicReferenceQuantities.Quantity;

/** Bounded, game-free quantity bindings. Unknown expressions remain unsupported, not guessed. */
public final class IntrinsicQuantityResolver {
    public record Binding(String identity, WeightedDistribution<Integer> referenceValues,
            IntUnaryOperator transformation) {
        public Binding(final String identity, final WeightedDistribution<Integer> values) {
            this(identity, values, value -> value);
        }

        public WeightedDistribution<Integer> values() {
            return referenceValues.map(transformation::applyAsInt);
        }

        public int at(final int referenceValue) {
            return transformation.applyAsInt(referenceValue);
        }
    }

    private IntrinsicQuantityResolver() { }

    public static Optional<Binding> resolve(final String expression, final Map<String, String> variables,
            final IntrinsicReferenceModel model, final PermanentProfile source) {
        return resolve(expression, variables, model, source, new java.util.HashSet<>());
    }

    private static Optional<Binding> resolve(final String expression, final Map<String, String> variables,
            final IntrinsicReferenceModel model, final PermanentProfile source, final Set<String> visited) {
        if (expression == null || visited.size() >= 24 || !visited.add(expression)) {
            return Optional.empty();
        }
        if (expression.matches("-?\\d+")) {
            try {
                final int value = Integer.parseInt(expression);
                return Optional.of(new Binding("literal:" + value,
                        WeightedDistribution.of(new WeightedValue<>(value, 1))));
            } catch (final NumberFormatException invalid) { return Optional.empty(); }
        }
        if (variables.containsKey(expression)) {
            return resolve(variables.get(expression), variables, model, source, visited);
        }
        if (expression.startsWith("Number$")) {
            return resolve(expression.substring("Number$".length()), variables, model, source, visited);
        }
        if (expression.startsWith("SVar$")) {
            return resolve(expression.substring("SVar$".length()), variables, model, source, visited);
        }
        final int slash = expression.lastIndexOf('/');
        if (slash > 0) {
            final String operation = expression.substring(slash + 1);
            final int dot = operation.indexOf('.');
            final String operator = dot < 0 ? operation : operation.substring(0, dot);
            final boolean unary = Set.of("Twice", "Thrice", "HalfUp", "HalfDown", "ThirdUp",
                    "ThirdDown", "Negative", "Abs").contains(operator);
            if (dot == 0 || unary && dot >= 0 || !unary && dot < 0) { return Optional.empty(); }
            if (!unary && !Set.of("Plus", "Minus", "NMinus", "Times", "DivideEvenlyUp", "DivideEvenlyDown",
                    "Mod", "LimitMax", "LimitMin").contains(operator)) { return Optional.empty(); }
            final Binding base = resolve(expression.substring(0, slash), variables, model, source,
                    new java.util.HashSet<>(visited)).orElse(null);
            final Binding secondary = unary ? new Binding("literal:0", WeightedDistribution.of(new WeightedValue<>(0, 1)))
                    : resolve(operation.substring(dot + 1), variables, model, source,
                            new java.util.HashSet<>(visited)).orElse(null);
            if (base == null || secondary == null) { return Optional.empty(); }
            final boolean sameQuantity = base.identity().equals(secondary.identity());
            final boolean fixedBase = base.referenceValues().entries().size() == 1;
            final boolean fixedSecondary = secondary.referenceValues().entries().size() == 1;
            if (!sameQuantity && !fixedBase && !fixedSecondary) { return Optional.empty(); }
            final Binding sampled = !sameQuantity && !fixedSecondary ? secondary : base;
            final IntUnaryOperator transform = reference -> {
                final int value = base.at(sameQuantity || !fixedBase ? reference : base.referenceValues().entries().get(0).value());
                final int operand = secondary.at(sameQuantity || !fixedSecondary ? reference : secondary.referenceValues().entries().get(0).value());
                return switch (operator) {
                case "Plus" -> Math.addExact(value, operand);
                case "Minus" -> Math.subtractExact(value, operand);
                case "NMinus" -> Math.subtractExact(operand, value);
                case "Times" -> Math.multiplyExact(value, operand);
                case "Twice" -> Math.multiplyExact(value, 2);
                case "Thrice" -> Math.multiplyExact(value, 3);
                case "HalfUp" -> (int) Math.ceil(value / 2.0);
                case "HalfDown" -> Math.floorDiv(value, 2);
                case "ThirdUp" -> (int) Math.ceil(value / 3.0);
                case "ThirdDown" -> Math.floorDiv(value, 3);
                case "Negative" -> Math.negateExact(value);
                case "Abs" -> value < 0 ? Math.negateExact(value) : value;
                case "LimitMax" -> Math.min(value, operand);
                case "LimitMin" -> Math.max(value, operand);
                case "Mod" -> value % operand;
                case "DivideEvenlyUp" -> operand == 0 ? 0
                        : Math.toIntExact((long) value / operand + (value % operand == 0 ? 0 : 1));
                default -> operand == 0 ? 0 : Math.toIntExact((long) value / operand);
                };
            };
            try {
                sampled.referenceValues().entries().forEach(entry -> transform.applyAsInt(entry.value()));
            } catch (final ArithmeticException overflow) { return Optional.empty(); }
            // Arithmetic changes the quantity's projection, not its identity. X and 2*X must
            // share the same sampled X through every mode and step.
            return Optional.of(new Binding(sampled.identity(), sampled.referenceValues(), transform));
        }
        final Quantity quantity = switch (expression) {
        case "Count$CardCounters.P1P1" -> Quantity.P1P1_COUNTERS;
        case "Count$CardCounters.LEVEL" -> Quantity.LEVEL_COUNTERS;
        case "Count$xPaid" -> Quantity.X_PAID;
        case "Count$YourStartingLife" -> Quantity.STARTING_LIFE;
        case "Count$Valid Land.YouCtrl" -> Quantity.LANDS_CONTROLLED;
        case "Count$ValidGraveyard Card.YouOwn", "Count$ValidGraveyard Card.YouCtrl" -> Quantity.GRAVEYARD_CARDS;
        case "Count$ValidGraveyard Creature.YouOwn", "Count$ValidGraveyard Creature.YouCtrl" -> Quantity.GRAVEYARD_CREATURES;
        case "Count$ValidGraveyard Card.OppOwn", "Count$ValidGraveyard Card.OppCtrl" -> Quantity.OPPONENT_GRAVEYARD_CARDS;
        case "Count$ValidGraveyard Creature.OppOwn", "Count$ValidGraveyard Creature.OppCtrl" -> Quantity.OPPONENT_GRAVEYARD_CREATURES;
        default -> null;
        };
        if (quantity != null) {
            return Optional.of(new Binding(quantity.name(), model.quantities().distribution(quantity)));
        }
        if ("Count$YourLifeTotal".equals(expression)) {
            return Optional.of(new Binding(IntrinsicDrawOutcomeBackend.CONTROLLER_LIFE, model.lifeTotals()));
        }
        if ("Count$OppGreatestLifeTotal".equals(expression)) {
            // The intrinsic environment has one opponent; a multiplayer maximum needs a joint model.
            return Optional.of(new Binding(IntrinsicDrawOutcomeBackend.OPPONENT_LIFE, model.lifeTotals()));
        }
        if (Set.of("Count$ValidHand Card.YouOwn", "Count$ValidHand Card.YouCtrl", "Count$CardsInYourHand").contains(expression)) {
            return Optional.of(new Binding(IntrinsicDrawOutcomeBackend.CONTROLLER_HAND, model.handSizes()));
        }
        if (Set.of("Count$ValidHand Card.OppOwn", "Count$ValidHand Card.OppCtrl").contains(expression)) {
            return Optional.of(new Binding(IntrinsicDrawOutcomeBackend.OPPONENT_HAND, model.handSizes()));
        }
        final IntrinsicCounterPredicates.Filter selfCounters = expression.startsWith("Count$Valid ")
                ? IntrinsicCounterPredicates.parse(expression.substring("Count$Valid ".length())).orElse(null) : null;
        if (selfCounters != null && Set.of("Card.Self", "Creature.Self").contains(selfCounters.base())) {
            final boolean creatureRequired = "Creature.Self".equals(selfCounters.base());
            final boolean matchesSource = source.present() && (!creatureRequired
                    || source.kind() == IntrinsicReferenceModel.PermanentKind.CREATURE
                    || source.kind() == IntrinsicReferenceModel.PermanentKind.TOKEN);
            return Optional.of(new Binding(selfCounters.quantity().name(), model.quantities().distribution(selfCounters.quantity()),
                    count -> matchesSource && selfCounters.matches().test(count) ? 1 : 0));
        }
        if ("Count$Valid Card.Self".equals(expression) || "Count$Valid Creature.Self".equals(expression)) {
            final boolean matches = source.present() && (expression.contains("Card.Self")
                    || source.kind() == IntrinsicReferenceModel.PermanentKind.CREATURE
                    || source.kind() == IntrinsicReferenceModel.PermanentKind.TOKEN);
            return Optional.of(new Binding("presentSource", WeightedDistribution.of(new WeightedValue<>(matches ? 1 : 0, 1))));
        }
        if ("Count$Valid Creature.YouCtrl".equals(expression)) {
            // The reference population is OTHER friendly creatures; the source contributes one
            // only if it is a creature. Preserve this convention rather than silently shifting
            // existing recipient-count distributions elsewhere.
            final boolean creature = source.kind() == IntrinsicReferenceModel.PermanentKind.CREATURE
                    || source.kind() == IntrinsicReferenceModel.PermanentKind.TOKEN;
            return Optional.of(new Binding("otherFriendlyCreatures", model.friendlyCreatureCounts(),
                    count -> count + (creature && source.present() ? 1 : 0)));
        }
        if ("Count$Valid Creature.Other+YouCtrl".equals(expression)) {
            return Optional.of(new Binding("otherFriendlyCreatures", model.friendlyCreatureCounts()));
        }
        if ("Count$Valid Creature.OppCtrl".equals(expression)) {
            return Optional.of(new Binding("opposingCreatures", model.opposingCreatureCounts()));
        }
        if ("Count$CardPower".equals(expression) || "Count$CardToughness".equals(expression)) {
            final int value = expression.endsWith("CardPower") ? source.power() : source.toughness();
            return Optional.of(new Binding(expression, WeightedDistribution.of(new WeightedValue<>(value, 1))));
        }
        // TODO: Independent multi-quantity operands, resolution-time mutable quantities, other Forge
        // arithmetic, typed populations, target/event/LKI quantities and correlations with
        // entry counters/X paid need explicit bindings; never reinterpret them as source values.
        return Optional.empty();
    }
}
