package forge.gamesimulationtests;

import forge.ai.ComputerUtil;
import forge.ai.simulation.SimulationTest;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementHandler;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class AirbendExileResultTest extends SimulationTest {
    @Test
    public void perpetualFlyingSurvivesAirbendRecastAndBounce() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player);
        Card target = putOnBattlefield("Runeclaw Bear", player);
        SpellAbility airbend = createAirbend(player);
        airbend.getTargets().add(target);
        AbilityUtils.resolve(airbend);

        Card exiled = game.getCardState(target);
        assertTrue(exiled.isInZone(ZoneType.Exile));
        assertTrue(exiled.hasKeyword(Keyword.FLYING));
        SpellAbility recast = exiled.getAllPossibleAbilities(player, true).stream()
                .filter(SpellAbility::isSpell)
                .findFirst().orElseThrow();
        assertEquals(recast.getPayCosts().getTotalMana().getGenericCost(), 2);
        Card manaSource = putOnBattlefield("Wastes", player);
        player.getManaPool().addMana(new Mana((byte) ManaAtom.COLORLESS, manaSource, null, player));
        player.getManaPool().addMana(new Mana((byte) ManaAtom.COLORLESS, manaSource, null, player));
        assertTrue(ComputerUtil.playStack(recast, player, game));
        game.getStack().resolveStack();

        Card recastCard = game.getCardState(exiled);
        assertTrue(recastCard.isInZone(ZoneType.Battlefield));
        assertTrue(recastCard.hasKeyword(Keyword.FLYING));
        Card bounced = game.getAction().moveTo(ZoneType.Hand, recastCard, null, null);
        Card returned = game.getAction().moveTo(ZoneType.Battlefield, bounced, null, null);
        assertTrue(returned.hasKeyword(Keyword.FLYING));
    }

    @Test
    public void redirectedExileDoesNotGrantFlyingOrReusePreviousResult() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player);
        Card first = putOnBattlefield("Runeclaw Bear", player);
        Card redirected = putOnBattlefield("Grizzly Bears", player);
        redirected.setSVar("Redirect", "DB$ ChangeZone | Defined$ ReplacedCard"
                + " | Origin$ Battlefield | Destination$ Hand");
        redirected.addReplacementEffect(ReplacementHandler.parseReplacement(
                "Event$ Moved | ValidCard$ Card.Self | ActiveZones$ Battlefield"
                        + " | Origin$ Battlefield | Destination$ Exile | ReplaceWith$ Redirect",
                redirected, true));

        SpellAbility airbend = createAirbend(player);
        airbend.getTargets().add(first);
        AbilityUtils.resolve(airbend);
        Card previouslyExiled = game.getCardState(first);
        assertTrue(previouslyExiled.hasKeyword(Keyword.FLYING));
        assertTrue(airbend.getAirbendExiled().isEmpty());

        airbend.resetTargets();
        airbend.getTargets().add(redirected);
        AbilityUtils.resolve(airbend);
        Card bounced = game.getCardState(redirected);
        assertTrue(bounced.isInZone(ZoneType.Hand));
        assertFalse(bounced.hasPerpetual());
        assertEquals(previouslyExiled.getPerpetual().size(), 1);
        assertTrue(airbend.getAirbendExiled().isEmpty());
    }

    @Test
    public void bothExiledMutateComponentsGainPerpetualFlying() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player);
        Card target = putOnBattlefield("Runeclaw Bear", player);
        Card component = createCard("Migratory Greathorn", player);
        player.getZone(ZoneType.Merged).add(component);
        component.setMergedToCard(target);
        target.setMergedCards(List.of(target, component));

        SpellAbility airbend = createAirbend(player);
        airbend.getTargets().add(target);
        AbilityUtils.resolve(airbend);

        for (Card original : List.of(target, component)) {
            Card exiled = game.getCardState(original);
            assertTrue(exiled.isInZone(ZoneType.Exile), original.getName());
            assertTrue(exiled.hasKeyword(Keyword.FLYING), original.getName());
            assertTrue(exiled.hasPerpetual(), original.getName());
        }
    }

    private SpellAbility createAirbend(Player player) {
        Card source = putOnBattlefield("Silvercoat Lion", player);
        source.setSVar("GrantFlying", "DB$ Pump | Defined$ AirbendExiled | PumpZone$ Exile"
                + " | KW$ Flying | Duration$ Perpetual");
        SpellAbility ability = AbilityFactory.getAbility(
                "AB$ Airbend | Cost$ T | ValidTgts$ Creature.Other+YouCtrl"
                        + " | TrackExiled$ True | SubAbility$ GrantFlying", source);
        ability.setActivatingPlayer(player);
        return ability;
    }

    private Card putOnBattlefield(String name, Player player) {
        Card card = createCard(name, player);
        player.getZone(ZoneType.Hand).add(card);
        return player.getGame().getAction().moveTo(ZoneType.Battlefield, card, null, null);
    }
}
