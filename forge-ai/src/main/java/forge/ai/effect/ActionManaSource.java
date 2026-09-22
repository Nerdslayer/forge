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

import forge.game.card.Card;

/**
 * One possible mana activation or one unit already floating in the mana pool.
 *
 * <p>All output masks belong to the same activation.  The feasibility checker may use any
 * subset of them, but it may not combine output from two different activations of the same
 * source card.  A {@code null} source represents one independent floating mana unit.</p>
 */
public record ActionManaSource(Card source, List<Integer> outputMasks, boolean uncertain) {
    public ActionManaSource {
        outputMasks = outputMasks == null ? List.of() : List.copyOf(outputMasks);
    }

    public boolean isFloating() {
        return source == null;
    }
}
