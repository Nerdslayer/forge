package forge.ai.effect;

import forge.card.CardType;

/** Normalizes only recipient predicates with explicitly modeled intrinsic probabilities. */
final class IntrinsicStaticRecipientFilter {
    private static final java.util.regex.Pattern CHARACTERISTIC = java.util.regex.Pattern.compile("(power|toughness)((?:GE|GT|LE|LT|EQ|NE)-?\\d+)");
    record Filter(String affected, double probability, java.util.function.BiPredicate<Integer, Integer> characteristics,
            java.util.function.Predicate<java.util.Set<String>> keywords, boolean profileRestricted) {
        Filter(final String affected, final double probability) {
            this(affected, probability, (power, toughness) -> true, words -> true, false);
        }

        boolean matches(final IntrinsicReferenceModel.PermanentProfile profile) {
            return characteristics.test(profile.power(), profile.toughness()) && keywords.test(profile.keywords());
        }

        boolean matches(final IntrinsicReferenceModel.CreatureProfile profile) {
            final java.util.Set<String> words = new java.util.HashSet<>(profile.keywords());
            if (profile.hexproof()) { words.add("Hexproof"); }
            if (profile.indestructible()) { words.add("Indestructible"); }
            return characteristics.test(profile.power(), profile.toughness()) && keywords.test(words);
        }
    }

    private IntrinsicStaticRecipientFilter() { }

    static Filter describe(final String affected, final IntrinsicReferenceModel model) {
        if (affected == null) { return new Filter(null, 1); }
        String normalized = affected;
        double probability = 1;
        java.util.function.BiPredicate<Integer, Integer> characteristics = (power, toughness) -> true;
        java.util.function.Predicate<java.util.Set<String>> keywords = words -> true;
        boolean restricted = false;
        final IntrinsicCounterPredicates.Filter counter = IntrinsicCounterPredicates.parse(affected).orElse(null);
        if (counter != null) {
            probability = model.quantities().distribution(counter.quantity()).entries().stream()
                    .filter(entry -> counter.matches().test(entry.value())).mapToDouble(WeightedValue::weight).sum();
            normalized = counter.base();
        }
        final String unfiltered = normalized;
        final java.util.List<String> base = new java.util.ArrayList<>();
        for (final String part : normalized.replace(".power", "+power").replace(".toughness", "+toughness")
                .replace(".with", "+with").split("\\+", -1)) {
            if (part.startsWith("power") || part.startsWith("toughness")) {
                final var matcher = CHARACTERISTIC.matcher(part);
                if (!matcher.matches() || IntrinsicAbilityConditions.compare("0", matcher.group(2)) == null) {
                    return new Filter(unfiltered, probability); // Keep unknown clauses for scope rejection.
                }
                final boolean power = "power".equals(matcher.group(1));
                final String comparison = matcher.group(2);
                characteristics = characteristics.and((p, t) -> Boolean.TRUE.equals(IntrinsicAbilityConditions.compare(
                        Integer.toString(power ? p : t), comparison)));
                restricted = true;
            } else if (part.startsWith("with")) {
                final boolean absent = part.startsWith("without");
                final String keyword = part.substring(absent ? 7 : 4);
                final java.util.Set<String> supported = IntrinsicStaticAbilityEvaluator.parseSupportedKeywords(keyword);
                if (supported == null || supported.size() != 1) {
                    return new Filter(unfiltered, probability);
                }
                final String required = supported.iterator().next();
                keywords = keywords.and(words -> words.stream().anyMatch(required::equalsIgnoreCase) != absent);
                restricted = true;
            } else { base.add(part); }
        }
        normalized = String.join("+", base);
        if (restricted && !normalized.contains(".") && base.size() > 1) {
            normalized = base.get(0) + "." + String.join("+", base.subList(1, base.size()));
        }
        if (normalized.startsWith("Card.")) {
            final String firstQualifier = normalized.substring(5).split("\\+", -1)[0];
            if (CardType.isACreatureType(firstQualifier)) {
                // P/T and creature keyword benefits require creature recipients even when the
                // script expresses its tribal predicate as Card.Merfolk rather than Creature.
                normalized = "Creature." + normalized.substring(5);
            }
        }
        // TODO: Other counters, variable/inter-card P/T predicates, chosen types, attachment/granted
        // scripts and history-dependent eligibility need their own distributions and bindings.
        return new Filter(normalized, probability, characteristics, keywords, restricted);
    }
}
