package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.CardDefinitionValueEvaluator;
import forge.ai.LobbyPlayerAi;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

/** Lifetime fragility is charged once, on either side, without a casualty refund. */
public class TargetingFragilityTest extends AITest {
    private static final String DRAWBACK = "T:Mode$ BecomesTarget | ValidTarget$ Card.Self"
            + " | Execute$ Sac | TriggerZones$ Battlefield";
    private static final String SAC = "SVar:Sac:DB$ Sacrifice";
    private static final List<String> DRAW = List.of(
            "T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | Execute$ Draw | TriggerZones$ Battlefield",
            "SVar:Draw:DB$ Draw | Defined$ You | NumCards$ 1");

    @Test
    public void definitionDiscountsBodyAndBenefitsOnceEvenWithDuplicateDrawbacks() {
        initAndCreateGame();
        final var evaluator = new CardDefinitionValueEvaluator();
        final var normal = evaluator.evaluate(rules("Normal Engine", DRAW));
        final List<String> abilities = new ArrayList<>(DRAW);
        abilities.addAll(List.of(DRAWBACK, SAC, DRAWBACK));
        final var fragile = evaluator.evaluate(rules("Fragile Engine", abilities));
        Assert.assertTrue(normal.grossPointValue() > 0);
        Assert.assertEquals(fragile.grossPointValue(), EffectMath.scalePercent(normal.grossPointValue(), 70));
        Assert.assertEquals(fragile.contributions().stream().filter(c -> c.category().equals("Lifetime drawback")).count(), 1L);
        Assert.assertTrue(fragile.warnings().isEmpty(), fragile.warnings().toString());
    }

    @Test
    public void liveDiscountIsMastermindOnlySymmetricAndUsesTheStatReducedBody() {
        final var game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        final Card friendly = add(ai, "Friendly Fragile", List.of(DRAWBACK, SAC));
        final Card hostile = add(opponent, "Hostile Fragile", List.of(DRAWBACK, SAC));
        hostile.addStaticAbility("Mode$ Continuous | Affected$ Creature.OppCtrl | AddPower$ -2 | AddToughness$ -2");
        game.getAction().checkStaticAbilities();
        final LobbyPlayerAi lobby = (LobbyPlayerAi) ai.getLobbyPlayer();
        lobby.setAiProfile("Default");
        final var context = ValuationContext.forCombat(ai, ValuationDecision.BLOCK, 0, 100);
        final var normal = CombatValuationEvaluator.prepare(context);
        lobby.setAiProfile("Mastermind");
        final var discounted = CombatValuationEvaluator.prepare(context);
        for (final Card source : List.of(friendly, hostile)) {
            final int body = normal.permanents().get(source.getId()).bodyLossValue();
            Assert.assertEquals(discounted.permanents().get(source.getId()).bodyLossValue(), EffectMath.scalePercent(body, 70));
            Assert.assertEquals(discounted.permanents().get(source.getId()).futureAbilityLossValue(), 0);
            final var removal = UnifiedCardValueEvaluator.evaluatePermanent(source, ValuationContext.forRemoval(ai, 0, 100));
            Assert.assertEquals(removal.currentPresenceValue(), EffectMath.scalePercent(Math.abs(body), 70));
            Assert.assertEquals(removal.futurePotentialValue(), 0);
        }
        Assert.assertTrue(discounted.evaluateLosses(List.of(friendly.getId())).total() < 0);
        Assert.assertTrue(discounted.evaluateLosses(List.of(hostile.getId())).total() > 0);
        friendly.setState(forge.card.CardStateName.FaceDown, false);
        Assert.assertEquals(TargetingFragilityEvaluator.discountPercent(ai, friendly), 0);
    }

    @Test
    public void liveFutureBenefitsAndRetirementAllowancesShareOneDiscount() {
        final var game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        final List<String> abilities = new ArrayList<>(DRAW);
        abilities.addAll(List.of(DRAWBACK, SAC));
        abilities.add("T:Mode$ Attacks | ValidCard$ Card.Self | Execute$ Draw | TriggerZones$ Battlefield");
        final Card source = add(ai, "Fragile Drawing Engine", abilities);
        final LobbyPlayerAi lobby = (LobbyPlayerAi) ai.getLobbyPlayer();
        lobby.setAiProfile("Default");
        final var context = ValuationContext.forCombat(ai, ValuationDecision.BLOCK, 0, 100);
        final var before = CombatValuationEvaluator.prepare(context);
        lobby.setAiProfile("Mastermind");
        final var after = CombatValuationEvaluator.prepare(context);
        final int future = before.permanents().get(source.getId()).futureAbilityLossValue();
        Assert.assertTrue(future < 0);
        final var raw = PermanentAbilityValueEvaluator.evaluateFutureAbilities(ai, source, List.of(),
                EffectAnalysisTrace.disabled(), PermanentAbilityValueEvaluator.FutureAbilityMode.REFERENCE_ONLY);
        final int expectedFuture = raw.contributions().stream().filter(AbilityValueContribution::counted)
                .mapToInt(c -> -TargetingFragilityEvaluator.discountBenefit(-c.value(), 30)).sum();
        Assert.assertEquals(after.permanents().get(source.getId()).futureAbilityLossValue(), expectedFuture,
                "Round each separately attributed benefit, not the combined sum");
        Assert.assertFalse(before.opportunities().isEmpty(), "Exercise combat-opportunity retirement, not just scheduled value");
        before.opportunities().forEach((key, value) -> {
            Assert.assertEquals(after.opportunities().get(key).remainingLossValue(), EffectMath.scalePercent(value.remainingLossValue(), 70));
            Assert.assertEquals(after.opportunities().get(key).perUseLossValue(), EffectMath.scalePercent(value.perUseLossValue(), 70));
        });
    }

    @Test
    public void optionalRestrictedAndScheduledSacrificesDoNotGetTheTargetingDiscount() {
        initAndCreateGame();
        for (final String trigger : List.of(
                DRAWBACK + " | OptionalDecider$ You", DRAWBACK + " | ValidSource$ Spell.OppCtrl",
                "T:Mode$ Phase | Phase$ End of Turn | ValidPlayer$ You | Execute$ Sac | TriggerZones$ Battlefield")) {
            final var definition = new PaperCard(rules("Different Sacrifice " + trigger, List.of(trigger, SAC)),
                    CardEdition.UNKNOWN_CODE, CardRarity.Special);
            Assert.assertFalse(TargetingFragilityEvaluator.hasTargetingSacrifice(CardAbilityTraversal.inspect(
                    CardAbilityTraversal.definitionState(definition, forge.card.CardStateName.Original))));
        }
        Assert.assertEquals(TargetingFragilityEvaluator.discountBenefit(-100, 30), -100);
    }

    @Test
    public void printedTargetingHintIsReplacedInsteadOfStackingTwoDrawbackPenalties() {
        final var game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        final Card source = addCard("Phantom Beast", ai);
        final LobbyPlayerAi lobby = (LobbyPlayerAi) ai.getLobbyPlayer();
        lobby.setAiProfile("Default");
        final int oldBody = UnifiedPermanentValueEvaluator.evaluate(ai, source);
        final int fullBody = UnifiedPermanentValueEvaluator.evaluateWithoutTargetingDrawback(ai, source);
        Assert.assertEquals(fullBody - oldBody, 25);
        lobby.setAiProfile("Mastermind");
        final var ledger = CombatValuationEvaluator.prepare(ValuationContext.forCombat(ai, ValuationDecision.BLOCK, 0, 100));
        Assert.assertEquals(ledger.permanents().get(source.getId()).bodyLossValue(), -EffectMath.scalePercent(fullBody, 70));
        Assert.assertEquals(ledger.permanents().get(source.getId()).bodyRetentionPercent(), 70);
    }

    @Test
    public void beneficialFutureContributionsAreDiscountedButSeparateLifeLossIsNot() {
        final var game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        ai.setTeam(0);
        opponent.setTeam(1);
        final List<String> abilities = new ArrayList<>(DRAW);
        abilities.addAll(List.of(DRAWBACK, SAC,
                "T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | Execute$ Lose | TriggerZones$ Battlefield",
                "SVar:Lose:DB$ LoseLife | Defined$ You | LifeAmount$ 3"));
        final Card source = add(ai, "Mixed Fragile Engine", abilities);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Mastermind");
        final var raw = PermanentAbilityValueEvaluator.evaluateFutureAbilities(ai, source, List.of(),
                EffectAnalysisTrace.disabled(), PermanentAbilityValueEvaluator.FutureAbilityMode.REFERENCE_ONLY);
        Assert.assertTrue(raw.contributions().stream().anyMatch(c -> c.counted() && c.value() > 0),
                "Losing an independent life-loss drawback is positive utility");
        Assert.assertTrue(raw.contributions().stream().anyMatch(c -> c.counted() && c.value() < 0));
        final int expected = raw.contributions().stream().filter(AbilityValueContribution::counted)
                .mapToInt(c -> c.value() < 0 ? EffectMath.scalePercent(c.value(), 70) : c.value()).sum();
        final var after = CombatValuationEvaluator.prepare(ValuationContext.forCombat(ai, ValuationDecision.BLOCK, 0, 100));
        Assert.assertEquals(after.permanents().get(source.getId()).futureAbilityLossValue(), expected);
    }

    private static CardRules rules(final String name, final List<String> abilities) {
        final List<String> script = new ArrayList<>(List.of("Name:" + name, "ManaCost:3 U",
                "Types:Creature Illusion", "PT:4/5", "Oracle:Fragility regression fixture."));
        script.addAll(abilities);
        return CardRules.fromScript(script);
    }

    private static Card add(final Player owner, final String name, final List<String> abilities) {
        final Card card = Card.fromPaperCard(new PaperCard(rules(name, abilities), CardEdition.UNKNOWN_CODE, CardRarity.Special), owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());
        owner.getZone(ZoneType.Battlefield).add(card);
        return card;
    }
}
