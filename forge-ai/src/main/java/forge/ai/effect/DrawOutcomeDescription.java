package forge.ai.effect;

import java.util.Map;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** Shared fixed draw interpretation; legality and resources are backend responsibilities. */
public record DrawOutcomeDescription(int amount, boolean controller) {
    /** Shared resource arithmetic; backends retain their own legality and terminal policies. */
    public record ResourceResult(int amount, int value, int handAfter, int libraryAfter, boolean overdraw) { }

    public static ResourceResult evaluateResources(final int hand, final int library,
            final int requested, final int drawLimit) {
        if (hand < 0 || library < 0 || drawLimit < 0) { throw new IllegalArgumentException("Nonnegative resources required"); }
        final int permitted = Math.min(Math.max(0, requested), drawLimit);
        final int actual = Math.min(permitted, library);
        return new ResourceResult(actual, forge.ai.PlayerResourceValueEvaluator.evaluateCardDraw(hand, actual),
                EffectMath.add(hand, actual), library - actual, permitted > library);
    }

    public static Optional<DrawOutcomeDescription> parse(final String api, final Map<String, String> params) {
        // TODO: Dynamic amounts, targets, optional draws, replacements and remembered cards.
        if (!"Draw".equals(api) || !Set.of("DB", "AB", "SP", "Cost", "Defined", "NumCards",
                "SubAbility", "SpellDescription", "StackDescription").containsAll(params.keySet())) {
            return Optional.empty();
        }
        final String recipient = params.getOrDefault("Defined", "You");
        if (!Set.of("You", "Opponent").contains(recipient)) { return Optional.empty(); }
        try {
            return Optional.of(new DrawOutcomeDescription(Integer.parseInt(params.getOrDefault("NumCards", "1")),
                    "You".equals(recipient)));
        } catch (final NumberFormatException invalid) { return Optional.empty(); }
    }

    /** Fixed 1v1 recipient union; each distinct player draws once, not once per alias. */
    public static Optional<List<DrawOutcomeDescription>> parseFixedRecipients(final String api, final Map<String, String> params) {
        final var recipients = new LinkedHashSet<String>();
        for (final String part : params.getOrDefault("Defined", "You").split("&", -1)) {
            if (!Set.of("You", "Opponent").contains(part.trim())) { return Optional.empty(); }
            recipients.add(part.trim());
        }
        final var draws = new java.util.ArrayList<DrawOutcomeDescription>();
        for (final String recipient : recipients) {
            final var single = new java.util.LinkedHashMap<>(params);
            single.put("Defined", recipient);
            final var draw = parse(api, single);
            if (draw.isEmpty()) { return Optional.empty(); }
            draws.add(draw.get());
        }
        // TODO: Multiplayer/all-player sets and event identities require explicit recipient state.
        return Optional.of(List.copyOf(draws));
    }
}
