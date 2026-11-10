package forge.ai.effect;

import java.util.List;
import java.util.Set;

import forge.ai.AITest;
import forge.ai.AiCardMemory;
import forge.card.mana.ManaAtom;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import org.testng.Assert;
import org.testng.annotations.Test;

public class EarlyActionAffordabilityTest extends AITest {
    private SpellAbility activation(final Player ai, final String cost) {
        final Card source = addCard("Grizzly Bears", ai);
        source.setSickness(false);
        final SpellAbility ability = AbilityFactory.getAbility("AB$ Destroy | Cost$ " + cost
                + " | ValidTgts$ Permanent", source);
        ability.setActivatingPlayer(ai);
        return ability;
    }

    @Test
    public void rejectsFixedManaShortageWithoutChangingProbeState() {
        final Player ai = initAndCreateGame().getPlayers().get(1);
        final SpellAbility ability = activation(ai, "3 Sac<1/CARDNAME>");
        final Card land = addCard("Plains", ai);
        final SpellAbility mana = land.getManaAbilities().get(0);
        mana.setActivatingPlayer(null);
        AiCardMemory.rememberCard(ai, land, AiCardMemory.MemorySet.PAYS_TAP_COST);
        final Set<Card> remembered = Set.copyOf(AiCardMemory.getMemorySet(ai, AiCardMemory.MemorySet.PAYS_TAP_COST));
        Assert.assertEquals(EarlyActionAffordability.assess(ai, ability), EarlyActionAffordability.Result.UNAVAILABLE);
        Assert.assertEquals(AiCardMemory.getMemorySet(ai, AiCardMemory.MemorySet.PAYS_TAP_COST), remembered);
        Assert.assertNull(mana.getActivatingPlayer());
        Assert.assertTrue(ability.getTargets().isEmpty());
        Assert.assertFalse(land.isTapped());
    }

    @Test
    public void sufficientQuantityAndDynamicCostsStillNeedNormalChecks() {
        final Player ai = initAndCreateGame().getPlayers().get(1);
        final SpellAbility ability = activation(ai, "3 Sac<1/CARDNAME>");
        addCards("Plains", 3, ai);
        Assert.assertEquals(EarlyActionAffordability.assess(ai, ability), EarlyActionAffordability.Result.NEEDS_NORMAL_CHECK);
        // Quantity is not proof of colored affordability: leave final color assignment authoritative.
        Assert.assertEquals(EarlyActionAffordability.assess(ai, activation(ai, "U U U")),
                EarlyActionAffordability.Result.NEEDS_NORMAL_CHECK);
        Assert.assertEquals(EarlyActionAffordability.assess(ai, activation(ai, "X")),
                EarlyActionAffordability.Result.NEEDS_NORMAL_CHECK);
    }

    @Test
    public void costReducersAndManaMultipliersBypassEarlyRejection() {
        final Player ai = initAndCreateGame().getPlayers().get(1);
        final SpellAbility ability = activation(ai, "3 Sac<1/CARDNAME>");
        addCard("Training Grounds", ai);
        Assert.assertEquals(EarlyActionAffordability.assess(ai, ability), EarlyActionAffordability.Result.NEEDS_NORMAL_CHECK);
        ability.putParam("ReduceCost", "2");
        Assert.assertEquals(EarlyActionAffordability.assess(ai, ability), EarlyActionAffordability.Result.NEEDS_NORMAL_CHECK);
        final Player other = initAndCreateGame().getPlayers().get(0);
        final SpellAbility otherAbility = activation(other, "3");
        addCard("Mana Flare", other);
        Assert.assertEquals(EarlyActionAffordability.assess(other, otherAbility), EarlyActionAffordability.Result.NEEDS_NORMAL_CHECK);
    }

    @Test
    public void sourceTapUnavailabilityIsIndependentOfTargetSelection() {
        final Player ai = initAndCreateGame().getPlayers().get(1);
        final SpellAbility ability = activation(ai, "T");
        ability.getHostCard().setTapped(true);
        Assert.assertEquals(EarlyActionAffordability.assess(ai, ability), EarlyActionAffordability.Result.UNAVAILABLE);
        ability.getHostCard().setTapped(false);
        ability.getHostCard().setSickness(true);
        Assert.assertEquals(EarlyActionAffordability.assess(ai, ability), EarlyActionAffordability.Result.UNAVAILABLE);
    }

    @Test
    public void priorityPassRequiresEveryAnnouncementToBeUnavailable() {
        final var game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, game.getPlayers().get(0));
        final SpellAbility sorcerySpeed = createCard("Grizzly Bears", ai).getFirstSpellAbility();
        sorcerySpeed.setActivatingPlayer(ai);
        final SpellAbility paid = activation(ai, "1");
        final Card land = addCard("Plains", ai);
        land.setTapped(true);

        Assert.assertTrue(EarlyActionAffordability.noPriorityAction(ai, List.of(sorcerySpeed, paid), false));
        final SpellAbility free = activation(ai, "0 Sac<1/CARDNAME>");
        Assert.assertFalse(EarlyActionAffordability.noPriorityAction(ai, List.of(sorcerySpeed, paid, free), false));
        final SpellAbility instant = createCard("Lightning Bolt", ai).getFirstSpellAbility();
        instant.setActivatingPlayer(ai);
        Assert.assertFalse(EarlyActionAffordability.noPriorityAction(ai, List.of(sorcerySpeed, paid, instant), false));
        ai.getManaPool().addMana(new Mana((byte) ManaAtom.COLORLESS, land, null, ai));
        Assert.assertFalse(EarlyActionAffordability.noPriorityAction(ai, List.of(sorcerySpeed, paid), false));
    }
}
