package forge.game.combat;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class DefendingPlayerTest extends AITest {
    @Test
    public void resolvesDefenderOnlyAfterTheAttackIsDeclared() {
        Game game = initAndCreateGame();
        Player attacker = game.getPlayers().get(1);
        Player defender = game.getPlayers().get(0);
        Card creature = addCard("Grizzly Bears", attacker);

        assertTrue(AbilityUtils.getDefinedPlayers(creature, "DefendingPlayer", null).isEmpty());

        Combat combat = new Combat(attacker);
        game.getPhaseHandler().setCombat(combat);
        assertTrue(AbilityUtils.getDefinedPlayers(creature, "DefendingPlayer", null).isEmpty());

        combat.addAttacker(creature, defender);
        assertEquals(AbilityUtils.getDefinedPlayers(creature, "DefendingPlayer", null).getFirst(), defender);
    }

    @Test
    public void enteringInPostcombatMainCanRefreshDefenderDependentCardText() {
        Game game = initAndCreateGame();
        Player attacker = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, attacker);
        addCard("Suntail Hawk", opponent);
        addCardToZone("Runeclaw Bear", opponent, ZoneType.Graveyard);
        Card creature = addCardToZone("Grizzly Bears", attacker, ZoneType.Hand);
        creature.addStaticAbility("Mode$ CantBlockBy | ValidAttacker$ Card.Self"
                + " | IsPresent$ Creature.OwnedBy DefendingPlayer | PresentZone$ Graveyard | PresentCompare$ EQ0"
                + " | Description$ CARDNAME can't be blocked if the defending player's graveyard has no creature cards.");

        Card permanent = game.getAction().moveToPlay(creature, null, AbilityKey.newMap());

        assertTrue(permanent.isInPlay());
    }
}
