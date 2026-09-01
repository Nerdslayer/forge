package forge.ai.ability;

import forge.ai.AITest;
import forge.ai.AiPlayDecision;
import forge.ai.SpellAbilityAi;
import forge.ai.SpellApiToAi;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import org.testng.Assert;
import org.testng.annotations.Test;

public class FightAiTest extends AITest {
    @Test
    public void testFixedFighterChoosesBestSafeKillableTarget() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);

        Card weakerTarget = addCard("Birds of Paradise", opponent);
        Card betterTarget = addCard("Rosie Cotton of South Lane", opponent);
        Card taunter = addCard("Brash Taunter", ai);
        taunter.setSickness(false);
        addCards("Mountain", 3, ai);
        game.getAction().checkStateEffects(true);

        SpellAbility fightAbility = null;
        for (SpellAbility ability : taunter.getSpellAbilities()) {
            if (ability.getApi() == ApiType.Fight) {
                fightAbility = ability;
                break;
            }
        }
        Assert.assertNotNull(fightAbility);
        fightAbility.setActivatingPlayer(ai);

        SpellAbilityAi fightAi = SpellApiToAi.Converter.get(fightAbility);
        Assert.assertEquals(fightAi.canPlayWithSubs(ai, fightAbility).decision(), AiPlayDecision.WillPlay);
        Assert.assertTrue(fightAbility.getTargets().contains(betterTarget));
        Assert.assertFalse(fightAbility.getTargets().contains(weakerTarget));
    }
}
