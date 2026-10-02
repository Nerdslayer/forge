package forge.ai.combat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.effect.ValuationCompleteness;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

/** Counter availability must follow an attempted untap, not simply the passage of a turn. */
public class CombatStunCounterTest extends AITest {
    @DataProvider(name = "readiness")
    public Object[][] readiness() {
        return new Object[][] {{true, 0, false, 0}, {true, 1, true, 0}, {true, 2, true, 1},
                {false, 1, false, 1}, {false, 2, false, 2}};
    }

    @Test(dataProvider = "readiness")
    public void frozenReplyMatchesTheEngineUntapWithoutMutatingTheSource(final boolean tapped, final int count,
            final boolean stillTapped, final int remaining) {
        final var game = initAndCreateGame();
        final Player attacker = game.getPlayers().get(0);
        final Player defender = game.getPlayers().get(1);
        attacker.setTeam(0);
        defender.setTeam(1);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, attacker);
        final Card source = creature(defender);
        source.setTapped(tapped);
        source.setCounters(CounterEnumType.STUN, count);
        final Combat combat = new Combat(attacker);
        game.getPhaseHandler().setCombat(combat);
        final var before = PublicCombatSnapshot.capture(attacker, combat);
        Assert.assertTrue(before.unsupportedReasons().isEmpty(), before.unsupportedReasons().toString());
        Assert.assertEquals(before.creatures().get(source.getId()).stunCounters(), count);
        final var current = CombatOutcomePredictor.predict(before, new CombatAssignment(Map.of(), Map.of()));
        final var values = new PreparedCombatValuation(Map.of(source.getId(),
                new PreparedCombatValuation.PermanentValue(source.getId(), defender.getId(), 150, 0, 0, List.of())),
                List.of(), ValuationCompleteness.COMPLETE, List.of());
        final var forecast = CombatSafetyEvaluator.forecastNextAttack(before, current,
                new PublicCombatReadiness(defender.getId(), Set.of(source.getId()), Map.of(source.getId(), Set.of()), List.of()),
                values, new CombatSearchBudget(1000));
        Assert.assertTrue(forecast.evaluation().supported(), forecast.evaluation().reasons().toString());
        final var next = forecast.continuation().orElseThrow().snapshot().creatures().get(source.getId());
        Assert.assertEquals(next.tapped(), stillTapped);
        Assert.assertEquals(next.stunCounters(), remaining);
        Assert.assertEquals(forecast.evaluation().attackers().contains(source.getId()), !stillTapped);
        Assert.assertEquals(source.isTapped(), tapped, "Forecast must not untap a live card");
        Assert.assertEquals(source.getCounters(CounterEnumType.STUN), count);
        source.untap(defender);
        Assert.assertEquals(source.isTapped(), stillTapped, "Frozen untap must agree with the engine replacement");
        Assert.assertEquals(source.getCounters(CounterEnumType.STUN), remaining);
    }

    @Test
    public void attackingWithStunIsLegalNowAndDoesNotConsumeTheCounterDuringDamage() {
        final var game = initAndCreateGame();
        final Player attacker = game.getPlayers().get(0);
        final Player defender = game.getPlayers().get(1);
        attacker.setTeam(0);
        defender.setTeam(1);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, attacker);
        final Card source = creature(attacker);
        source.setCounters(CounterEnumType.STUN, 2);
        final Combat combat = new Combat(attacker);
        game.getPhaseHandler().setCombat(combat);
        combat.addAttacker(source, defender);
        final var before = PublicCombatSnapshot.capture(attacker, combat);
        final var result = CombatOutcomePredictor.predict(before, new CombatAssignment(before.attackersToDefenders(), Map.of()));
        Assert.assertTrue(result.available(), result.reasons().toString());
        Assert.assertEquals(result.playerLifeAfter().get(defender.getId()).intValue(), 17);
        final var forecast = CombatSafetyEvaluator.forecastNextAttack(before, result,
                new PublicCombatReadiness(defender.getId(), Set.of(), Map.of(), List.of()),
                new PreparedCombatValuation(Map.of(), List.of(), ValuationCompleteness.COMPLETE, List.of()), new CombatSearchBudget(1000));
        final var next = forecast.continuation().orElseThrow().snapshot().creatures().get(source.getId());
        Assert.assertTrue(next.tapped(), "The nonactive player's creature never attempts to untap");
        Assert.assertEquals(next.stunCounters(), 2);
        Assert.assertEquals(source.getCounters(CounterEnumType.STUN), 2);
    }

    @Test
    public void untappedStunnedCreatureCanStillBlockAndTradeNormally() {
        final var game = initAndCreateGame();
        final Player attacker = game.getPlayers().get(0);
        final Player defender = game.getPlayers().get(1);
        attacker.setTeam(0);
        defender.setTeam(1);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, attacker);
        final Card source = creature(attacker);
        final Card blocker = creature(defender);
        blocker.setCounters(CounterEnumType.STUN, 2);
        final Combat combat = new Combat(attacker);
        game.getPhaseHandler().setCombat(combat);
        combat.addAttacker(source, defender);
        final var before = PublicCombatSnapshot.capture(defender, combat);
        Assert.assertTrue(before.legalBlockers().get(source.getId()).contains(blocker.getId()));
        final var result = CombatOutcomePredictor.predict(before,
                new CombatAssignment(before.attackersToDefenders(), Map.of(source.getId(), List.of(blocker.getId()))));
        Assert.assertTrue(result.available(), result.reasons().toString());
        Assert.assertEquals(result.lostCreatures(), Set.of(source.getId(), blocker.getId()));
        Assert.assertEquals(result.playerLifeAfter().get(defender.getId()).intValue(), 20);
        Assert.assertEquals(blocker.getCounters(CounterEnumType.STUN), 2);
    }

    @Test
    public void stunDoesNotHideAnUnprojectedReplacement() {
        final var game = initAndCreateGame();
        final Player player = game.getPlayers().get(0);
        final Card source = creature(player);
        source.setCounters(CounterEnumType.STUN, 1);
        source.addReplacementEffect(forge.game.replacement.ReplacementHandler.parseReplacement(
                "Event$ Untap | ActiveZones$ Battlefield | ValidCard$ Card.Self | ReplaceWith$ Other", source, false, null));
        final var snapshot = PublicCombatSnapshot.capture(player, new Combat(player));
        Assert.assertTrue(snapshot.unsupportedReasons().isEmpty());
        Assert.assertTrue(snapshot.ignoredEffects().stream().anyMatch(reason -> reason.startsWith("Unprojected replacement")));
    }

    private static Card creature(final Player owner) {
        final Card card = Card.fromPaperCard(new PaperCard(CardRules.fromScript(List.of(
                "Name:Stun Combat Fixture", "ManaCost:3", "Types:Creature Human", "PT:3/3")),
                CardEdition.UNKNOWN_CODE, CardRarity.Special), owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());
        owner.getZone(ZoneType.Battlefield).add(card);
        card.setSickness(false);
        return card;
    }
}
