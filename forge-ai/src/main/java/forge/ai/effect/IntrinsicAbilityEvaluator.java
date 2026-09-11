package forge.ai.effect;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import forge.card.CardStateName;
import forge.game.card.CardState;
import forge.item.IPaperCard;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;
import forge.ai.effect.CardAbilityTraversal.AbilityDescription;
import forge.ai.effect.IntrinsicDrawOutcomeBackend.State;

/** Evaluates prepared definition descriptions without constructing or querying a live game. */
public final class IntrinsicAbilityEvaluator {
    private final IntrinsicReferenceModel model;
    private final IntrinsicEvaluationSettings settings;
    public record AbilityValue(String path, double expectedOccurrences,
            IntrinsicReferenceAggregate contribution) { }

    public IntrinsicAbilityEvaluator(final IntrinsicReferenceModel model, final IntrinsicEvaluationSettings settings) {
        this.model = model;
        this.settings = settings;
    }

    /** Game-free public entry point for a selected definition face. Results are per ability. */
    public List<AbilityValue> evaluateDefinition(final IPaperCard definition, final CardStateName face) {
        final CardState state = CardAbilityTraversal.definitionState(definition, face);
        final forge.card.CardTypeView type = state.getType();
        final PermanentKind kind = type.isAura() ? PermanentKind.AURA : type.isCreature() ? PermanentKind.CREATURE
                : type.isPlaneswalker() ? PermanentKind.PLANESWALKER : type.isArtifact() ? PermanentKind.ARTIFACT
                : type.isEnchantment() ? PermanentKind.ENCHANTMENT : type.isLand() ? PermanentKind.LAND : PermanentKind.PERMANENT;
        final Set<String> keywords = state.getIntrinsicKeywords().stream().map(k -> k.getOriginal()).collect(Collectors.toSet());
        // TODO: Variable characteristic values and nonpermanent origins need dedicated models.
        if (type.isCreature() && (!state.getBasePowerString().matches("\\d+")
                || !state.getBaseToughnessString().matches("\\d+"))) {
            throw new IllegalArgumentException("Variable creature characteristics require a reference profile");
        }
        final PermanentProfile profile = new PermanentProfile(true, kind, true, Math.max(0, state.getBasePower()),
                Math.max(0, state.getBaseToughness()), keywords, type.isBasicLand());
        return evaluate(CardAbilityTraversal.inspect(state), profile,
                keywords.stream().anyMatch("Flash"::equalsIgnoreCase) ? EntryTiming.FLASH_LATE_TURN : EntryTiming.NORMAL_SPEED);
    }

    /** Unsupported origins remain in results; callers must check aggregate completeness. */
    public List<AbilityValue> evaluate(final List<AbilityDescription> abilities,
            final IntrinsicReferenceModel.PermanentProfile source, final EntryTiming timing) {
        return abilities.stream().map(a -> evaluate(a, source, timing)).toList();
    }

    private AbilityValue evaluate(final AbilityDescription ability,
            final IntrinsicReferenceModel.PermanentProfile source, final EntryTiming timing) {
        final ScheduledTriggerParser.Schedule schedule = ScheduledTriggerParser.parse(ability.parameters()).orElse(null);
        // TODO: Other origins, conditional and non-battlefield triggers, and granted abilities.
        if (ability.origin() != CardAbilityTraversal.Origin.TRIGGER || schedule == null
                || ability.provenance() == CardAbilityTraversal.Provenance.GRANTED
                || !"Battlefield".equals(ability.parameters().getOrDefault("TriggerZones", "Battlefield"))) {
            return new AbilityValue(ability.path(), 0, new IntrinsicReferenceAggregate(0, 0, 0, 0, 1, 0,
                    List.of(ability.path() + ": unsupported intrinsic origin/zone")));
        }
        final IntrinsicScheduledTrigger trigger = new IntrinsicScheduledTrigger(
                IntrinsicScheduledTrigger.Schedule.valueOf(schedule.timing().name()),
                IntrinsicScheduledTrigger.PlayerScope.valueOf(schedule.playerScope().name()));
        final double occurrences = IntrinsicScheduledTriggerEstimator.estimate(trigger, source, model, settings, timing)
                .expectedOccurrences();
        final Outcome<State> outcome = new OutcomeDescriptionCompiler<>(new IntrinsicDrawOutcomeBackend(settings))
                .compile(ability.outcome());
        final List<ReferenceCase> cases = ReferenceCaseCombiner.combine(List.of(
                new ReferenceDimension("controllerHand", model.handSizes()),
                new ReferenceDimension("opponentHand", model.handSizes())));
        final IntrinsicReferenceAggregate aggregate = IntrinsicReferenceAggregator.aggregate(cases, reference -> {
            final State state = new State(reference.value("controllerHand", Integer.class),
                    reference.value("opponentHand", Integer.class));
            final OutcomePlan<State> plan = new OutcomePlanner<State>().evaluate(outcome, state);
            // Repeated uses share a per-resolution expectation, not projected later hand sizes.
            return new OutcomePlan<>(plan.value() * occurrences, plan.state(), plan.decisions(), plan.branches(),
                    plan.supported(), plan.reason(), plan.completeness(), plan.unresolvedProbability(),
                    plan.unresolvedAlternatives());
        });
        return new AbilityValue(ability.path(), occurrences, aggregate);
    }
}
