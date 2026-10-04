package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import forge.ai.PlayerResourceValueEvaluator;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;
import forge.card.ICardFace;
import forge.game.ability.AbilityFactory;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTap;

/** Bounded option value of a noncreature resource token consumed by one activation. */
final class IntrinsicConsumableAbilityEvaluator {
    private static final Set<String> PARAMETERS = Set.of("AB", "Cost", "Defined", "Produced",
            "Amount", "NumCards", "LifeAmount", "SpellDescription", "Secondary");
    private record Action(String api, int amount, int manaCost, boolean tapCost) { }
    private record Option(Action action, double readyOpportunity, double tappedOpportunity) { }

    private IntrinsicConsumableAbilityEvaluator() { }

    static Optional<IntrinsicTokenResolver.ResourceValue> evaluate(final ICardFace face, final IntrinsicReferenceModel model,
            final IntrinsicEvaluationSettings settings) {
        if (!face.getType().isArtifact() || face.getType().isCreature() || face.getType().isLand()
                || face.getKeywords().iterator().hasNext() || face.getTriggers().iterator().hasNext()
                || face.getStaticAbilities().iterator().hasNext() || face.getReplacements().iterator().hasNext()) {
            return Optional.empty();
        }
        final List<Action> actions = new ArrayList<>();
        for (final String script : face.getAbilities()) {
            final Action action = action(script);
            if (action == null) { return Optional.empty(); }
            actions.add(action);
        }
        if (actions.isEmpty()) { return Optional.empty(); }
        final IntrinsicOutcomeEvaluator utility = new IntrinsicOutcomeEvaluator(settings);
        final List<Option> options = actions.stream().map(action -> new Option(action,
                oneUseOpportunity(action.manaCost(), model, settings, false),
                oneUseOpportunity(action.manaCost(), model, settings, action.tapCost()))).toList();
        // Keep hand/life conditional on the planner's projected state, not an independently
        // pre-averaged token price. A preceding draw can change the value of a Clue option.
        return Optional.of((hand, life, tapped) -> {
            double best = 0;
            for (final Option option : options) {
                final Action action = option.action();
                final int benefit = switch (action.api()) {
                case "Draw" -> utility.evaluateCardDraw(hand, action.amount(), true);
                case "GainLife" -> utility.evaluateLifeGain(life, action.amount(), true);
                default -> utility.evaluateMana(action.amount(), true);
                };
                // This values an optional resource conversion, not selecting a spell to
                // cast. Account for the activation's investment, but never a card cost:
                // the token itself is precisely the option whose creation we are valuing.
                final int net = Math.max(0, benefit - PlayerResourceValueEvaluator.evaluateManaInvestment(action.manaCost()));
                best = Math.max(best, net * (tapped ? option.tappedOpportunity() : option.readyOpportunity()));
            }
            // TODO: Blood/Map, extra costs, chains, creature-token abilities, granted text, artifact
            // synergies and replacement effects require richer reference bindings. Do not silently
            // value only the supported part of a token with additional unsupported abilities.
            return best;
        });
    }

    private static Action action(final String script) {
        try {
            final Map<String, String> parameters = AbilityFactory.getMapParams(script);
            if (!PARAMETERS.containsAll(parameters.keySet())
                    || !"You".equals(parameters.getOrDefault("Defined", "You"))) { return null; }
            final String api = parameters.getOrDefault("AB", "");
            final String amountKey = switch (api) {
            case "Draw" -> "NumCards";
            case "GainLife" -> "LifeAmount";
            case "Mana" -> "Amount";
            default -> null;
            };
            if (amountKey == null || parameters.containsKey("Produced") && !"Mana".equals(api)
                    || "Mana".equals(api) && !"Any".equals(parameters.get("Produced"))
                    || parameters.containsKey("NumCards") && !"Draw".equals(api)
                    || parameters.containsKey("LifeAmount") && !"GainLife".equals(api)
                    || parameters.containsKey("Amount") && !"Mana".equals(api)) { return null; }
            final String amount = parameters.getOrDefault(amountKey, "Mana".equals(api) ? "1" : "");
            if (!amount.matches("\\d+") || !parameters.containsKey("Cost")) { return null; }
            final Cost cost = new Cost(parameters.get("Cost"), true);
            boolean sacrifice = false;
            for (final CostPart part : cost.getCostParts()) {
                if (part instanceof CostSacrifice) {
                    if (sacrifice || !part.payCostFromSource() || !"1".equals(part.getAmount())) { return null; }
                    sacrifice = true;
                } else if (!(part instanceof CostPartMana) && !(part instanceof CostTap)) { return null; }
            }
            if (!sacrifice || cost.getTotalMana().countX() > 0) { return null; }
            return new Action(api, Integer.parseInt(amount), cost.getTotalMana().getCMC(), cost.hasTapCost());
        } catch (final RuntimeException invalid) { return null; }
    }

    private static double oneUseOpportunity(final int manaCost, final IntrinsicReferenceModel model,
            final IntrinsicEvaluationSettings settings, final boolean initiallyTapped) {
        final double affordable = model.availableMana().entries().stream()
                .filter(entry -> entry.value() >= manaCost).mapToDouble(WeightedValue::weight).sum();
        final var source = new PermanentProfile(true, PermanentKind.ARTIFACT, true, 0, 0, Set.of());
        final var survival = new PermanentSurvivalEstimator(model);
        double waiting = 1;
        double opportunity = 0;
        for (int turn = 1; turn <= settings.recurringTriggerResolutions(); turn++) {
            if (turn == 1 && initiallyTapped) { continue; }
            opportunity += waiting * affordable * survival.probabilityAtTurnStart(source,
                    EntryTiming.NORMAL_SPEED, 2 * turn - 1) * AbilityOccurrenceEstimator.turnDiscount(turn);
            waiting *= 1 - affordable;
        }
        return opportunity;
    }
}
