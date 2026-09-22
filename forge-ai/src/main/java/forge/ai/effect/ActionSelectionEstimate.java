/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package forge.ai.effect;

import java.util.List;

/** Attributed gross value and confidence for one candidate action. */
public record ActionSelectionEstimate(int grossBenefit, int explicitNonManaCost,
        ActionResourceFootprint resources, ValuationCompleteness completeness,
        boolean usedFallback, List<String> limitations) {
    public ActionSelectionEstimate {
        if (resources == null || completeness == null) {
            throw new IllegalArgumentException("Action resources and completeness are required");
        }
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    /** Value used by the combination optimizer after explicit non-mana costs are applied. */
    public int netBenefit() {
        return EffectMath.subtract(grossBenefit, explicitNonManaCost);
    }

    public boolean canEstablishOverride() {
        return completeness == ValuationCompleteness.COMPLETE && !usedFallback
                && !resources.uncertain();
    }
}
