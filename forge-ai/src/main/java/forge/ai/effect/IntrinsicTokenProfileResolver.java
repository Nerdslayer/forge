package forge.ai.effect;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import forge.StaticData;
import forge.card.ICardFace;
import forge.item.IPaperCard;
import forge.item.PaperToken;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;

/** Resolves fixed creature token scripts into game-free intrinsic profiles. */
final class IntrinsicTokenProfileResolver {
    private IntrinsicTokenProfileResolver() {
    }

    static Function<String, Optional<PermanentProfile>> forSource(final IPaperCard source) {
        return script -> resolve(source, script);
    }

    private static Optional<PermanentProfile> resolve(final IPaperCard source,
            final String script) {
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
            if (face == null || !face.getType().isCreature()
                    || face.getIntPower() < 0 || face.getIntToughness() < 0) {
                return Optional.empty();
            }
            return Optional.of(new PermanentProfile(true, PermanentKind.TOKEN, true,
                    face.getIntPower(), face.getIntToughness(), keywords(face), false));
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
