package forge.ai.effect;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import forge.StaticData;
import forge.card.ICardFace;
import forge.item.IPaperCard;
import forge.item.PaperToken;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;

/** Resolves fixed token scripts into game-free body or supported resource-option values. */
final class IntrinsicTokenProfileResolver {
    private IntrinsicTokenProfileResolver() {
    }

    static IntrinsicTokenResolver forSource(final IPaperCard source, final IntrinsicReferenceModel model,
            final IntrinsicEvaluationSettings settings) {
        final java.util.Map<String, Optional<IntrinsicTokenResolver.Definition>> cache = new java.util.HashMap<>();
        return script -> cache.computeIfAbsent(script, key -> resolve(source, key, model, settings));
    }

    private static Optional<IntrinsicTokenResolver.Definition> resolve(final IPaperCard source,
            final String script, final IntrinsicReferenceModel model, final IntrinsicEvaluationSettings settings) {
        if (source == null || script == null || script.isBlank()) {
            return Optional.empty();
        }
        try {
            final StaticData data = StaticData.instance();
            if (data == null || data.getAllTokens() == null) {
                return Optional.empty();
            }
            PaperToken token = data.getAllTokens().getToken(script, source.getEdition());
            if (token == null) {
                token = data.getAllTokens().getToken(script);
            }
            if (token == null) {
                return Optional.empty();
            }
            final ICardFace face = token.getMainFace();
            if (face == null) { return Optional.empty(); }
            if (!face.getType().isCreature()) {
                return IntrinsicConsumableAbilityEvaluator.evaluate(face, model, settings).map(value ->
                        new IntrinsicTokenResolver.Definition(new PermanentProfile(true, PermanentKind.ARTIFACT,
                                true, 0, 0, Set.of()), value));
            }
            final boolean variablePower = !face.getPower().matches("-?\\d+");
            final boolean variableToughness = !face.getToughness().matches("-?\\d+");
            if ((variablePower || variableToughness) && face.getStaticAbilities().iterator().hasNext()) {
                // TODO: Explicit P/T overrides do not suppress characteristic-defining statics.
                // Layered variable-token characteristics require their own projection.
                return Optional.empty();
            }
            if (!variablePower && face.getIntPower() < 0 || !variableToughness && face.getIntToughness() < 0) {
                return Optional.empty();
            }
            // Variable prototype dimensions are placeholders, never valued without explicit
            // overrides. TODO: Characteristic-defining token abilities and negative/zero
            // toughness/SBA timing, without duplicating body/keyword credit.
            return Optional.of(new IntrinsicTokenResolver.Definition(new PermanentProfile(true, PermanentKind.TOKEN, true,
                    variablePower ? 0 : face.getIntPower(), variableToughness ? 0 : face.getIntToughness(), keywords(face), false),
                    null, variablePower, variableToughness));
        } catch (final RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Set<String> keywords(final ICardFace face) {
        final Set<String> result = new HashSet<>();
        for (final String keyword : face.getKeywords()) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            // Reference creature evaluation currently uses keyword families, not parameters.
            result.add(keyword.split(":", 2)[0].trim().toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }
}
