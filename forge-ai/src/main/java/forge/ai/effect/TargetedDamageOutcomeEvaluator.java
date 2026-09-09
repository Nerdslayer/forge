package forge.ai.effect;

import java.util.Set;

import forge.ai.ComputerUtilCombat;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CounterEnumType;
import forge.game.keyword.Keyword;
import forge.game.spellability.SpellAbility;

/** Values a planner-bound damage target, sharing player damage and permanent-delta evaluation. */
final class TargetedDamageOutcomeEvaluator implements OutcomeEvaluator {
    static final TargetedDamageOutcomeEvaluator INSTANCE = new TargetedDamageOutcomeEvaluator();
    private static final Set<String> PARAMS = Set.of("DB", "ValidTgts", "ValidTgtsDesc", "TgtPrompt",
            "NumDmg", "DamageSource", "SpellDescription", "StackDescription");

    // TODO(effect analysis): Wither/infect, battles, damage-trigger/replacement repercussions,
    // consumable prevention/shields, regeneration, divided damage and damage-linked lifelink.
    // Nonlethal marked damage has no permanent value, but carries into subsequent damage outcomes.
    private TargetedDamageOutcomeEvaluator() { }

    @Override
    public boolean supports(final SpellAbility outcome) {
        return outcome != null && outcome.getApi() == ApiType.DealDamage && outcome.usesTargeting()
                && outcome.getSubAbility() == null && outcome.hasParam("NumDmg")
                && PARAMS.containsAll(outcome.getMapParams().keySet())
                && "Self".equals(outcome.getParamOrDefault("DamageSource", "Self"))
                && !outcome.getHostCard().isWitherDamage();
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome, final OutcomeEvaluationContext context) {
        if (context.state() == null) { return 0; }
        if (context.state().unprojectedBoard) { return context.unsupported(); }
        int value = PlayerDamageOutcomeEvaluator.INSTANCE.evaluateOutcome(outcome, context);
        final int amount = AbilityUtils.calculateAmount(outcome.getHostCard(), outcome.getParam("NumDmg"), outcome);
        if (amount <= 0) { return value; }
        for (final Card selected : outcome.getTargets().getTargetCards()) {
            final Card card = context.state().card(selected);
            if (card == null) { continue; }
            if (card.isBattle() || (!card.isCreature() && !card.isPlaneswalker())
                    || card.getCounters(CounterEnumType.SHIELD) > 0 || card.getShieldCount() > 0) {
                throw new IllegalArgumentException("Unsupported damage recipient state");
            }
            final int damage = ComputerUtilCombat.predictDamageTo(card, amount, outcome.getHostCard(), false);
            if (damage <= 0) { continue; }
            final Card changed = CardCopyService.getLKICopy(card);
            context.state().damageAffected.add(card);
            changed.setZone(card.getZone());
            if (card.isPlaneswalker()) {
                final int loyalty = Math.max(0, card.getCounters(CounterEnumType.LOYALTY) - damage);
                changed.setCounters(CounterEnumType.LOYALTY, loyalty);
            } else {
                changed.setDamage(EffectMath.add(card.getDamage(), damage));
                if (outcome.getHostCard().hasKeyword(Keyword.DEATHTOUCH)) {
                    changed.setHasBeenDealtDeathtouchDamage(true);
                }
            }
            // State-based actions wait until resolution ends. Keep a lethally damaged creature
            // available so a later counter/pump in the same sequence can still save it.
            value = EffectMath.add(value, CardStateDeltaEvaluator.evaluateChange(context, card, changed));
        }
        return value;
    }
}
