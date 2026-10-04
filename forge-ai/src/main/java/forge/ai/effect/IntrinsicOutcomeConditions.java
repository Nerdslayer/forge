package forge.ai.effect;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import forge.ai.effect.IntrinsicDrawOutcomeBackend.State;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;

/** Resolution-time predicates read projected state; unknown condition fields are never stripped. */
final class IntrinsicOutcomeConditions {
    record Description(AbilityOutcomeDescription node, Predicate<State> predicate, Set<String> dimensions) { }

    private IntrinsicOutcomeConditions() { }

    static Description describe(final AbilityOutcomeDescription node) {
        if (node.parameters().keySet().stream().anyMatch(field -> field.startsWith("OrCondition") || field.startsWith("OrOtherCondition"))) {
            // A false recognized arm does not prove an OR expression false.
            return new Description(node, null, Set.of());
        }
        final var remaining = new LinkedHashMap<>(node.parameters());
        final Set<String> dimensions = new LinkedHashSet<>();
        Predicate<State> predicate = null;
        final Boolean fixed = IntrinsicAbilityConditions.compare(remaining.get("ConditionCheckSVar"),
                remaining.getOrDefault("ConditionSVarCompare", "GE1"));
        if (fixed != null) {
            predicate = state -> fixed;
            remaining.remove("ConditionCheckSVar");
            remaining.remove("ConditionSVarCompare");
        }
        final String sourceQuantity = remaining.get("ConditionCheckSVar");
        final String sourceComparison = remaining.getOrDefault("ConditionSVarCompare", "GE1");
        if (sourceQuantity != null && Set.of("Count$CardPower", "Count$CardToughness").contains(sourceQuantity)
                && IntrinsicAbilityConditions.compare("0", sourceComparison) != null) {
            final boolean power = sourceQuantity.endsWith("CardPower");
            predicate = state -> state.sourcePermanent().present() && Boolean.TRUE.equals(IntrinsicAbilityConditions.compare(
                    Integer.toString(power ? state.sourcePermanent().power() : state.sourcePermanent().toughness()), sourceComparison));
            remaining.remove("ConditionCheckSVar");
            remaining.remove("ConditionSVarCompare");
        }
        final String filter = remaining.get("ConditionPresent");
        final String comparison = remaining.getOrDefault("ConditionCompare", "GE1");
        final String defined = remaining.get("ConditionDefined");
        final String zone = remaining.getOrDefault("ConditionZone", "Battlefield");
        ToIntFunction<State> count = null;
        if (filter != null && IntrinsicAbilityConditions.compare("0", comparison) != null) {
            if ("Hand".equals(zone) && defined == null) {
                if (Set.of("Card.YouCtrl", "Card.YouOwn").contains(filter)) {
                    count = State::controllerHand;
                    dimensions.add(IntrinsicDrawOutcomeBackend.CONTROLLER_HAND);
                } else if (Set.of("Card.OppCtrl", "Card.OppOwn").contains(filter)) {
                    count = State::opponentHand;
                    dimensions.add(IntrinsicDrawOutcomeBackend.OPPONENT_HAND);
                }
            } else if ("Battlefield".equals(zone)) {
                if ((defined == null && Set.of("Card.Self", "Creature.Self").contains(filter))
                        || "Self".equals(defined) && Set.of("Card", "Creature").contains(filter)) {
                    final boolean creature = filter.startsWith("Creature");
                    count = state -> state.sourcePermanent().present() && (!creature || sourceIsCreature(state)) ? 1 : 0;
                } else if (defined == null && "Creature.YouCtrl".equals(filter)) {
                    count = state -> state.controllerCreatureCount() + (state.sourcePermanent().present()
                            && state.sourcePermanent().controlledByAi() && sourceIsCreature(state) ? 1 : 0);
                    dimensions.add(IntrinsicDrawOutcomeBackend.CONTROLLER_CREATURE_COUNT);
                } else if (defined == null && "Creature.Other+YouCtrl".equals(filter)) {
                    count = State::controllerCreatureCount;
                    dimensions.add(IntrinsicDrawOutcomeBackend.CONTROLLER_CREATURE_COUNT);
                } else if (defined == null && "Creature.OppCtrl".equals(filter)) {
                    count = state -> state.opponentCreatureCount() + (state.sourcePermanent().present()
                            && !state.sourcePermanent().controlledByAi() && sourceIsCreature(state) ? 1 : 0);
                    dimensions.add(IntrinsicDrawOutcomeBackend.OPPONENT_CREATURE_COUNT);
                }
            }
        }
        if (count != null) {
            final ToIntFunction<State> quantity = count;
            final Predicate<State> presence = state -> Boolean.TRUE.equals(
                    IntrinsicAbilityConditions.compare(Integer.toString(quantity.applyAsInt(state)), comparison));
            predicate = predicate == null ? presence : predicate.and(presence);
            for (final String field : Set.of("ConditionPresent", "ConditionCompare", "ConditionDefined", "ConditionZone")) {
                remaining.remove(field);
            }
        }
        final String lifeRecipient = remaining.get("ConditionLifeTotal");
        final String lifeComparison = remaining.get("ConditionLifeAmount");
        if (lifeRecipient != null && Set.of("You", "Opponent").contains(lifeRecipient)
                && IntrinsicAbilityConditions.compare("0", lifeComparison) != null) {
            final boolean controller = "You".equals(lifeRecipient);
            final Predicate<State> life = state -> Boolean.TRUE.equals(IntrinsicAbilityConditions.compare(
                    Integer.toString(controller ? state.controllerLife() : state.opponentLife()), lifeComparison));
            predicate = predicate == null ? life : predicate.and(life);
            dimensions.add(controller ? IntrinsicDrawOutcomeBackend.CONTROLLER_LIFE : IntrinsicDrawOutcomeBackend.OPPONENT_LIFE);
            remaining.remove("ConditionLifeTotal");
            remaining.remove("ConditionLifeAmount");
        }
        // TODO: Graveyard inventories, targeted/remembered objects, mutable SVar expressions,
        // compound conditions, history and event-dependent comparisons need explicit state bindings.
        return new Description(new AbilityOutcomeDescription(node.path(), node.api(), remaining,
                node.choices(), node.next(), node.issue()), predicate, Set.copyOf(dimensions));
    }

    private static boolean sourceIsCreature(final State state) {
        return state.sourcePermanent().kind() == PermanentKind.CREATURE || state.sourcePermanent().kind() == PermanentKind.TOKEN;
    }
}
