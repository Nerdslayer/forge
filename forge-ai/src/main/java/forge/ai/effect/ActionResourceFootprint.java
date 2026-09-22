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

/** Resources consumed by one projected action beyond the selector's mana constraint. */
public record ActionResourceFootprint(List<Card> consumedCards, List<Card> tappedSources,
        List<ActionResourceRequirement> requirements, boolean uncertain) {
    public ActionResourceFootprint {
        consumedCards = consumedCards == null ? List.of() : List.copyOf(consumedCards);
        tappedSources = tappedSources == null ? List.of() : List.copyOf(tappedSources);
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }

    /** Compatibility constructor for the original single-card footprint. */
    public ActionResourceFootprint(final Card consumedCard, final Card tappedSource,
            final boolean uncertain) {
        this(singleton(consumedCard), singleton(tappedSource), List.of(), uncertain);
    }

    /** Returns the first fixed consumed card for callers that only need the old single-card view. */
    public Card consumedCard() {
        return consumedCards.isEmpty() ? null : consumedCards.get(0);
    }

    /** Returns the first fixed tapped source for callers that only need the old single-card view. */
    public Card tappedSource() {
        return tappedSources.isEmpty() ? null : tappedSources.get(0);
    }

    public boolean conflictsWith(final ActionResourceFootprint other) {
        if (other == null) {
            return false;
        }
        return sharesIdentity(consumedCards, other.consumedCards)
                || sharesIdentity(tappedSources, other.tappedSources)
                || sharesIdentity(consumedCards, other.tappedSources)
                || sharesIdentity(tappedSources, other.consumedCards)
                || sharesPotentialRequirement(consumedCards, other.requirements)
                || sharesPotentialRequirement(tappedSources, other.requirements)
                || sharesPotentialRequirement(other.consumedCards, requirements)
                || sharesPotentialRequirement(other.tappedSources, requirements);
    }

    public static ActionResourceFootprint forCast(final Card card) {
        return new ActionResourceFootprint(singleton(card), List.of(), List.of(), false);
    }

    public static ActionResourceFootprint forActivation(final Card source,
            final boolean hasTapCost) {
        return forActivation(source, hasTapCost, false);
    }

    /** Represents a source-bound sacrifice or exile as consumption of the source card. */
    public static ActionResourceFootprint forActivation(final Card source,
            final boolean hasTapCost, final boolean consumesSource) {
        return new ActionResourceFootprint(consumesSource ? singleton(source) : List.of(),
                hasTapCost ? singleton(source) : List.of(), List.of(), false);
    }

    public static ActionResourceFootprint withRequirements(final List<Card> consumedCards,
            final List<Card> tappedSources, final List<ActionResourceRequirement> requirements,
            final boolean uncertain) {
        return new ActionResourceFootprint(consumedCards, tappedSources, requirements, uncertain);
    }

    private static List<Card> singleton(final Card card) {
        return card == null ? List.of() : List.of(card);
    }

    private static boolean sharesIdentity(final List<Card> first, final List<Card> second) {
        for (final Card left : first) {
            for (final Card right : second) {
                if (left == right) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean sharesPotentialRequirement(final List<Card> fixed,
            final List<ActionResourceRequirement> requirements) {
        for (final Card fixedCard : fixed) {
            for (final ActionResourceRequirement requirement : requirements) {
                for (final ActionResourceRequirement.ResourceCandidate candidate
                        : requirement.candidates()) {
                    if (fixedCard == candidate.card()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
