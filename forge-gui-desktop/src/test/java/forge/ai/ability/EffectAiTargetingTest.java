package forge.ai.ability;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.SpellApiToAi;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class EffectAiTargetingTest extends AITest {
    @DataProvider
    public Object[][] targetingCases() {
        return new Object[][] {
                {"Default", false, false}, {"Mastermind", false, false},
                {"Default", true, false}, {"Mastermind", true, false},
                {"Default", false, true}, {"Mastermind", false, true},
                {"Default", true, true}, {"Mastermind", true, true}
        };
    }

    @DataProvider
    public Object[][] fallbackCases() {
        return new Object[][] {
                {"Default", false}, {"Mastermind", false},
                {"Default", true}, {"Mastermind", true}
        };
    }

    @DataProvider
    public Object[][] profiles() {
        return new Object[][] {{"Default"}, {"Mastermind"}};
    }

    @Test(dataProvider = "targetingCases")
    public void disablingCombatNeverTargetsOwnOrAlliedCreatures(final String profile, final boolean fallback,
            final boolean includeAlly) {
        final Game game = initAndCreateThreePlayerGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        final Player ally = game.getPlayers().get(2);
        ai.setTeam(0);
        ally.setTeam(0);
        opponent.setTeam(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile(profile);
        final Card source = addCard("Sol Ring", ai);
        final Card ownCreature = addCard("Craw Wurm", ai);
        if (includeAlly) {
            final Card alliedCreature = addCard("Colossal Dreadmaw", ally);
            alliedCreature.setSickness(false);
        }
        final Card enemyCreature = addCard("Grizzly Bears", opponent);
        ownCreature.setSickness(fallback);
        enemyCreature.setSickness(false);
        source.setSVar("CantAttack", "Mode$ CantAttack | ValidCard$ Card.IsRemembered");
        source.setSVar("CantBlock", "Mode$ CantBlockBy | ValidBlocker$ Card.IsRemembered");
        if (fallback) {
            // With no available attackers, request the generic main-one fallback.
            source.setSVar("PlayMain1", "ALWAYS");
        }
        final SpellAbility ability = AbilityFactory.getAbility("AB$ Effect | Cost$ 0"
                + " | ValidTgts$ Creature | RememberObjects$ Targeted"
                + " | StaticAbilities$ CantAttack,CantBlock | IsCurse$ True"
                + " | Duration$ UntilYourNextTurn", source);
        ability.setActivatingPlayer(ai);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);

        Assert.assertTrue(SpellApiToAi.Converter.get(ability).canPlayWithSubs(ai, ability).willingToPlay());
        Assert.assertSame(ability.getTargetCard(), enemyCreature);
    }

    @Test(dataProvider = "fallbackCases")
    public void fallbackConsidersOnlyOpponentsAffectedByTheRestriction(final String profile,
            final boolean cantActivate) {
        final Game game = initAndCreateThreePlayerGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        final Player ally = game.getPlayers().get(2);
        ai.setTeam(0);
        ally.setTeam(0);
        opponent.setTeam(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile(profile);
        final Card source = addCard("Sol Ring", ai);
        addCard("Planar Bridge", ai);
        addCard("Staff of Nin", ally);
        final Card enemyCreature = addCard("Grizzly Bears", opponent);
        final Card enemyArtifact = addCard("Planar Portal", opponent);
        // An expensive permanent with no activated abilities is not a useful target.
        addCard("Darksteel Forge", opponent);
        final SpellAbility ability = fallbackRestriction(source, ai, cantActivate, 1);

        Assert.assertTrue(SpellApiToAi.Converter.get(ability).canPlayWithSubs(ai, ability).willingToPlay());
        Assert.assertSame(ability.getTargetCard(), cantActivate ? enemyArtifact : enemyCreature);
    }

    @Test(dataProvider = "profiles")
    public void fallbackDoesNotSelectTheSameActivatedPermanentTwice(final String profile) {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile(profile);
        final Card source = addCard("Sol Ring", ai);
        addCard("Grizzly Bears", opponent);
        final Card portal = addCard("Planar Portal", opponent);
        final Card tome = addCard("Jayemdae Tome", opponent);
        final SpellAbility ability = fallbackRestriction(source, ai, true, 2);

        Assert.assertTrue(SpellApiToAi.Converter.get(ability).canPlayWithSubs(ai, ability).willingToPlay());
        Assert.assertEquals(ability.getTargets().size(), 2);
        Assert.assertTrue(ability.getTargets().getTargetCards().contains(portal));
        Assert.assertTrue(ability.getTargets().getTargetCards().contains(tome));
    }

    private SpellAbility fallbackRestriction(final Card source, final Player ai, final boolean cantActivate,
            final int targets) {
        source.setSVar("PlayMain1", "ALWAYS");
        source.setSVar("Restriction", "Mode$ CantAttack,CantBlock" + (cantActivate ? ",CantBeActivated" : "")
                + " | ValidCard$ Card.IsRemembered");
        final SpellAbility ability = AbilityFactory.getAbility("AB$ Effect | Cost$ 0"
                + " | ValidTgts$ Permanent.nonLand | RememberObjects$ Targeted"
                + " | StaticAbilities$ Restriction | IsCurse$ True | Duration$ UntilYourNextTurn"
                + " | TargetMin$ " + targets + " | TargetMax$ " + targets, source);
        ability.setActivatingPlayer(ai);
        return ability;
    }
}
