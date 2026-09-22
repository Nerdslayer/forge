/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package forge.ai;

import forge.game.spellability.SpellAbility;

/**
 * The legacy AI's immediate-play result for one candidate action.
 *
 * <p>This preserves the reason returned by the existing chooser while allowing experimental
 * selectors to admit only actions that the legacy AI would play now. It intentionally does not
 * recalculate or reinterpret the legacy rating.</p>
 */
public record LegacyActionAssessment(SpellAbility original, AiPlayDecision decision,
        TimingDisposition timingDisposition, boolean assessmentComplete) {
    public LegacyActionAssessment(final SpellAbility original, final AiPlayDecision decision,
            final TimingDisposition timingDisposition) {
        this(original, decision, timingDisposition, true);
    }

    public boolean willingNow() {
        return decision != null && decision.willingToPlay()
                && timingDisposition == TimingDisposition.PLAY_NOW;
    }

    /** A known legacy defer/pass signal that must not be treated as spare mana. */
    public boolean blocksCombination() {
        return switch (timingDisposition) {
            case WAIT_FOR_COMBAT, WAIT_FOR_MAIN2, WAIT_FOR_END_OF_TURN,
                    REACTIVE_OR_STACK_ONLY, ANOTHER_TIME -> true;
            default -> false;
        };
    }

    public enum TimingDisposition {
        PLAY_NOW,
        WAIT_FOR_COMBAT,
        WAIT_FOR_MAIN2,
        WAIT_FOR_END_OF_TURN,
        REACTIVE_OR_STACK_ONLY,
        ANOTHER_TIME,
        REJECTED
    }

    public static TimingDisposition classify(final AiPlayDecision decision) {
        if (decision == null) {
            return TimingDisposition.REJECTED;
        }
        return switch (decision) {
            case WillPlay, MandatoryPlay, PlayToEmptyHand, AddBoardPresence, ImpactCombat,
                    ResponseToStackResolve, Removal, Tempo, CardAdvantage ->
                    TimingDisposition.PLAY_NOW;
            case WaitForCombat -> TimingDisposition.WAIT_FOR_COMBAT;
            case WaitForMain2 -> TimingDisposition.WAIT_FOR_MAIN2;
            case WaitForEndOfTurn -> TimingDisposition.WAIT_FOR_END_OF_TURN;
            case StackNotEmpty -> TimingDisposition.REACTIVE_OR_STACK_ONLY;
            case AnotherTime, TimingRestrictions, MissingPhaseRestrictions ->
                    TimingDisposition.ANOTHER_TIME;
            default -> TimingDisposition.REJECTED;
        };
    }
}
