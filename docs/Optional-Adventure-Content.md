# Optional Harry Potter Adventure content

Integration can include an optional Hogwarts estate with seven main stationary opponents, four native forest creature encounters, five roaming variants, and three HP variants of existing Adventure decks. Card scripts, tokens, custom editions, and card images remain external player custom content. Having the Adventure assets installed does not install those cards or grant ownership.

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
| Aragog | Stationary forest encounter only | No random encounter | 26 | 300–499 |
| Snape | Stationary Potions encounter only | No random encounter | 22 | 300–499 |

Roaming weight is 0.25, compared with 1 for many ordinary enemies. Roaming collisions start a duel directly. The seven main stationary Hogwarts opponents keep their optional battle dialogue and can be challenged before the roaming unlock. Each main HP victory awards two random nonbasic cards from that enemy's deck and 10–14 shards on Normal before equipment bonuses. This includes the five existing roaming variants; the three independently enabled native deck variants retain their original enemies' rewards. Mythic HP cards are possible, not guaranteed. Other difficulty settings scale life and random reward additions.

The engine uses difficulty thresholds 2 and 3 for these unlocks; they are not computed deck-power ratings or AI profiles. No global rank or AI behavior changes are made.

## Exploring Hogwarts

The south gate opens onto a large grounds map. Hagrid's hut is at the front-left; the Forbidden Forest starts at the front-right and runs north along the castle's eastern side. Aragog guards a webbed clearing, and Voldemort waits at the far northern end. The former Great Hall portal and separate Shadow Chamber have been removed.

Forest side paths also contain a boar, wolf, giant spider, and centaur using their existing native decks and sprites. The beasts battle on contact; the centaur offers an optional challenge. These stationary local variants award two deck cards, 80–119 gold, and 3–5 shards on Normal, with 13, 20, 15, and 16 life respectively. Their original global definitions and decks are unchanged.

The castle entrance leads into the enlarged Great Hall, with Harry and Ron. Its east doorway opens into the library. The rear-right stairs lead to an upper gallery and Dumbledore's office; the rear-left stairs descend to the dungeons, ingredient stores, and Snape's Potions classroom. Doorways and stairs work in both directions. Fourteen exploration caches provide modest gold, shards, a Preordain, and an HP Polyjuice Potion, using normal persistent pickup tracking. Enemies can be challenged again when revisiting their room.

Aragog's 60-card black-green deck has 26 Spider creatures spanning twelve different kinds, including the HP Aragog and Acromantula Brood alongside native spiders, graveyard support, and Spider Spawning. Snape's 60-card deck uses scry/surveil, Clues, Polyjuice, cauldrons, and control spells, with an Esper core and a small red splash for Sectumsempra. It has three copies of Snape and 24 lands. The forest uses Forge's existing animated black spider; Snape uses a generated head portrait with the native black wizard's world animations.

The eleven-room estate also includes hidden kitchens, the Room of Requirement, a tapestry staircase, and a Whomping Willow tunnel to the Shrieking Shack. Discovery flags keep their entrances unlocked across saves. Canonical entrances are distinguished from adapted shortcut destinations in the expansion details.

The one-time town quest **A Castle Out of Place** asks the player to find the displaced castle, speak to Dumbledore, and defeat Voldemort. The named victory completes it without returning to Dumbledore or town. It appears in the normal Shandalar town quest pool only when that world's Hogwarts is active and available. Earlier castle victories count through location history or the saved opponent win record; after meeting Dumbledore, a second battle is unnecessary. A completed quest is excluded from future offers. The one-time reward is 500 gold, 10 shards, and three random HP cards, in addition to normal battle loot.

The offer is uniformly chosen from eligible town quests, with no special rarity weight: before other eligibility filters, its share is 1/10 in forest/mountain/plains towns, 1/12 in island towns, 1/11 in swamp towns, and 1/17 in wastes towns. Normal town cooldowns and the side-quest limit still apply. Details and qualifications are in the expansion documentation.

See [Hogwarts expansion details](Hogwarts-Expansion.md) for the room graph and verification scope.

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
