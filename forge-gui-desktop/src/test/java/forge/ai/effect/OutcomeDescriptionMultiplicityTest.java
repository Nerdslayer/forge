package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.testng.Assert;
import org.testng.annotations.Test;

public class OutcomeDescriptionMultiplicityTest {
    private static final Predicate<AbilityOutcomeDescription> MATCH = node -> "Draw".equals(node.api());

    private static AbilityOutcomeDescription leaf(final AbilityOutcomeDescription next) {
        return new AbilityOutcomeDescription("draw", "Draw", Map.of(), List.of(), next, "");
    }

    @Test
    public void nestedModesUseLargestLegalSelectionRatherThanSumOfAllAlternatives() {
        final var nested = new AbilityOutcomeDescription("nested", "Charm", Map.of(), List.of(leaf(null), leaf(leaf(null))), null, "");
        final var outer = new AbilityOutcomeDescription("outer", "GenericChoice", Map.of("ChoiceAmount", "1"),
                List.of(nested, leaf(null)), leaf(null), "");
        Assert.assertEquals(OutcomeDescriptionMultiplicity.maximumOccurrences(outer, MATCH, 10), 3);
        Assert.assertEquals(OutcomeDescriptionMultiplicity.maximumOccurrences(outer, MATCH, 2), 2);
        final var repeat = new AbilityOutcomeDescription("repeat", "Charm", Map.of("CharmNum", "3", "CanRepeatModes", "True"),
                List.of(nested, leaf(null)), null, "");
        Assert.assertEquals(OutcomeDescriptionMultiplicity.maximumOccurrences(repeat, MATCH, 10), 6);
        final var random = new AbilityOutcomeDescription("random", "Charm", Map.of("CharmNum", "1", "Random", "True"),
                List.of(leaf(null), leaf(null)), null, "");
        Assert.assertEquals(OutcomeDescriptionMultiplicity.maximumOccurrences(random, MATCH, 10), 1);
    }

    @Test
    public void unknownCountsAndTraversalLimitsCannotCertifySafeMultiplicity() {
        for (final var parameters : List.of(Map.of("CharmNum", "X"), Map.of("CharmNum", "-1"),
                Map.of("CharmNum", "1", "MinCharmNum", "2"), Map.of("CharmNum", "99"))) {
            final var unknown = new AbilityOutcomeDescription("unknown", "Charm", parameters, List.of(leaf(null)), null, "");
            Assert.assertEquals(OutcomeDescriptionMultiplicity.maximumOccurrences(unknown, MATCH, 2), 2);
        }
        AbilityOutcomeDescription longChain = leaf(null);
        for (int i = 0; i < 30; i++) {
            longChain = new AbilityOutcomeDescription("long", "GainLife", Map.of(), List.of(), longChain, "");
        }
        Assert.assertEquals(OutcomeDescriptionMultiplicity.maximumOccurrences(longChain, MATCH, 2), 2);
    }
}
