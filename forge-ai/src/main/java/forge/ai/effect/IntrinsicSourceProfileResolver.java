package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;
import forge.game.card.CardState;

/** Initializes intrinsic source characteristics without consulting a live battlefield. */
final class IntrinsicSourceProfileResolver {
    private static final Set<String> CHARACTERISTIC_PARAMS = Set.of("Mode", "CharacteristicDefining",
            "SetPower", "SetToughness", "Affected", "Description");

    private IntrinsicSourceProfileResolver() { }

    static forge.card.CardTypeView definitionTypeFacts(final CardState state) {
        final var type = new forge.card.CardType(state.getType());
        if (state.getIntrinsicKeywords().stream().anyMatch(keyword -> "Changeling".equalsIgnoreCase(keyword.getOriginal()))) {
            type.addAll(forge.card.CardType.getAllCreatureTypes());
        }
        // TODO: Other type-changing/CDAs need explicit reference resolution. Changeling is
        // unconditional and must not be mistaken for a printed Shapeshifter-only exclusion.
        return type;
    }

    record SourceCase(PermanentProfile profile, Map<String, Integer> quantities) {
        SourceCase { quantities = Map.copyOf(quantities); }
    }

    static boolean ownsCharacteristic(final Map<String, String> parameters) {
        return "Continuous".equals(parameters.get("Mode"))
                && "True".equals(parameters.get("CharacteristicDefining"))
                && CHARACTERISTIC_PARAMS.containsAll(parameters.keySet())
                && Set.of("Card.Self", "Creature.Self").contains(parameters.getOrDefault("Affected", "Card.Self"))
                && (parameters.containsKey("SetPower") || parameters.containsKey("SetToughness"));
    }

    static Optional<List<WeightedValue<PermanentProfile>>> resolve(final CardState state,
            final PermanentProfile base, final IntrinsicReferenceModel model) {
        return resolveCases(state, base, model).map(cases -> cases.stream()
                .map(reference -> new WeightedValue<>(reference.value().profile(), reference.weight())).toList());
    }

    static Optional<List<WeightedValue<SourceCase>>> resolveCases(final CardState state,
            final PermanentProfile base, final IntrinsicReferenceModel model) {
        if (!state.getType().isCreature()) {
            return Optional.of(List.of(new WeightedValue<>(new SourceCase(base, Map.of()), 1)));
        }
        String power = state.getBasePowerString();
        String toughness = state.getBaseToughnessString();
        boolean characteristic = false;
        for (final var ability : state.getStaticAbilities()) {
            if (ownsCharacteristic(ability.getMapParams())) {
                characteristic = true;
                power = ability.getMapParams().getOrDefault("SetPower", power);
                toughness = ability.getMapParams().getOrDefault("SetToughness", toughness);
            }
        }
        if (!characteristic && power.matches("\\d+") && toughness.matches("\\d+")) {
            return startingP1p1Cases(state, base, model);
        }
        final var powerBinding = IntrinsicQuantityResolver.resolve(power, state.getSVars(), model, base).orElse(null);
        final var toughnessBinding = IntrinsicQuantityResolver.resolve(toughness, state.getSVars(), model, base).orElse(null);
        if (powerBinding == null || toughnessBinding == null) {
            // TODO: Conditional/multi-zone CDAs, richer arithmetic and
            // noncreature characteristic models. Retain explicit unsupported definition coverage.
            return Optional.empty();
        }
        final java.util.ArrayList<WeightedValue<SourceCase>> profiles = new java.util.ArrayList<>();
        if (powerBinding.identity().equals(toughnessBinding.identity())) {
            for (final var value : powerBinding.referenceValues().entries()) {
                profiles.add(new WeightedValue<>(new SourceCase(withSize(base, powerBinding.at(value.value()),
                        toughnessBinding.at(value.value())), Map.of(powerBinding.identity(), value.value())), value.weight()));
            }
        } else {
            for (final var p : powerBinding.referenceValues().entries()) {
                for (final var t : toughnessBinding.referenceValues().entries()) {
                    profiles.add(new WeightedValue<>(new SourceCase(withSize(base, powerBinding.at(p.value()), toughnessBinding.at(t.value())),
                            Map.of(powerBinding.identity(), p.value(), toughnessBinding.identity(), t.value())), p.weight() * t.weight()));
                }
            }
        }
        return Optional.of(List.copyOf(profiles));
    }

    private static Optional<List<WeightedValue<SourceCase>>> startingP1p1Cases(final CardState state,
            final PermanentProfile base, final IntrinsicReferenceModel model) {
        List<WeightedValue<SourceCase>> cases = List.of(new WeightedValue<>(new SourceCase(base, Map.of()), 1));
        for (final var keyword : state.getIntrinsicKeywords()) {
            final String[] parts = keyword.getOriginal().split(":", -1);
            if (parts.length != 3 || !"etbCounter".equals(parts[0]) || !"P1P1".equals(parts[1])) { continue; }
            final var binding = IntrinsicQuantityResolver.resolve(parts[2], state.getSVars(), model, base).orElse(null);
            if (binding == null || !binding.identity().startsWith("literal:") && !"X_PAID".equals(binding.identity())
                    || binding.values().entries().stream().anyMatch(entry -> entry.value() < 0 || entry.value() > 1024)) {
                return Optional.empty();
            }
            final List<WeightedValue<SourceCase>> expanded = new java.util.ArrayList<>();
            for (final var reference : cases) {
                final var current = reference.value();
                final List<WeightedValue<Integer>> amounts = current.quantities().containsKey(binding.identity())
                        ? List.of(new WeightedValue<>(current.quantities().get(binding.identity()), 1)) : binding.referenceValues().entries();
                if (expanded.size() + amounts.size() > 256) { return Optional.empty(); }
                for (final var amount : amounts) {
                    final int count = EffectMath.add(current.quantities().getOrDefault(IntrinsicDrawOutcomeBackend.SOURCE_INITIAL_P1P1, 0),
                            binding.at(amount.value()));
                    final Map<String, Integer> quantities = new java.util.LinkedHashMap<>(current.quantities());
                    if (!binding.identity().startsWith("literal:")) { quantities.put(binding.identity(), amount.value()); }
                    quantities.put(IntrinsicDrawOutcomeBackend.SOURCE_P1P1, count);
                    quantities.put(IntrinsicDrawOutcomeBackend.SOURCE_INITIAL_P1P1, count);
                    expanded.add(new WeightedValue<>(new SourceCase(withSize(base, EffectMath.add(base.power(), count),
                            EffectMath.add(base.toughness(), count)), quantities), reference.weight() * amount.weight()));
                }
            }
            cases = expanded;
        }
        // TODO: Conditional entries, other initial quantities/counters, entry replacements,
        // counter-dependent CDAs and later growth. X paid is shared with all ability quantities;
        // it is not independently resampled for each counter keyword or outcome amount.
        return Optional.of(List.copyOf(cases));
    }

    private static PermanentProfile withSize(final PermanentProfile base, final int power, final int toughness) {
        return new PermanentProfile(true, PermanentKind.CREATURE, base.controlledByAi(), Math.max(0, power), Math.max(0, toughness),
                base.keywords(), base.basicLand(), base.loyalty());
    }
}
