package forge.ai.effect;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;

/** Literal type/owner filters over the primary kinds represented by intrinsic profiles. */
final class IntrinsicPermanentTargetFilter {
    enum Controller { ANY, FRIENDLY, OPPOSING }

    record Filter(Set<PermanentKind> kinds, Controller controller) {
        Filter { kinds = Set.copyOf(kinds); }
    }

    private IntrinsicPermanentTargetFilter() { }

    static Optional<Filter> parse(final String validity) {
        if (validity == null || validity.isBlank()) { return Optional.empty(); }
        final Set<PermanentKind> kinds = EnumSet.noneOf(PermanentKind.class);
        Controller owner = null;
        for (final String branch : validity.toLowerCase(Locale.ROOT).split(",", -1)) {
            final String[] clauses = branch.trim().split("[.+]", -1);
            final Set<PermanentKind> branchKinds = kinds(clauses[0]);
            if (branchKinds == null) { return Optional.empty(); }
            Controller branchOwner = Controller.ANY;
            boolean nonland = false;
            for (int i = 1; i < clauses.length; i++) {
                switch (clauses[i]) {
                case "nonland" -> {
                    if (nonland) { return Optional.empty(); }
                    nonland = true;
                    branchKinds.remove(PermanentKind.LAND);
                }
                case "youctrl", "oppctrl", "youdontctrl" -> {
                    if (branchOwner != Controller.ANY) { return Optional.empty(); }
                    branchOwner = "youctrl".equals(clauses[i]) ? Controller.FRIENDLY : Controller.OPPOSING;
                }
                default -> { return Optional.empty(); }
                }
            }
            // TODO: Mixed-controller union branches, secondary types (artifact creatures),
            // subtype/CMC/color predicates and conditional characteristics need richer profiles.
            if (owner != null && owner != branchOwner || branchKinds.isEmpty()) { return Optional.empty(); }
            owner = branchOwner;
            kinds.addAll(branchKinds);
        }
        return Optional.of(new Filter(kinds, owner));
    }

    private static Set<PermanentKind> kinds(final String type) {
        return switch (type) {
        case "permanent", "card" -> EnumSet.of(PermanentKind.CREATURE, PermanentKind.TOKEN,
                PermanentKind.AURA, PermanentKind.ARTIFACT, PermanentKind.ENCHANTMENT,
                PermanentKind.PLANESWALKER, PermanentKind.LAND, PermanentKind.PERMANENT);
        case "creature" -> EnumSet.of(PermanentKind.CREATURE, PermanentKind.TOKEN);
        case "artifact" -> EnumSet.of(PermanentKind.ARTIFACT);
        case "enchantment" -> EnumSet.of(PermanentKind.ENCHANTMENT, PermanentKind.AURA);
        case "aura" -> EnumSet.of(PermanentKind.AURA);
        case "planeswalker" -> EnumSet.of(PermanentKind.PLANESWALKER);
        case "land" -> EnumSet.of(PermanentKind.LAND);
        default -> null;
        };
    }
}
