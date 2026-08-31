package forge.ai.effect;

import java.util.List;

/** Matches normalized productions against a consequence's existing Forge trigger. */
interface EffectEventMatcher {
    List<EffectMatch> match(EffectProduction production, EffectConsequence consequence);
}
