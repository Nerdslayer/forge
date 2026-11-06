# Optional Harry Potter Adventure content

Integration can include an optional Hogwarts hub, five opponents and roaming variants, and three HP variants of existing Adventure decks. Card scripts, tokens, custom editions, and card images remain external player custom content. Having the Adventure assets installed does not install those cards or grant ownership.

The pack is registered by `forge-gui/res/adventure/optional/harry_potter/manifest.json`. The engine validates required card printings/rules, transforming faces, token scripts, resource files, and complete deck counts. It activates the Hogwarts group atomically; Human Elite, Esper Artificer, and Cook are independently checked. An unavailable variant uses its ordinary deck. Missing card images alone do not disable content.

The default Shandalar plane is supported. Other planes are unchanged. Enabled new worlds generate one Hogwarts Castle in the colorless region. Existing worlds are not automatically modified; previously placed locations retain their saved position. Restart/resource reload evaluates availability again.

Normal delivery is the fork's automated Windows snapshot build, triggered by a push to `integration`. The player runs the resulting installer. Source changes and build verification do not authorize an agent to modify an installed game directly. User custom card scripts, editions, and art remain separately managed custom content.

| Opponent | Additional regions beyond colorless | Random encounter unlock | Normal life | Guaranteed Normal gold |
| --- | --- | --- | ---: | --- |
| Harry / Gryffindor | White, red | 60 total wins | 20 | 200–299 |
| Ron / Weasley | Red | 60 total wins | 20 | 200–299 |
| Hagrid | Green | 60 total wins | 24 | 200–299 |
| Dumbledore | White, blue | 151 total wins | 22 | 300–499 |
| Voldemort | Black | 151 total wins | 28 | 400–599 |

Roaming weight is 0.25, compared with 1 for many ordinary enemies. Roaming collisions start a duel directly. Stationary Hogwarts opponents keep their optional battle dialogue and can be challenged before the roaming unlock. Each victory also awards one random nonbasic card from that enemy's deck and 10–14 shards on Normal before equipment bonuses. Mythic HP cards are possible, not guaranteed. Other difficulty settings scale life and random reward additions.

The engine uses difficulty thresholds 2 and 3 for these unlocks; they are not computed deck-power ratings or AI profiles. No global rank or AI behavior changes are made.

## Save compatibility

Unavailable owned POI definitions remain resolvable for existing saves, but are not generated, displayed, navigated to, or entered. A native placeholder supports dormant record loading when custom presentation assets are absent. Reinstalling dependencies restores access to the same POI identity and associated room/shop state. Unavailable transient roaming actors are skipped without dropping later ordinary actors.

Before player/world mutation, the loader checks saved card references and POI names. A save that requires unresolved owned custom cards or an unresolvable location is declined with a missing-content message rather than being partially loaded or reaching the world-regeneration fallback. Restore the required custom content and retry. Merely disabling encounters cannot make missing card rules playable.

## Migrating a legacy manually edited installation

The migration tool is an optional, manually invoked maintenance path for legacy private installations; it does not run on game startup or during the automated build. Do not run it against a player's installation without explicit permission. Close Forge first. It requires Python 3.9 or newer, an existing installation, the new Adventure resource tree, and the newly built desktop/Adventure JARs. Both JARs must contain the Integration snapshot URL; the Adventure JAR must contain `AdventureContentPacks`.

Example dry run from the repository root (replace example paths with the actual installation/build locations):

```powershell
python tools/migrate_hp_adventure.py `
  --install-root C:/Games/Forge `
  --adventure-source forge-gui/res/adventure `
  --desktop-jar forge-gui-desktop/target/forge-gui-desktop-2.0.16-SNAPSHOT-jar-with-dependencies.jar `
  --adventure-jar forge-gui-mobile-dev/target/forge-gui-mobile-dev-2.0.16-SNAPSHOT-jar-with-dependencies.jar `
  --plan-out C:/ForgeBackups/hp-plan.json
```

Review the plan, then repeat with `--apply --backup-root C:/ForgeBackups`. The tool backs up replaced/deleted files, verifies preconditions and installed hashes, removes only legacy HP registrations, removes redundant plane biome overrides, preserves nonredundant overrides and unrelated edits, and restores the HP substitutions in stock decks while placing the HP variants separately. It copies only managed content and the two JARs; saves, profile files, custom scripts, player decks, and unrelated resources are not deployment targets. An unrecognized copy-count or concurrent-edit conflict stops the migration.

The receipt contains exact backup paths and before/after hashes. A repeated migration should report no changes. To restore the paired pre-migration JARs/data:

```powershell
python tools/migrate_hp_adventure.py --rollback C:/ForgeBackups/hp-content-TIMESTAMP/receipt.json
```

Rollback refuses to overwrite later edits to affected files. An interrupted installation attempts rollback automatically. Retain the backup/receipt until the new installation has been tested. Do not replace only the engine with an older/official build that cannot resolve Hogwarts while retaining a save that contains that location.

## Development and verification

The loader is `forge-gui-mobile/src/forge/adventure/util/AdventureContentPacks.java`. Catalog, biome, deck, map, quest/event-selection, and save-load guards call it. Base enemy/POI registries and stock decks remain normal resources; enabled additions are merged at runtime. Native card rules and player-profile data are not stored in this contribution.

Focused TestNG coverage is in `AdventureContentPackTest`, `AdventureContentAvailabilityTest`, and `AdventureContentSaveCompatibilityTest` in the desktop test tree. Migration transaction tests run with:

```text
python -m unittest discover -s tools -p test_migrate_hp_adventure.py -v
```

For an integration package, preserve `https://github.com/Nerdslayer/forge/releases/download/fork-windows-snapshot/` in both JARs' `snapshot-url.txt`. The fork snapshot workflow already injects this URL. Local builds must inject and verify it explicitly before installation.
