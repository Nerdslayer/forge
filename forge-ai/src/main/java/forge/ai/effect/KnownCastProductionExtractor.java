package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.ai.ComputerUtilMana;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Creates bounded cast opportunities from information visible to the evaluating AI. */
final class KnownCastProductionExtractor {
    private static final double NEXT_TURN_CAST_DISCOUNT = 0.85;

    // TODO(effect analysis): Add hidden-opponent-hand cast estimates, copied spells, alternate
    // casting zones/costs, exact card-choice probabilities, and interactions with the actual
    // cast decision. This adapter intentionally uses only known cards and battlefield abilities.
    private KnownCastProductionExtractor() {
    }

    static List<EffectProduction> extract(final Player evaluatingAi, final Player player,
            final boolean includeHand) {
        if (evaluatingAi == null || player == null || !player.isInGame()) {
            return List.of();
        }
        final List<EffectProduction> productions = new ArrayList<>();
        if (includeHand && player == evaluatingAi) {
            for (final Card card : player.getCardsIn(ZoneType.Hand)) {
                addSpellCast(productions, player, card);
            }
        }
        for (final Card permanent : player.getCardsIn(ZoneType.Battlefield)) {
            for (final SpellAbility ability : permanent.getSpellAbilities()) {
                addAbilityCast(productions, permanent, ability);
            }
        }
        return productions;
    }

    private static void addSpellCast(final List<EffectProduction> productions,
            final Player player, final Card card) {
        final SpellAbility spell = card.getFirstSpellAbility();
        if (spell == null || !spell.isSpell()) {
            return;
        }
        final int manaCost = Math.max(0, card.getCMC());
        final int availableMana = Math.max(0,
                ComputerUtilMana.getAvailableManaEstimate(player, true));
        final int nextTurnMana = Math.max(0,
                ComputerUtilMana.getAvailableManaEstimate(player, false)
                        - player.getManaPool().totalMana());
        final double expectedBatches;
        if (manaCost <= availableMana) {
            expectedBatches = 1;
        } else if (manaCost <= nextTurnMana + 1) {
            expectedBatches = NEXT_TURN_CAST_DISCOUNT;
        } else {
            return;
        }
        final SpellAbility cast = spell.copy(card, false);
        cast.setActivatingPlayer(player);
        productions.add(production(card, cast, player, expectedBatches));
    }

    private static void addAbilityCast(final List<EffectProduction> productions,
            final Card source, final SpellAbility ability) {
        if (!ability.isActivatedAbility() || ability.isManaAbility()) {
            return;
        }
        final SpellAbility copied = EffectAbilityUtils.copyActivatedAbility(source, ability);
        if (copied == null) {
            return;
        }
        final ActivationUseEstimate estimate = ActivatedAbilityUseEvaluator.estimate(source, copied);
        if (!estimate.supported() || estimate.expectedUses() <= 0) {
            return;
        }
        productions.add(production(source, copied, source.getController(),
                estimate.expectedUses()));
    }

    private static EffectProduction production(final Card source, final SpellAbility ability,
            final Player activator, final double expectedBatches) {
        final Map<AbilityKey, Object> parameters = new EnumMap<>(AbilityKey.class);
        parameters.put(AbilityKey.Card, source);
        parameters.put(AbilityKey.SpellAbility, ability);
        parameters.put(AbilityKey.Activator, activator);
        final EffectEvent event = new EffectEvent(EffectType.SPELL_OR_ABILITY_CAST, activator,
                List.of(new EffectEvent.Subject(source, 1)), parameters);
        return new EffectProduction(source, EffectType.SPELL_OR_ABILITY_CAST,
                List.of(event), expectedBatches,
                AbilityIdentity.forSpellAbility(source, ability));
    }
}
