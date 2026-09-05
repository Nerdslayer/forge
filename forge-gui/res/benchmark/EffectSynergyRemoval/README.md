# Effect Synergy and Removal Benchmark Pool

This pool concentrates cards that exercise AI effect-relationship scoring and cards that must choose
among several removal targets. At multiplier 1, its nine decks schedule 162 games.

Most lists are unchanged copies of existing Forge decks:

- `GAM_1_60_White Soul Sisters Generated Deck_3_16.dck`
- `GAS_24_38_Saproling Swarm Saprolings Generated Deck_930_19.dck`
- `GAL_20_42_Artifact Affinity Deck Generated Deck_21_16.dck`
- `GAL_1_28_Legacy Merfolk Merfolks Generated Deck_5_19.dck`
- `GAP_18_12_Red Aggro Pioneer Generated Deck_3_8.dck`
- `GAS_23_25_Control Mono Black Generated Deck_810_17.dck`
- `GAS_21_8_Esper Control Dance Generated Deck_940_19.dck`

`Riddler 3.dck` is adapted from the existing Quest duel of that name by retaining its main deck in
ordinary constructed-deck format. `Rosie Jet Tokens.dck` is purpose-built to repeatedly exercise the
token-created and +1/+1-counter relationship between Rosie Cotton of South Lane and Royal Talon
Fighter Jet.

Run this pool by passing:

```
--deck-dir res/benchmark/EffectSynergyRemoval
```
