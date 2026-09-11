package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import forge.game.CardTraitBase;
import forge.card.CardStateName;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.card.CardState;
import forge.item.IPaperCard;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

/** Inventories a selected face without checking activity, affordability or conditions. */
public final class CardAbilityTraversal {
    private CardAbilityTraversal() { }

    public enum Origin { SPELL, ACTIVATION, TRIGGER, STATIC, REPLACEMENT }
    public enum Provenance { PRINTED, KEYWORD, GRANTED }
    public record AbilityDescription(String path, Origin origin, Provenance provenance,
            Map<String, String> parameters, AbilityOutcomeDescription outcome) {
        public AbilityDescription { parameters = Map.copyOf(parameters); }
    }

    /** Uses Forge's existing game-free materialization, including keyword expansion. */
    public static List<AbilityDescription> inspectDefinition(final IPaperCard definition, final CardStateName face) {
        return inspect(definitionState(definition, face));
    }

    static CardState definitionState(final IPaperCard definition, final CardStateName face) {
        // Negative IDs request a display-only card and omit scripts. A local nonnegative ID
        // materializes abilities without allocating a game or consuming any live card IDs.
        final Card card = CardFactory.getCard(definition, null, 0, null);
        if (!card.hasState(face)) { throw new IllegalArgumentException("Missing definition face: " + face); }
        // Split Original is a composite view; require callers to select a real face.
        if (face == CardStateName.Original && card.hasState(CardStateName.LeftSplit)) {
            throw new IllegalArgumentException("Select LeftSplit or RightSplit explicitly");
        }
        return card.getState(face);
    }

    /**
     * Caller supplies the intended face. A live state is not a definition: intrinsic flags do
     * not undo changed text. TODO: Delayed-trigger discovery and rules-generated origin labels.
     * Intrinsic callers must supply a prepared definition, never an opponent's hidden face.
     */
    public static List<AbilityDescription> inspect(final CardState state) {
        final List<AbilityDescription> result = new ArrayList<>();
        final String face = state.getStateName().name();
        int index = 0;
        for (final SpellAbility ability : state.getSpellAbilities()) {
            final String path = face + "/ability:" + index++;
            result.add(new AbilityDescription(path, ability.isActivatedAbility() ? Origin.ACTIVATION : Origin.SPELL,
                    provenance(ability), ability.getMapParams(), AbilityOutcomeParser.parse(ability, path)));
        }
        index = 0;
        for (final Trigger trigger : state.getTriggers()) {
            final String path = face + "/trigger:" + index++;
            AbilityOutcomeDescription outcome;
            try {
                outcome = AbilityOutcomeParser.parse(EffectAbilityUtils.resolveTriggerOutcome(
                        trigger.getHostCard(), trigger), path + "/execute");
            } catch (final RuntimeException failure) {
                outcome = AbilityOutcomeDescription.unresolved(path, "Cannot resolve trigger Execute");
            }
            result.add(new AbilityDescription(path, Origin.TRIGGER, provenance(trigger), trigger.getMapParams(), outcome));
        }
        index = 0;
        for (final CardTraitBase ability : state.getStaticAbilities()) {
            result.add(entry(face + "/static:" + index++, Origin.STATIC, ability));
        }
        index = 0;
        for (final CardTraitBase ability : state.getReplacementEffects()) {
            result.add(entry(face + "/replacement:" + index++, Origin.REPLACEMENT, ability));
        }
        return List.copyOf(result);
    }

    private static AbilityDescription entry(final String path, final Origin origin, final CardTraitBase ability) {
        return new AbilityDescription(path, origin, provenance(ability), ability.getMapParams(),
                AbilityOutcomeDescription.unresolved(path, "Origin requires an adapter: " + origin));
    }

    private static Provenance provenance(final CardTraitBase ability) {
        return !ability.isIntrinsic() ? Provenance.GRANTED
                : ability.getKeyword() != null ? Provenance.KEYWORD : Provenance.PRINTED;
    }
}
