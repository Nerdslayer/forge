/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package forge.ai.effect;

import java.util.Collections;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import forge.ai.AiCardMemory;
import forge.ai.ComputerUtilMana;
import forge.card.mana.ManaAtom;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostTap;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** Immutable state shared by all action-combination probes in one chooser invocation. */
public record ActionDecisionSnapshot(PhaseType phase, boolean aiTurn, boolean stackEmpty,
        int availableMana, Set<Card> reservedResources, int handOverflow,
        List<ActionManaSource> manaSources, boolean manaResourcesComplete) {
    public ActionDecisionSnapshot {
        reservedResources = reservedResources == null ? Set.of() : Set.copyOf(reservedResources);
        manaSources = manaSources == null ? List.of() : List.copyOf(manaSources);
    }

    /** Captures mana, timing, hand pressure, and transient AI resource reservations once. */
    public static ActionDecisionSnapshot capture(final Player ai) {
        if (ai == null || ai.getGame() == null) {
            return new ActionDecisionSnapshot(null, false, false, 0, Set.of(), 0,
                    List.of(), false);
        }
        final Set<Card> reservations = Collections.newSetFromMap(new IdentityHashMap<>());
        final AiCardMemory.MemorySet[] reservationSets = {
                AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL,
                AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_MAIN2,
                AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_DECLBLK,
                AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK,
                AiCardMemory.MemorySet.PAYS_TAP_COST,
                AiCardMemory.MemorySet.PAYS_SAC_COST
        };
        for (final AiCardMemory.MemorySet reservationSet : reservationSets) {
            final Set<Card> remembered = AiCardMemory.getMemorySet(ai, reservationSet);
            if (remembered != null) {
                reservations.addAll(remembered);
            }
        }
        final int availableMana;
        try {
            availableMana = Math.max(0, ComputerUtilMana.getAvailableManaEstimate(ai, true));
        } catch (final RuntimeException ignored) {
            // The legacy chooser remains authoritative if the snapshot cannot be completed.
            return new ActionDecisionSnapshot(ai.getGame().getPhaseHandler().getPhase(),
                    ai.getGame().getPhaseHandler().isPlayerTurn(ai),
                    ai.getGame().getStack().isEmpty(), 0, reservations, handOverflow(ai),
                    List.of(), false);
        }
        final ManaSources manaSources = captureManaSources(ai, reservations);
        return new ActionDecisionSnapshot(ai.getGame().getPhaseHandler().getPhase(),
                ai.getGame().getPhaseHandler().isPlayerTurn(ai),
                ai.getGame().getStack().isEmpty(), availableMana, reservations, handOverflow(ai),
                manaSources.sources(), manaSources.complete());
    }

    public boolean allowsProactiveCombination() {
        return phase != null && phase.isMain() && aiTurn && stackEmpty && reservedResources.isEmpty();
    }

    public boolean hasResourceReservation() {
        return !reservedResources.isEmpty();
    }

    private static int handOverflow(final Player ai) {
        return ai.isUnlimitedHandSize() ? 0
                : Math.max(0, ai.getCardsIn(ZoneType.Hand).size() - ai.getMaxHandSize());
    }

    private static ManaSources captureManaSources(final Player ai, final Set<Card> reservations) {
        final List<ActionManaSource> sources = new ArrayList<>();
        boolean complete = true;
        for (final Mana mana : ai.getManaPool()) {
            if (mana.isRestricted()) {
                // A floating mana restriction depends on the spell being paid.  The source model
                // intentionally does not guess at that relationship.
                complete = false;
                continue;
            }
            sources.add(new ActionManaSource(null, List.of((int) mana.getColor()), false));
        }

        for (final Card source : ai.getCardsIn(ZoneType.Battlefield)) {
            if (source.getManaAbilities().isEmpty() || reservations.contains(source)) {
                continue;
            }
            for (final SpellAbility manaAbility : ComputerUtilMana.getAIPlayableMana(source)) {
                final SpellAbility probe;
                try {
                    probe = manaAbility.copy(source, false);
                    probe.setActivatingPlayer(ai);
                    if (!probe.canPlay() || !probe.checkRestrictions(ai)) {
                        continue;
                    }
                } catch (final RuntimeException ignored) {
                    complete = false;
                    continue;
                }

                final AbilityManaPart manaPart = probe.getManaPart();
                final List<Integer> outputMasks = parseOutputMasks(probe, manaPart);
                if (outputMasks.isEmpty()) {
                    // An available but dynamic/special mana ability is not safe to flatten into
                    // a static source model.  The legacy payment code remains authoritative.
                    complete = false;
                    continue;
                }
                final boolean uncertain = isUncertainManaActivation(probe, manaPart);
                sources.add(new ActionManaSource(source, outputMasks, uncertain));
            }
        }
        return new ManaSources(sources, complete);
    }

    private static List<Integer> parseOutputMasks(final SpellAbility ability,
            final AbilityManaPart manaPart) {
        if (manaPart == null) {
            return List.of();
        }
        final String produced = manaPart.getOrigProduced();
        if (produced == null || produced.isBlank()) {
            return List.of();
        }
        // Forge uses Combo for ordinary modal lands as well as for dynamic effects. A static
        // list such as "Combo G W" represents one mana chosen from those colors, not two mana.
        // Keep the dynamic forms below unsupported until their choices can be resolved safely.
        if (manaPart.isComboMana()) {
            return parseSimpleComboOutput(produced);
        }
        if (manaPart.isSpecialMana()) {
            return List.of();
        }
        if (manaPart.isAnyMana()) {
            return List.of((int) (produced.contains("AnyType")
                    ? ManaAtom.ALL_MANA_TYPES : ManaAtom.ALL_MANA_COLORS));
        }
        if (manaPart.isComboMana() || produced.contains("Chosen")
                || produced.contains("ColorID") || produced.contains("NotedColors")) {
            return List.of();
        }

        final List<Integer> outputMasks = new ArrayList<>();
        for (final String token : produced.split(" ")) {
            if (token.isBlank()) {
                continue;
            }
            try {
                if (token.chars().allMatch(Character::isDigit)) {
                    final int amount = Integer.parseInt(token);
                    for (int i = 0; i < amount; i++) {
                        outputMasks.add((int) ManaAtom.COLORLESS);
                    }
                    continue;
                }
            } catch (final NumberFormatException ignored) {
                return List.of();
            }
            final byte color = ManaAtom.fromName(token);
            if (color == 0) {
                return List.of();
            }
            outputMasks.add((int) color);
        }
        return outputMasks;
    }

    private static List<Integer> parseSimpleComboOutput(final String produced) {
        final String prefix = "Combo ";
        if (!produced.startsWith(prefix)) {
            return List.of();
        }
        final String choices = produced.substring(prefix.length()).trim();
        if (choices.equals("Any")) {
            return List.of((int) ManaAtom.ALL_MANA_COLORS);
        }
        if (choices.isBlank() || choices.contains("Chosen") || choices.contains("ColorIdentity")
                || choices.contains("ColorID") || choices.contains("NotedColors")
                || choices.contains("AnyDifferent")) {
            return List.of();
        }
        int mask = 0;
        for (final String token : choices.split(" ")) {
            if (token.isBlank()) {
                continue;
            }
            final byte color = ManaAtom.fromName(token);
            if (color == 0) {
                return List.of();
            }
            mask |= color;
        }
        return mask == 0 ? List.of() : List.of(mask);
    }

    private static boolean isUncertainManaActivation(final SpellAbility ability,
            final AbilityManaPart manaPart) {
        if (manaPart == null || !ability.getPayCosts().hasTapCost()
                || !manaPart.getManaRestrictions().isEmpty()
                || !manaPart.getExtraManaRestriction().isEmpty()) {
            // Non-tap mana abilities may be repeatable or have a hidden use limit.  Do not let
            // the combination selector infer repeated use until those limits are modeled.
            return true;
        }
        for (final CostPart part : ability.getPayCosts().getCostParts()) {
            if (!(part instanceof CostTap) && !(part instanceof CostPartMana)) {
                // Additional mana-source costs (sacrifice, counters, life, etc.) need their own
                // resource footprint.  They remain visible to legacy payment and diagnostics.
                return true;
            }
        }
        return false;
    }

    private record ManaSources(List<ActionManaSource> sources, boolean complete) {
    }
}
