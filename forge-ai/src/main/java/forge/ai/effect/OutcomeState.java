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
    final List<Card> createdTokens = new java.util.ArrayList<>();
    final Map<Player, Integer> hands = new HashMap<>();
    final Map<Player, Integer> libraries = new HashMap<>();
    final Map<Player, Integer> life = new HashMap<>();
    final java.util.Set<Card> damageAffected = new java.util.HashSet<>();
    final Map<String, List<Card>> sacrifices = new HashMap<>();
    final java.util.Set<SpellAbility> rememberedSacrifices =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    final List<Card> createdPermanents = new java.util.ArrayList<>();
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
        result.createdTokens.addAll(createdTokens);
        result.hands.putAll(hands);
        result.libraries.putAll(libraries);
        result.life.putAll(life);
        result.damageAffected.addAll(damageAffected);
        result.sacrifices.putAll(sacrifices);
        result.rememberedSacrifices.addAll(rememberedSacrifices);
        result.createdPermanents.addAll(createdPermanents);
        result.lastLifeLost = lastLifeLost;
        result.timestampOffset = timestampOffset;
        result.unsupported = unsupported;
        result.unprojectedBoard = unprojectedBoard;
        result.unprojectedBindings = unprojectedBindings;
        return result;
    }

    Card card(final Card original) { return cards.getOrDefault(original, original); }
    List<Card> battlefield(final Player player) {
        final List<Card> result = new java.util.ArrayList<>();
        for (final Card original : player.getGame().getCardsIn(ZoneType.Battlefield)) {
            final Card projected = card(original);
            if (projected != null) { result.add(projected); }
        }
        for (final Card original : createdTokens) {
            final Card projected = card(original);
            if (projected != null) { result.add(projected); }
        }
        for (final Card permanent : createdPermanents) {
            final Card projected = card(permanent);
            if (projected != null) { result.add(projected); }
        }
        return result;
    }

    void addToken(final Card prototype) {
        // Branch-local negative IDs keep identical tokens distinct without advancing the game's
        // card allocator. TODO(effect analysis): ETB/replacement events and dynamic static changes
        // still need projection-aware handling; this is not a full battlefield simulation.
        if (createdTokens.size() >= 128) { throw new IllegalArgumentException("Projected token limit exceeded"); }
        final Card token = new forge.game.card.CardCopyService(prototype)
                .getLKICopyWithId(Integer.MIN_VALUE + createdTokens.size());
        token.setGamePieceType(prototype.getGamePieceType());
        token.setController(prototype.getController(), 0);
        token.setLastKnownZone(prototype.getController().getZone(ZoneType.Battlefield));
        token.setZone(prototype.getController().getZone(ZoneType.Battlefield));
        token.setTapped(prototype.isTapped());
        createdTokens.add(token);
    }

    void addPermanent(final Card prototype, final Player controller) {
        // TODO(effect analysis): Project relevant entry triggers and current-board relationships
        // for this permanent; the current adapter values its base permanent state only.
        if (createdPermanents.size() >= 128) {
            throw new IllegalArgumentException("Projected permanent limit exceeded");
        }
        final Card permanent = new forge.game.card.CardCopyService(prototype)
                .getLKICopyWithId(Integer.MIN_VALUE + 4096 + createdPermanents.size());
        permanent.setController(controller, 0);
        permanent.setLastKnownZone(controller.getZone(ZoneType.Battlefield));
        permanent.setZone(controller.getZone(ZoneType.Battlefield));
        createdPermanents.add(permanent);
    }

    boolean hasRememberedSacrifice(final SpellAbility ability) {
        SpellAbility current = ability;
        while (current != null) {
            if (rememberedSacrifices.contains(current)) { return true; }
            current = current instanceof forge.game.spellability.AbilitySub sub
                    ? sub.getParent() : null;
        }
        return false;
    }
    int hand(final Player player) { return hands.getOrDefault(player, player.getCardsIn(ZoneType.Hand).size()); }
    int library(final Player player) { return libraries.getOrDefault(player, player.getCardsIn(ZoneType.Library).size()); }

    OutcomeState bind(final SpellAbility ability, final List<GameEntity> selected) {
        final OutcomeState result = copy();
        result.targets.put(ability, List.copyOf(selected));
        return result;
    }
}
