package forge.ai.effect;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import forge.ai.effect.IntrinsicDrawOutcomeBackend.State;
import forge.ai.effect.IntrinsicDrawOutcomeBackend.TargetRef;

/** Unconditional spell counters against an independent, average opposing spell reference. */
final class IntrinsicCounterSpellOutcome {
    private static final Set<String> PARAMETERS = Set.of("DB", "SubAbility", "SpellDescription", "StackDescription",
            "Secondary", "PrecostDesc", "CostDesc", "ValidTgts", "ValidTgtsDesc", "TgtPrompt", "TargetType",
            "TargetMin", "TargetMax", "TgtZone", "Destination", "AILogic");
    private static final Set<String> SPELL_TYPES = Set.of("Creature", "Artifact", "Enchantment", "Planeswalker", "Instant", "Sorcery");

    private IntrinsicCounterSpellOutcome() { }

    record Counter(boolean friendly, double eligibleProbability, String validity, IntrinsicLibraryReference library) {
        Outcome<State> outcome(final String path, final IntrinsicOutcomeEvaluator evaluator,
                final WeightedDistribution<Integer> manaValues) {
            final List<Outcome.Weighted<State>> cases = new java.util.ArrayList<>();
            final double spells = library.hitProbability(String.join(",", SPELL_TYPES)).orElseThrow();
            for (final var mana : manaValues.entries()) {
                final double eligible = spells == 0 ? 0 : Math.min(1,
                        library.spellTypeProbabilityAtMana(validity, mana.value()).orElseThrow() / spells);
                if (eligible > 0) {
                    cases.add(new Outcome.Weighted<>(new Outcome.Atomic<State>(path, state -> new Outcome.Transition<>(
                            (double) evaluator.evaluateAverageCardPlay(mana.value()) * (friendly ? -1 : 1),
                            state.clearTarget(), "Counter average spell (mana value " + mana.value() + ")")),
                            eligible * mana.weight()));
                }
                if (eligible < 1) {
                    cases.add(new Outcome.Weighted<>(new Outcome.Target<State, TargetRef>(path + ":spell-unavailable",
                            state -> List.of(), State::withTarget, new Outcome.Atomic<>(state -> new Outcome.Transition<>(0, state)), true),
                            (1 - eligible) * mana.weight()));
                }
            }
            // TODO: Actual stack outcomes/identity, counterability, permission/timing, shared
            // stack targets, ability counters, unless-payment, alternative destinations and
            // graveyard interactions. Mana value estimates prevented effect value, not mana
            // refunded to either player, and must never modify available-mana or hand counts.
            return new Outcome.Random<>(path + ":countered-spell", cases);
        }
    }

    static Optional<Counter> parse(final AbilityOutcomeDescription node, final IntrinsicLibraryReference library) {
        if (!"Counter".equals(node.api()) || !PARAMETERS.containsAll(node.parameters().keySet())
                || !"Spell".equals(node.parameters().get("TargetType"))
                || !Set.of("Graveyard", "Exile").contains(node.parameters().getOrDefault("Destination", "Graveyard"))
                || !"Stack".equals(node.parameters().getOrDefault("TgtZone", "Stack"))
                || !"1".equals(node.parameters().getOrDefault("TargetMin", "1"))
                || !"1".equals(node.parameters().getOrDefault("TargetMax", "1"))) { return Optional.empty(); }
        final List<String> filters = new java.util.ArrayList<>();
        String owner = null;
        for (final String raw : node.parameters().getOrDefault("ValidTgts", "").split(",", -1)) {
            final String[] parts = raw.trim().split("[.+]", -1);
            if (parts.length == 0) { return Optional.empty(); }
            String branchOwner = "Any";
            final List<String> properties = new java.util.ArrayList<>();
            properties.add("Spell".equals(parts[0]) ? "Card" : parts[0]);
            for (int i = 1; i < parts.length; i++) {
                if (Set.of("YouCtrl", "OppCtrl").contains(parts[i]) && "Any".equals(branchOwner)) { branchOwner = parts[i]; }
                else if (Set.of("YouCtrl", "OppCtrl").contains(parts[i])) { return Optional.empty(); }
                else { properties.add(parts[i]); }
            }
            if (owner != null && !owner.equals(branchOwner)) { return Optional.empty(); }
            owner = branchOwner;
            // Tribal/secondary spell-type matching needs a conditional spell reference, not
            // the library's modest tribal support assumption. Keep this slice literal-primary.
            final String filter = String.join(".", properties);
            final var parsed = IntrinsicManaValueFilter.parse(filter);
            if (parsed.isEmpty() || parsed.get().stream().anyMatch(branch ->
                    IntrinsicPrimaryTypeFilter.parseBranch(branch.types()).isEmpty())
                    || !Set.of("Card", "Spell").contains(parts[0]) && !SPELL_TYPES.contains(parts[0])) { return Optional.empty(); }
            filters.add(filter);
        }
        final double spells = library.hitProbability(String.join(",", SPELL_TYPES)).orElseThrow();
        final String validity = filters.stream().map(filter -> filter + ".nonLand")
                .collect(java.util.stream.Collectors.joining(","));
        final double eligible = spells == 0 ? 0 : library.hitProbability(validity).orElseThrow() / spells;
        return Optional.of(new Counter("YouCtrl".equals(owner), Math.min(1, eligible), validity, library));
    }
}
