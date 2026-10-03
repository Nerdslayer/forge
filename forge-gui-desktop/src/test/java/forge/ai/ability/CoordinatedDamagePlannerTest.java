package forge.ai.ability;

import java.util.ArrayList;
import java.util.List;

import forge.ai.AITest;
import forge.ai.AiPlayDecision;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.SpellApiToAi;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import org.testng.Assert;
import org.testng.annotations.Test;

public class CoordinatedDamagePlannerTest extends AITest {
    private record Fixture(Game game, Player ai, Card target, List<SpellAbility> pings) { }

    private Fixture fixture(final int sources) {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Mastermind");
        final Player opponent = game.getPlayers().get(0);
        final Card target = addCard("Aven Windreader", opponent);
        final List<SpellAbility> pings = new ArrayList<>();
        for (int i = 0; i < sources; i++) {
            final Card source = addCard("Grizzly Bears", ai);
            source.setSickness(false);
            final SpellAbility ping = AbilityFactory.getAbility("AB$ DealDamage | Cost$ T"
                    + " | NumDmg$ 1 | ValidTgts$ Creature | SpellDescription$ Deal 1 damage.", source);
            source.addSpellAbility(ping);
            ping.setActivatingPlayer(ai);
            pings.add(ping);
        }
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, opponent);
        game.getAction().checkStateEffects(true);
        return new Fixture(game, ai, target, pings);
    }

    @Test
    public void threeTapSourcesEnableOtherwiseRejectedDamage() {
        final Fixture f = fixture(3);
        final SpellAbility first = f.pings().get(0);
        Assert.assertEquals(SpellApiToAi.Converter.get(first).canPlayWithSubs(f.ai(), first).decision(),
                AiPlayDecision.WillPlay);
        Assert.assertEquals(first.getTargetCard(), f.target());
    }

    @Test
    public void defaultKeepsLegacyBehavior() {
        final Fixture f = fixture(3);
        ((LobbyPlayerAi) f.ai().getLobbyPlayer()).setAiProfile("Default");
        final SpellAbility first = f.pings().get(0);
        Assert.assertNotEquals(SpellApiToAi.Converter.get(first).canPlayWithSubs(f.ai(), first).decision(),
                AiPlayDecision.WillPlay);
    }

    @Test
    public void fallbackDoesNotReplaceSingleActivationTimingDecisions() {
        final Fixture f = fixture(1);
        f.target().setDamage(2);
        Assert.assertFalse(new CoordinatedDamagePlanner().prepare(f.ai(), f.pings().get(0)));
    }

    @Test
    public void insufficientDamageAndUnavailableSourcesAreRejected() {
        final Fixture f = fixture(3);
        final CoordinatedDamagePlanner planner = new CoordinatedDamagePlanner();
        f.pings().get(2).getHostCard().setTapped(true);
        Assert.assertFalse(planner.prepare(f.ai(), f.pings().get(0)));
        f.pings().get(2).getHostCard().setTapped(false);
        f.pings().get(2).getHostCard().setSickness(true);
        Assert.assertFalse(planner.prepare(f.ai(), f.pings().get(0)));
    }

    @Test
    public void multipleAbilitiesOnOneSourceAreNotMultiplePings() {
        final Fixture f = fixture(2);
        final Card source = f.pings().get(1).getHostCard();
        source.addSpellAbility(f.pings().get(1).copy(source, f.ai(), false));
        Assert.assertFalse(new CoordinatedDamagePlanner().prepare(f.ai(), f.pings().get(0)));
        f.target().setDamage(1);
        Assert.assertTrue(new CoordinatedDamagePlanner().prepare(f.ai(), f.pings().get(0)));
    }

    @Test
    public void continuationRechecksMarkedDamageAndResources() {
        final Fixture f = fixture(3);
        final CoordinatedDamagePlanner planner = new CoordinatedDamagePlanner();
        Assert.assertTrue(planner.prepare(f.ai(), f.pings().get(0)));
        f.pings().get(0).getHostCard().setTapped(true);
        f.target().setDamage(1);
        Assert.assertTrue(planner.prepare(f.ai(), f.pings().get(1)));
        f.pings().get(2).getHostCard().setTapped(true);
        Assert.assertFalse(planner.prepare(f.ai(), f.pings().get(1)));
        f.pings().get(2).getHostCard().setTapped(false);
        // The canceled continuation must establish a new multi-activation group first.
        Assert.assertTrue(planner.prepare(f.ai(), f.pings().get(1)));
        f.pings().get(1).getHostCard().setTapped(true);
        f.target().setDamage(2);
        Assert.assertTrue(planner.prepare(f.ai(), f.pings().get(2)));
        Assert.assertEquals(f.pings().get(2).getTargetCard(), f.target());
    }

    @Test
    public void chooserCompletesLethalAfterEachResolvedActivation() {
        final Fixture f = fixture(3);
        final var controller = ((PlayerControllerAi) f.ai().getController()).getAi();
        for (int i = 0; i < 3; i++) {
            final List<SpellAbility> choice = controller.chooseSpellAbilityToPlay();
            Assert.assertNotNull(choice);
            Assert.assertEquals(choice.size(), 1);
            final SpellAbility action = choice.get(0);
            Assert.assertEquals(action.getTargetCard(), f.target());
            action.getHostCard().setTapped(true);
            AbilityUtils.resolve(action);
            f.game().getAction().checkStateEffects(true);
        }
        Assert.assertFalse(f.target().isInPlay());
    }

    @Test
    public void restrictedTargetsAndAdditionalCostsAreNotRelaxed() {
        final Fixture f = fixture(3);
        final SpellAbility first = f.pings().get(0);
        final SpellAbility restricted = AbilityFactory.getAbility("AB$ DealDamage | Cost$ T"
                + " | NumDmg$ 1 | ValidTgts$ Creature.attacking", first.getHostCard());
        Assert.assertFalse(new CoordinatedDamagePlanner().prepare(f.ai(), restricted));
        final SpellAbility paid = AbilityFactory.getAbility("AB$ DealDamage | Cost$ 1 T"
                + " | NumDmg$ 1 | ValidTgts$ Creature", first.getHostCard());
        Assert.assertFalse(new CoordinatedDamagePlanner().prepare(f.ai(), paid));
    }

    @Test
    public void grantedSliverAbilitiesWorkAgainstAnAttacker() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Mastermind");
        final Card quilled = addCard("Quilled Sliver", ai);
        final Card other = addCard("Necrotic Sliver", ai);
        final Card third = addCard("Leeching Sliver", ai);
        for (final Card source : List.of(quilled, other, third)) {
            source.setSickness(false);
        }
        final Card attacker = addCard("Aven Windreader", opponent);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, opponent);
        game.getPhaseHandler().setCombat(new Combat(opponent));
        game.getPhaseHandler().getCombat().addAttacker(attacker, ai);
        game.getAction().checkStateEffects(true);
        final SpellAbility ping = quilled.getSpellAbilities().stream()
                .filter(a -> a.getApi() == ApiType.DealDamage).findFirst().orElseThrow();
        ping.setActivatingPlayer(ai);
        Assert.assertEquals(SpellApiToAi.Converter.get(ping).canPlayWithSubs(ai, ping).decision(),
                AiPlayDecision.WillPlay);
        Assert.assertEquals(ping.getTargetCard(), attacker);
    }

    @Test
    public void indestructibleAndDamageTriggeredChangesAreDeferred() {
        final Fixture f = fixture(3);
        final Card taunter = addCard("Brash Taunter", f.target().getController());
        final CoordinatedDamagePlanner planner = new CoordinatedDamagePlanner();
        Assert.assertFalse(planner.prepare(f.ai(), f.pings().get(0)));
        Assert.assertTrue(taunter.isInPlay());
    }
}
