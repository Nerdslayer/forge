package forge.ai.effect;

import org.tinylog.Logger;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/** Collects one optional, grouped diagnostic trace for an effect-analysis decision. */
public final class EffectAnalysisTrace {
    public static final String ENABLE_PROPERTY = "forge.ai.effectAnalysisTrace";

    private static final EffectAnalysisTrace DISABLED = new EffectAnalysisTrace(null, null, false);

    private final Player evaluatingAi;
    private final SpellAbility removalAbility;
    private final StringBuilder details;

    private EffectAnalysisTrace(final Player evaluatingAi, final SpellAbility removalAbility,
            final boolean enabled) {
        this.evaluatingAi = evaluatingAi;
        this.removalAbility = removalAbility;
        this.details = enabled ? new StringBuilder() : null;
    }

    /** Creates a trace controlled by the {@value #ENABLE_PROPERTY} JVM property. */
    public static EffectAnalysisTrace create(final Player evaluatingAi) {
        return create(evaluatingAi, null);
    }

    /** Creates a trace for the spell or ability requesting removal-target evaluation. */
    public static EffectAnalysisTrace create(final Player evaluatingAi,
            final SpellAbility removalAbility) {
        return Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "false"))
                ? new EffectAnalysisTrace(evaluatingAi, removalAbility, true) : DISABLED;
    }

    static EffectAnalysisTrace disabled() {
        return DISABLED;
    }

    /** Returns whether this trace is collecting diagnostics. */
    public boolean isEnabled() {
        return details != null;
    }

    /** Records information about the removal decision after target filtering. */
    public void context(final int candidateCount) {
        if (!isEnabled()) {
            return;
        }
        if (removalAbility == null) {
            line("Removal effect: unknown");
        } else {
            final SpellAbility root = removalAbility.getRootAbility();
            final Card source = removalAbility.getHostCard();
            final String sourceZone = source.getZone() == null
                    ? "unknown" : source.getZone().getZoneType().toString();
            final Player activator = removalAbility.getActivatingPlayer();
            line("Removal effect: %s", removalUseLabel(root, source));
            line("Action: %s, source=%s, zone=%s, activator=%s",
                    removalAbility.getApi(), cardLabel(source), sourceZone,
                    activator == null ? "unknown" : activator.getName());
        }
        line("Candidates considered: %d", candidateCount);
    }

    void production(final EffectProduction production) {
        if (!isEnabled()) {
            return;
        }
        line("Production: %s -> %s%s, batches=%d, events=%d, subjects=%s",
                cardLabel(production.source()), production.type(), productionDetails(production),
                production.expectedBatches(), production.events().size(), subjects(production));
    }

    void consequence(final EffectConsequence consequence) {
        if (!isEnabled()) {
            return;
        }
        line("Consequence: %s observes %s -> %s",
                cardLabel(consequence.source()), observedTypeLabel(consequence),
                consequence.outcome().getApi());
    }

    void triggeredMatch(final EffectProduction production,
            final EffectConsequence consequence, final EffectMatch match,
            final int valuePerResolution, final int contribution) {
        if (!isEnabled()) {
            return;
        }
        line("  Match: %s -> %s, resolutions=%d, value/resolution=%d, contribution=%d",
                cardLabel(production.source()), cardLabel(consequence.source()),
                match.resolutions(), valuePerResolution, contribution);
    }

    void triggeredRelationship(final EffectProduction production,
            final EffectConsequence consequence, final int valueBeforeBatches,
            final int totalValue) {
        if (!isEnabled()) {
            return;
        }
        line("Triggered relationship: %s <-> %s, per-batch=%d, batches=%d, total=%d",
                cardLabel(production.source()), cardLabel(consequence.source()),
                valueBeforeBatches, production.expectedBatches(), totalValue);
    }

    void staticRecipient(final Card source, final Card affected,
            final int automaticValue, final int hintedValue, final int signedValue) {
        if (!isEnabled()) {
            return;
        }
        line("  Static recipient: %s -> %s, automatic=%d, hint=%d, signed=%d",
                cardLabel(source), cardLabel(affected), automaticValue, hintedValue, signedValue);
    }

    void staticRelationship(final Card source, final int totalValue) {
        if (isEnabled()) {
            line("Static relationship: %s, total=%d", cardLabel(source), totalValue);
        }
    }

    /** Records the final removal score assembled for one candidate. */
    public void candidate(final Card card, final int baseValue, final int relationshipValue,
            final int synergyWeight, final int weightedAdjustment, final int finalValue) {
        if (!isEnabled()) {
            return;
        }
        line("Candidate: %s, base=%d, relationship=%d, weight=%d%%, adjustment=%d, final=%d",
                cardLabel(card), baseValue, relationshipValue, synergyWeight,
                weightedAdjustment, finalValue);
    }

    /** Records and emits the selected removal target. */
    public void finish(final Card selected) {
        if (!isEnabled()) {
            return;
        }
        line("Selected: %s", selected == null ? "none" : cardLabel(selected));
        Logger.info(format());
    }

    String format() {
        if (!isEnabled()) {
            return "";
        }
        final String aiName = evaluatingAi == null ? "unknown" : evaluatingAi.getName();
        return "[AI Effect Analysis] Removal decision for " + aiName + System.lineSeparator()
                + details;
    }

    private void line(final String format, final Object... args) {
        details.append(String.format(format, args)).append(System.lineSeparator());
    }

    private static String subjects(final EffectProduction production) {
        final StringBuilder result = new StringBuilder("[");
        boolean first = true;
        for (final EffectEvent event : production.events()) {
            for (final EffectEvent.Subject subject : event.subjects()) {
                if (!first) {
                    result.append(", ");
                }
                first = false;
                if (subject.value() instanceof Card card) {
                    result.append(card.getName());
                } else if (subject.value() instanceof Player player) {
                    result.append(player.getName());
                } else {
                    result.append(subject.value().getClass().getSimpleName());
                }
                result.append(" x").append(subject.occurrences());
            }
        }
        return result.append(']').toString();
    }

    private static String productionDetails(final EffectProduction production) {
        if (production.type() == EffectType.COUNTER_ADDED) {
            final Object counterType = production.events().get(0).triggerParameters().get(
                    AbilityKey.CounterType);
            return ", counterType=" + counterType;
        }
        if (production.type() == EffectType.LIFE_GAINED) {
            final Object amount = production.events().get(0).triggerParameters().get(
                    AbilityKey.LifeAmount);
            return ", lifeAmount=" + amount;
        }
        if (production.type() == EffectType.CARD_DRAWN) {
            return ", drawEvents=" + production.events().size();
        }
        if (production.type() == EffectType.DAMAGE_DEALT) {
            int amount = 0;
            for (final EffectEvent event : production.events()) {
                final Object eventAmount = event.triggerParameters().get(AbilityKey.DamageAmount);
                if (eventAmount instanceof Integer value) {
                    amount = EffectMath.add(amount, value);
                }
            }
            return ", totalDamage=" + amount;
        }
        if (production.type() == EffectType.ZONE_CHANGED) {
            final EffectEvent event = production.events().get(0);
            return ", origin=" + event.triggerParameters().get(AbilityKey.Origin)
                    + ", destination="
                    + event.triggerParameters().get(AbilityKey.Destination);
        }
        if (production.type() == EffectType.SACRIFICED) {
            int amount = 0;
            for (final EffectEvent event : production.events()) {
                for (final EffectEvent.Subject subject : event.subjects()) {
                    amount = EffectMath.add(amount, subject.occurrences());
                }
            }
            return ", sacrificed=" + amount;
        }
        return "";
    }

    private static String observedTypeLabel(final EffectConsequence consequence) {
        if (consequence.observedType() != EffectType.COUNTER_ADDED) {
            return consequence.observedType().toString();
        }
        return consequence.observedType() + "(counterType="
                + consequence.trigger().getParamOrDefault("CounterType", "Any") + ")";
    }

    private static String removalUseLabel(final SpellAbility root, final Card source) {
        if (root.isSpell()) {
            return source.getName();
        }
        if (root.isTrigger()) {
            return source.getName() + " Trigger";
        }
        return source.getName() + " Ability";
    }

    private static String cardLabel(final Card card) {
        return card.getName() + "#" + card.getId() + "(" + card.getController().getName() + ")";
    }
}
