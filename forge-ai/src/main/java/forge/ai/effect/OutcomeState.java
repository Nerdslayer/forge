package forge.ai.effect;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Private projections for one branch. Never changes game objects or learns hidden card identities. */
public final class OutcomeState {
    final Map<SpellAbility, List<GameEntity>> targets = new IdentityHashMap<>();
    final Map<Card, Card> cards = new HashMap<>();
    final Map<Player, Integer> hands = new HashMap<>();
    final Map<Player, Integer> libraries = new HashMap<>();
    final Map<Player, Integer> life = new HashMap<>();
    final java.util.Set<Card> damageAffected = new java.util.HashSet<>();
    final Map<String, List<Card>> sacrifices = new HashMap<>();
    int lastLifeLost;
    long timestampOffset;
    boolean unsupported;
    boolean unprojectedBoard;
    boolean unprojectedBindings;

    public OutcomeState() { }

    OutcomeState copy() {
        final OutcomeState result = new OutcomeState();
        result.targets.putAll(targets);
        result.cards.putAll(cards);
        result.hands.putAll(hands);
        result.libraries.putAll(libraries);
        result.life.putAll(life);
        result.damageAffected.addAll(damageAffected);
        result.sacrifices.putAll(sacrifices);
        result.lastLifeLost = lastLifeLost;
        result.timestampOffset = timestampOffset;
        result.unsupported = unsupported;
        result.unprojectedBoard = unprojectedBoard;
        result.unprojectedBindings = unprojectedBindings;
        return result;
    }

    Card card(final Card original) { return cards.getOrDefault(original, original); }
    int hand(final Player player) { return hands.getOrDefault(player, player.getCardsIn(ZoneType.Hand).size()); }
    int library(final Player player) { return libraries.getOrDefault(player, player.getCardsIn(ZoneType.Library).size()); }

    OutcomeState bind(final SpellAbility ability, final List<GameEntity> selected) {
        final OutcomeState result = copy();
        result.targets.put(ability, List.copyOf(selected));
        return result;
    }
}
