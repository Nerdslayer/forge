package forge.game.phase;

import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementLayer;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;

import java.util.Set;

/** Semantic proofs for explicitly supported mandatory loops, rather than state-hash guesses. */
public final class MandatoryLoopVerifier {
    private static final Set<String> LIFE_TRIGGER_KEYS = Set.of(
            "Mode", "TriggerZones", "LifeTotal", "LifeAmount", "Execute", "TriggerDescription");
    private static final Set<String> LOSS_EFFECT_KEYS = Set.of("DB", "Defined", "SpellDescription");
    private static final Set<String> CANT_LOSE_KEYS = Set.of(
            "Event", "ActiveZones", "ValidPlayer", "ValidLoseReason", "Layer", "Description", "Secondary");

    private MandatoryLoopVerifier() {
    }

    /**
     * An Always life-threshold trigger cannot change anything when its sole instruction is
     * prevented by an unconditional can't-lose rule. With no other resolution/state hooks,
     * it must trigger again under the same conditions. CR 104.4b and 732.4 apply after passes.
     *
     * TODO: Add separate proofs for token/zone-changing cycles, conditional protection,
     * additional effects and multiplayer (including limited range of influence).
     */
    public static boolean isVerified(final Game game, final SpellAbility next) {
        if (game.isGameOver() || game.getRegisteredPlayers().size() != 2 || game.getPlayers().size() != 2
                || game.getStack().size() != 1 || game.getStack().hasSimultaneousStackEntries()
                || game.getStack().isFrozen() || game.getStack().isResolving()
                || next != game.getStack().peekAbility()
                || game.getPlayers().stream().anyMatch(p -> p.getOutcome() != null)
                || game.getTriggerHandler().hasDelayedOrWaitingTriggers()
                || game.getTriggerHandler().isTriggerSuppressed(TriggerType.Always)
                || !(next instanceof WrappedAbility wrapper) || !wrapper.isMandatory()
                || wrapper.getDecider() != null || !plainLossEffect(wrapper.getWrappedAbility())) {
            return false;
        }
        final Trigger trigger = wrapper.getTrigger();
        final Card source = wrapper.getHostCard();
        final Player player = wrapper.getActivatingPlayer();
        if (player == null || player != source.getController() || !source.isInPlay()
                || !trigger.zonesCheck(source.getZone())
                || !source.getTriggers().contains(trigger) || trigger.isSuppressed()
                || trigger.getMode() != TriggerType.Always || trigger.getSpawningAbility() != null
                || trigger.getKeyword() != null
                || !trigger.getTriggerRemembered().isEmpty()
                || !LIFE_TRIGGER_KEYS.containsAll(trigger.getMapParams().keySet())
                || !"Battlefield".equals(trigger.getParam("TriggerZones"))
                || !"You".equals(trigger.getParam("LifeTotal"))
                || !plainLossEffect(trigger.getOverridingAbility())) {
            return false;
        }
        final String threshold = trigger.getParamOrDefault("LifeAmount", "");
        if (!threshold.matches("GE[0-9]{1,9}") || player.getLife() < Integer.parseInt(threshold.substring(2))) {
            return false;
        }

        boolean protectedUnconditionally = false;
        for (final Card card : game.getCardsInGame()) {
            if (card.getZone() == null) {
                return false;
            }
            for (final StaticAbility ability : card.getStaticAbilities()) {
                // Blocking restrictions (including flying) only answer combat legality queries.
                if (!Set.of(StaticAbilityMode.CantBlockBy).equals(ability.getMode())
                        && !ability.isSuppressed() && (ability.getActiveZone() == null
                        ? card.isInPlay() || card.isInZone(ZoneType.Command)
                        : ability.getActiveZone().contains(card.getZone().getZoneType()))) {
                    return false; // A changing continuous rule could break the proposed fixed point.
                }
            }
            for (final Trigger other : card.getTriggers()) {
                if (other.getId() != trigger.getId() && other.zonesCheck(card.getZone())
                        && other.getMode() != TriggerType.LifeLost) {
                    // This prevented loss changes no life. Reject every other event observer,
                    // including SpellAbilityCast emitted when the next trigger is stacked.
                    return false;
                }
            }
            for (final ReplacementEffect replacement : card.getReplacementEffects()) {
                if (replacement.getMode() != ReplacementType.GameLoss || !replacement.zonesCheck(card.getZone())) {
                    continue;
                }
                // Even a currently inapplicable replacement must not introduce a future choice/effect.
                if (replacement.getLayer() != ReplacementLayer.CantHappen
                        || !CANT_LOSE_KEYS.containsAll(replacement.getMapParams().keySet())
                        || replacement.getOverridingAbility() != null
                        || !"GameLoss".equals(replacement.getParam("Event"))
                        || !"Battlefield".equals(replacement.getParam("ActiveZones"))
                        || !"You".equals(replacement.getParam("ValidPlayer"))) {
                    return false;
                }
                if (card.getController() == player && !replacement.isSuppressed() && !replacement.hasRun()
                        && !replacement.hasParam("ValidLoseReason")) {
                    protectedUnconditionally = true;
                }
            }
        }
        return protectedUnconditionally && player.cantLoseCheck(GameLossReason.SpellEffect);
    }

    private static boolean plainLossEffect(final SpellAbility ability) {
        return ability != null && ability.getApi() == ApiType.LosesGame
                && ability.getKeyword() == null
                && LOSS_EFFECT_KEYS.containsAll(ability.getMapParams().keySet())
                && "LosesGame".equals(ability.getParam("DB")) && "You".equals(ability.getParam("Defined"))
                && !ability.usesTargeting() && ability.getSubAbility() == null
                && ability.getAdditionalAbilities().isEmpty() && ability.getAdditionalAbilityLists().isEmpty()
                && (ability.getChosenList() == null || ability.getChosenList().isEmpty());
    }
}
