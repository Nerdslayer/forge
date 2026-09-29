# Effect Synergy and Removal Benchmark Pool

This pool concentrates cards that exercise AI effect-relationship scoring and cards that must choose
among several removal targets. At multiplier 1, its 27 decks schedule 1,458 games.

Most lists are unchanged copies of existing Forge decks:

- `GAM_1_60_White Soul Sisters Generated Deck_3_16.dck`
- `GAS_24_38_Saproling Swarm Saprolings Generated Deck_930_19.dck`
- `GAL_20_42_Artifact Affinity Deck Generated Deck_21_16.dck`
- `GAL_1_28_Legacy Merfolk Merfolks Generated Deck_5_19.dck`
- `GAP_18_12_Red Aggro Pioneer Generated Deck_3_8.dck`
- `GAS_23_25_Control Mono Black Generated Deck_810_17.dck`
- `GAS_21_8_Esper Control Dance Generated Deck_940_19.dck`

`Riddler 3.dck` is adapted from the existing Quest duel of that name by retaining its main deck in
ordinary constructed-deck format. `Effect Analysis Counters and Tokens.dck` is a purpose-built
green-white list for counter choices, counter transfer, token creation, and the Rosie/Jet engine.
`Effect Analysis Dinosaurs.dck` is purpose-built around enrage damage and copied Polyraptor tokens,
using ordinary damage spells instead of an automatic infinite-damage engine.
`Bounce Tempo.dck` concentrates on returning opposing creatures to hand while also playing
re-castable ETB creatures and token makers. Its mirror match can present token targets alongside
creatures whose ETB value makes bouncing them less attractive.

The expanded existing-deck coverage is intentional:

- `GAM_27_Yawgmoth, Thran Physician based deck_138_0.dck` — sacrifice, death/zone changes, counters,
  card draw, and life-loss interactions.
- `GAM_16_Chatterfang, Squirrel General based deck_134_0.dck` and
  `GAM_33_Hapatra, Vizier of Poisons based deck_39_0.dck` — token, counter, and sacrifice engines.
- `GAS_40_Ozolith, the Shattered Spire based deck_135_0.dck` and
  `GAS_38_Halana and Alena, Partners based deck_71_1.dck` — persistent counter production and
  counter-scaled creatures.
- `GAM_24_Waste Not based deck_39_1.dck` and `GAM_32_Fractured Sanity based deck_72_1.dck` —
  discard, card draw, graveyard/zone movement, and resource consequences.
- `GAP_23_21_Jeskai Superfriends Colors Generated Deck_3_16.dck` — planeswalker loyalty, control,
  sweepers, and broad target selection.
- `Planeswalker Deck - ELD Walkers.dck` and `Planeswalker Deck - Bant Superfriends.dck` add dense,
  multi-color planeswalker coverage for loyalty-mode selection and outcome evaluation.
- `GAS_16_92_Boros Equipment White Generated Deck_918_19.dck` — Aura/Equipment attachment and
  combat-oriented creatures.
- `GAM_18_Tovolar, Dire Overlord based deck_23_0.dck` — transforming/state-changing permanents,
  combat, and removal.
- `GAS_39_Gala Greeters based deck_48_0.dck` — a representative choose-one ability whose branches
  create a token, add a counter, or gain life.
- `GAM_1_Adeline, Resplendent Cathar based deck_130_0.dck` — attack-triggered token creation,
  combat production, and creature-type static effects.
- `GAM_15_Wooded Foothills based deck_4_1.dck` — control-changing creature theft alongside
  battlefield interaction.
- `GAM_10_70_Soul Sisters Deck Generated Deck_35_15.dck` — opponent-owned Myr tokens from
  `Genesis Chamber` alongside life-gain and token-recipient interactions.

The original pool remains represented by the Soul Sisters, Saproling, Affinity, Merfolk, Red Aggro,
Mono Black, Esper Control, Riddler, and counters/tokens Rosie-Jet list above. Together, the pool
covers the common implemented production families (`TOKEN_CREATED`, `COUNTER_ADDED`, `LIFE_GAINED`,
`LIFE_LOST`, `CARD_DRAWN`, `CARD_DISCARDED`, `DAMAGE_DEALT`, `ATTACKED_OR_BLOCKED`,
`TAPPED_OR_UNTAPPED`, `SACRIFICED`, and `ZONE_CHANGED`) and exercises the implemented outcome adapters for counters,
permanent evaluation, tokens, life, mana/discard/draw, damage, sacrifice, control, copying,
attachments, animation/state changes, and combat restrictions where the selected cards expose them.

Run this pool by passing:

```
--deck-dir res/benchmark/EffectSynergyRemoval
```
