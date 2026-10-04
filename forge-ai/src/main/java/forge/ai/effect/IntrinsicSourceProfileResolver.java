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
            final int counters = startingP1p1(state);
            if (counters > 0) {
                return Optional.of(List.of(new WeightedValue<>(new SourceCase(withSize(base,
                        EffectMath.add(base.power(), counters), EffectMath.add(base.toughness(), counters)),
                        Map.of(IntrinsicDrawOutcomeBackend.SOURCE_P1P1, counters,
                                IntrinsicDrawOutcomeBackend.SOURCE_INITIAL_P1P1, counters)), 1)));
            }
            return Optional.of(List.of(new WeightedValue<>(new SourceCase(base, Map.of()), 1)));
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

    private static int startingP1p1(final CardState state) {
        int count = 0;
        for (final var keyword : state.getIntrinsicKeywords()) {
            final String[] parts = keyword.getOriginal().split(":", -1);
            if (parts.length == 3 && "etbCounter".equals(parts[0]) && "P1P1".equals(parts[1]) && parts[2].matches("\\d+")) {
                try { count = Math.addExact(count, Integer.parseInt(parts[2])); }
                catch (final ArithmeticException | NumberFormatException invalid) { return 0; }
            }
        }
        // TODO: Conditional/variable ETB counters, entry replacements and counter-dependent CDAs
        // need shared initial-state resolution. This is a known starting count, not later growth.
        return count;
    }

    private static PermanentProfile withSize(final PermanentProfile base, final int power, final int toughness) {
        return new PermanentProfile(true, PermanentKind.CREATURE, base.controlledByAi(), Math.max(0, power), Math.max(0, toughness),
                base.keywords(), base.basicLand(), base.loyalty());
    }
}
