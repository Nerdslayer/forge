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
 * A bounded additional-cost resource that can be selected from a known set of cards.
 *
 * <p>The selector does not choose a payment target for the live game. It only uses these
 * candidates to prove that independent projected actions can be paid without requiring the same
 * card twice. Costs whose candidates or amount cannot be represented safely remain uncertain.</p>
 */
public record ActionResourceRequirement(ResourceKind kind, List<ResourceCandidate> candidates,
        int amount, boolean sameObject, boolean uncertain, String description) {
    public ActionResourceRequirement {
        if (kind == null) {
            throw new IllegalArgumentException("A resource kind is required");
        }
        if (amount < 0) {
            throw new IllegalArgumentException("A resource amount cannot be negative");
        }
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        description = description == null ? kind.name() : description;
    }

    public boolean isAvailable() {
        return amount == 0 || !candidates.isEmpty();
    }

    public enum ResourceKind {
        HAND_CARD,
        BATTLEFIELD_PERMANENT,
        ZONE_CARD,
        COUNTER
    }

    /** One card and the number of units of this requirement it can provide. */
    public record ResourceCandidate(Card card, int capacity) {
        public ResourceCandidate {
            if (card == null) {
                throw new IllegalArgumentException("A resource candidate card is required");
            }
            if (capacity <= 0) {
                throw new IllegalArgumentException("A resource candidate needs positive capacity");
            }
        }
    }
}
