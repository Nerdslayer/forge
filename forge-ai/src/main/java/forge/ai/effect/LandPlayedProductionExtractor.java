package forge.ai.effect;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.StaticData;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** Produces one player-wide expected land-play event for live relationship analysis. */
final class LandPlayedProductionExtractor {
    // TODO(effect analysis): Model additional land drops, known land identity, land-play timing,
    // land-play costs, and multi-turn card draw/land availability rather than one conservative
    // next-opportunity estimate.
    private LandPlayedProductionExtractor() {
    }

    static List<EffectProduction> extract(final Player controller) {
        if (controller == null || controller.getGame() == null) {
            return List.of();
        }
        // A normal next turn includes one draw. Hand size is public even when its contents are
        // hidden, so the same 40%-per-card estimate used by activation occurrence can be reused.
        final double expectedBatches = AbilityOccurrenceEstimator
                .estimateAdditionalLandProbability(controller, 1);
        if (expectedBatches <= 0) {
            return List.of();
        }
        final Card source = EffectAnalysisCardFactory.createUnknownCard(
                controller, ZoneType.Battlefield);
        final Card representativeLand = representativeLand(controller);
        final Map<AbilityKey, Object> parameters = new EnumMap<>(AbilityKey.class);
        // LandPlayed checks Origin as a string and the predicted land is assumed to come from
        // hand. ValidSA is intentionally absent because the next land's play cause is unknown.
        parameters.put(AbilityKey.Origin, ZoneType.Hand.name());
        final EffectEvent event = new EffectEvent(EffectType.LAND_PLAYED, controller,
                List.of(new EffectEvent.Subject(representativeLand, 1)), parameters);
        return List.of(new EffectProduction(source, EffectType.LAND_PLAYED,
                List.of(event), expectedBatches));
    }

    private static Card representativeLand(final Player controller) {
        for (final Card card : controller.getCardsIn(ZoneType.Battlefield)) {
            if (card.isLand()) {
                return card;
            }
        }
        return CardFactory.getCard(StaticData.instance().getCommonCards().getCard("Forest"),
                controller, controller.getGame().nextCardId(), controller.getGame());
    }
}
