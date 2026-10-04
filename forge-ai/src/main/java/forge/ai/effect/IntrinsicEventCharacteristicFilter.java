package forge.ai.effect;

import java.util.Map;
import java.util.regex.Pattern;

import forge.ai.effect.CardAbilityTraversal.AbilityDescription;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;

/** Source-relative event eligibility uses the prepared source profile, not a live hidden card. */
final class IntrinsicEventCharacteristicFilter {
    private static final Pattern RELATIVE = Pattern.compile("(power|toughness)(GE|GT|LE|LT|EQ|NE)([A-Za-z][A-Za-z0-9_]*)");

    private IntrinsicEventCharacteristicFilter() { }

    static AbilityDescription normalize(final AbilityDescription ability, final Map<String, String> variables,
            final IntrinsicReferenceModel model, final PermanentProfile source) {
        if (ability.origin() != CardAbilityTraversal.Origin.TRIGGER || !"ChangesZone".equals(ability.parameters().get("Mode"))) { return ability; }
        final String valid = ability.parameters().get("ValidCard");
        if (valid == null) { return ability; }
        final String[] parts = valid.split("(?=[.+])|(?<=[.+])");
        boolean changed = false;
        for (int i = 0; i < parts.length; i++) {
            final var match = RELATIVE.matcher(parts[i]);
            if (!match.matches()) { continue; }
            final var binding = IntrinsicQuantityResolver.resolve(match.group(3), variables, model, source).orElse(null);
            if (binding == null || binding.referenceValues().entries().size() != 1) { continue; }
            // Do not substitute a single sampled hand/counter population as a fixed event bound.
            if (!binding.identity().startsWith("literal:") && !java.util.Set.of("Count$CardPower", "Count$CardToughness")
                    .contains(binding.identity())) { continue; }
            final int value;
            try { value = binding.at(binding.referenceValues().entries().get(0).value()); }
            catch (final ArithmeticException overflow) { continue; }
            parts[i] = match.group(1) + match.group(2) + value;
            changed = true;
        }
        if (!changed) { return ability; }
        final var parameters = new java.util.LinkedHashMap<>(ability.parameters());
        parameters.put("ValidCard", String.join("", parts));
        // TODO: Joint variable event bounds, remembered objects and dynamic eligibility after
        // previous trigger resolutions require more than this independent source snapshot.
        return new AbilityDescription(ability.path(), ability.origin(), ability.provenance(), parameters, ability.outcome());
    }
}
