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

/** Shared result for parsing the non-mana resource requirements of one action. */
record ActionCostAnalysis(ActionResourceFootprint resources, int explicitValue,
        ValuationCompleteness completeness, List<String> reasons) {
    ActionCostAnalysis {
        if (resources == null || completeness == null) {
            throw new IllegalArgumentException("Action cost resources and completeness are required");
        }
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    boolean supported() {
        return completeness == ValuationCompleteness.COMPLETE;
    }
}
