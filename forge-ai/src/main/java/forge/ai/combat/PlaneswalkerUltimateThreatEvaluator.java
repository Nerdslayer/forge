package forge.ai.combat;

import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.cost.CostRemoveCounter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Public potential loyalty threat; unknown strategic effects do not disable standard thresholds. */
public final class PlaneswalkerUltimateThreatEvaluator {
    private PlaneswalkerUltimateThreatEvaluator() { }

    public record Threat(int threshold, boolean potential, List<String> reasons) {
        public Threat { reasons = List.copyOf(reasons); }
    }

    public static int threshold(final Card card) { return describe(card).threshold(); }

    public static Threat describe(final Card card) {
        int threshold = Integer.MAX_VALUE;
        boolean unresolved = false;
        final List<String> reasons = new ArrayList<>();
        // TODO: Project relevant loyalty recovery/extra activations instead of assuming unchanged
        // loyalty and ordinary timing. This is potential urgency, never a future activation certificate.
        for (final var ability : card.getSpellAbilities()) {
            if (!ability.hasParam("Ultimate")) { continue; }
            if (ability.isSuppressed()) { continue; }
            final var probe = ability.copy(card, card.getController(), false);
            probe.setActivatingPlayer(card.getController());
            if (publiclyBlocked(card, probe, reasons)) { continue; }
            if (probe.usesTargeting()) {
                final String valid = probe.getParamOrDefault("ValidTgts", "");
                final String min = probe.getParamOrDefault("TargetMin", "1");
                if (Set.of("Player", "Opponent", "Player.Opponent", "Player.YouCtrl").contains(valid)
                        && min.matches("[0-9]{1,4}")) {
                    final long targets = card.getGame().getPlayers().stream().filter(probe::canTarget).count();
                    if (targets < Integer.parseInt(min)) {
                        reasons.add("Ultimate has no required public player target on frozen board");
                        continue;
                    }
                } else { reasons.add("Ultimate target availability approximate; hidden zones not inspected"); }
            }
            if (probe.getMapParams().keySet().stream().anyMatch(key -> key.startsWith("Condition") || key.startsWith("Activation"))) {
                reasons.add("Conditional ultimate availability approximate");
            }
            if (probe.getPayCosts() == null) { unresolved = true; reasons.add("Unresolved ultimate cost"); continue; }
            final var parts = probe.getPayCosts().getCostParts();
            final var remove = parts.stream().filter(part -> part instanceof CostRemoveCounter counter
                    && counter.counter != null && counter.counter.is(CounterEnumType.LOYALTY)
                    && !counter.oneOrMore && "CARDNAME".equals(counter.getType())
                    && counter.zone.equals(List.of(forge.game.zone.ZoneType.Battlefield))).map(part -> (CostRemoveCounter) part)
                    .findFirst().orElse(null);
            if (remove == null || !remove.getAmount().matches("[0-9]{1,6}")) {
                unresolved = true; reasons.add("Variable/nonstandard ultimate loyalty cost unresolved"); continue;
            }
            final int cost = Integer.parseInt(remove.getAmount());
            if (cost <= 0) { unresolved = true; reasons.add("Nonpositive ultimate cost unresolved"); continue; }
            if (parts.size() > 1) { reasons.add("Additional ultimate costs approximate"); }
            threshold = Math.min(threshold, cost);
        }
        if (threshold == Integer.MAX_VALUE) { threshold = -1; }
        if (threshold > 0 || unresolved) {
            reasons.add("Public loyalty threshold only; intervening recovery/actions not simulated");
        }
        // An unresolved alternative may have a smaller threshold: retain priority, but do not
        // fabricate an exact denial amount or claim every ultimate mode was prevented.
        return new Threat(unresolved ? -1 : threshold, unresolved || threshold > 0
                && card.getCounters(CounterEnumType.LOYALTY) >= threshold, reasons);
    }

    private static boolean publiclyBlocked(final Card card, final forge.game.spellability.SpellAbility ability,
            final List<String> reasons) {
        for (final Card source : card.getGame().getCardsIn(forge.game.zone.ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            if (source.isFaceDown() || source.isPhasedOut()) { continue; }
            for (final var rule : source.getStaticAbilities()) {
                if (rule.isSuppressed() || !"CantBeActivated".equals(rule.getMapParams().get("Mode"))) { continue; }
                final boolean fixed = Set.of("Mode", "ValidCard", "ValidSA", "Activator", "EffectZone", "Description", "Secondary")
                        .containsAll(rule.getMapParams().keySet()) && publicRestrictionExpressions(rule.getMapParams());
                if (!fixed) { reasons.add("Conditional activation restriction approximate: " + source.getId()); continue; }
                if (rule.checkConditions(forge.game.staticability.StaticAbilityMode.CantBeActivated)
                        && forge.game.staticability.StaticAbilityCantBeCast.applyCantBeActivatedAbility(rule, ability, card, card.getController())) {
                    reasons.add("Known public activation prohibition: " + source.getId());
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean publicRestrictionExpressions(final java.util.Map<String, String> params) {
        // Do not pass arbitrary validity/SVar expressions into an engine query that could inspect
        // hidden resources. Broader rules retain potential urgency with an approximation reason.
        final String card = params.getOrDefault("ValidCard", "Card");
        final boolean publicCard = Set.of("Card", "Planeswalker", "Creature", "Artifact", "Enchantment", "Land",
                "Card.Self", "Card.YouCtrl", "Card.OppCtrl", "Planeswalker.YouCtrl", "Planeswalker.OppCtrl").contains(card)
                || (card.startsWith("Card.named") || card.startsWith("Planeswalker.named"))
                        && !card.contains("+") && !card.contains(",") && !card.contains("$");
        return publicCard && Set.of("Activated", "Activated.Loyalty", "Activated.nonMana", "Ability", "Ability.Mana")
                .contains(params.getOrDefault("ValidSA", "Ability"))
                && Set.of("Player", "You", "Opponent", "Player.You", "Player.Opponent")
                        .contains(params.getOrDefault("Activator", "Player"));
    }

    public static int damageRequired(final int loyalty, final int threshold) {
        return threshold > 0 && loyalty >= threshold ? loyalty - threshold + 1 : 0;
    }

    static boolean hasCombatReplacement(final Card card) {
        // Every ordinary walker has its initial-loyalty ETB replacement. It cannot modify
        // combat while the walker is already on the battlefield and must not veto support.
        return card.getReplacementEffects().stream().anyMatch(effect -> !effect.isSuppressed()
                && !(effect.getMode() == forge.game.replacement.ReplacementType.Moved
                        && "Battlefield".equals(effect.getMapParams().get("Destination"))));
    }
}
