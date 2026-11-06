"""File-transaction tests; no game/profile paths are used."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import migrate_hp_adventure as migration


class MigrationTest(unittest.TestCase):
    def setUp(self):
        temporary_root = Path(__file__).resolve().parents[1] / 'target/hp-migration-tests'
        temporary_root.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix='forge-hp-migration-', dir=temporary_root)
        self.root = Path(self.temporary.name).resolve()
        self.assertEqual(self.root.parent, temporary_root.resolve())
        self.install = self.root / 'game'
        self.source = self.root / 'source'
        self.backups = self.root / 'backups'
        self.closed = patch.object(migration, 'ensure_closed')
        self.closed.start()
        self.addCleanup(self.closed.stop)
        self.addCleanup(self.temporary.cleanup)
        self.saved = self.root / 'profile' / '3_save_slot.sav'
        self.write(self.saved, b'untouched player save')
        names = ['Dumbledore', 'Gryffindor', 'Weasley Family', 'Hagrid', 'Voldemort']
        enemies = [{'name': 'HP ' + name} for name in names] + [{'name': 'HP Roaming ' + name} for name in names]
        self.json(self.source / 'optional/harry_potter/enemies.json', enemies)
        self.json(self.source / 'optional/harry_potter/points_of_interest.json', [{'name': 'Hogwarts'}])
        self.json(self.source / 'optional/harry_potter/manifest.json', {'schemaVersion': 1})
        self.json(self.install / 'res/adventure/common/world/enemies.json', [{'name': 'Local Sentinel', 'life': 99}] + enemies)
        self.json(self.install / 'res/adventure/common/world/points_of_interest.json', [{'name': 'Native Town'}, {'name': 'Hogwarts'}])
        for color in ['colorless', 'white', 'blue', 'black', 'red', 'green']:
            baseline = {'enemies': ['Native Enemy'], 'pointsOfInterest': ['Native Town'], 'otherProperty': 42}
            self.json(self.source / f'common/world/biomes/{color}.json', baseline)
            legacy = dict(baseline, enemies=['Native Enemy', 'HP Roaming Hagrid'], pointsOfInterest=['Native Town', 'Hogwarts'])
            if color == 'white':
                legacy['enemies'].append('Human Captain')
            self.json(self.install / f'res/adventure/common/world/biomes/{color}.json', legacy)
            if color == 'colorless':
                legacy['localNote'] = 'preserve this override'
            self.json(self.install / f'res/adventure/Shandalar/world/biomes/{color}.json', legacy)
        stock = {
            'standard/humanelite.dck': '4 Mother of Runes|SET|1\n56 Plains',
            'standard/esper_artificer.dck': '2 Ghalma the Shaper|SET|1\n2 Sharding Sphinx|SET|1\n1 Geth, Lord of the Vault|SET|1\n72 Island',
            'miniboss/thecheff.dck': '2 Candy Trail|SET|1\n180 Forest',
        }
        legacy = {
            'standard/humanelite.dck': '2 Mother of Runes|SET|1\n2 Arthur Weasley, Muggle Enthusiast|HP|1\n56 Plains',
            'standard/esper_artificer.dck': '2 Severus Snape, the Half-Blood Prince|HP|1\n1 Regulus Black, Defier of the Dark Lord|HP|1\n2 Kreacher, Loyal House-Elf|HP|1\n72 Island',
            'miniboss/thecheff.dck': "2 Hufflepuff's Cup|HP|1\n180 Forest",
        }
        for relative in stock:
            self.write(self.source / 'common/decks' / relative, '[Main]\n' + stock[relative] + '\n')
            self.write(self.install / 'res/adventure/common/decks' / relative, '[Main]\n' + legacy[relative] + '\n')
            self.write(self.source / 'common/decks/harry_potter/variants' / Path(relative).name, '[Main]\n' + legacy[relative] + '\n')
        self.write(self.install / 'res/adventure/common/decks/standard/private.dck', b'private unrelated edit')
        self.write(self.source / 'common/maps/map/hogwarts/hall.tmx', b'fixture map')
        self.desktop = self.root / 'binaries/desktop.jar'
        self.adventure = self.root / 'binaries/adventure.jar'
        for path, adventure in [(self.desktop, False), (self.adventure, True)]:
            path.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('snapshot-url.txt', migration.SNAPSHOT)
                if adventure:
                    archive.writestr('forge/adventure/util/AdventureContentPacks.class', b'test-only entry')
            self.write(self.install / path.name, b'old binary fixture')

    @staticmethod
    def write(path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value.encode() if isinstance(value, str) else value)

    def json(self, path, value):
        self.write(path, migration.encoded(value))

    def snapshot(self):
        return {p.relative_to(self.install).as_posix(): migration.digest(p) for p in self.install.rglob('*') if p.is_file()}

    def prepare(self):
        return migration.prepare(self.install, self.source, self.desktop, self.adventure)

    def test_apply_repeat_and_rollback_preserve_unrelated_data(self):
        before = self.snapshot()
        changes = self.prepare()
        self.assertEqual(self.snapshot(), before, 'dry run must not mutate installation')
        result = migration.apply_changes(self.install, self.backups, changes)
        self.assertEqual(result['state'], 'complete')
        enemies = migration.load(self.install / 'res/adventure/common/world/enemies.json')
        self.assertEqual(enemies, [{'name': 'Local Sentinel', 'life': 99}])
        retained = migration.load(self.install / 'res/adventure/Shandalar/world/biomes/colorless.json')
        self.assertEqual(retained['localNote'], 'preserve this override')
        self.assertFalse((self.install / 'res/adventure/Shandalar/world/biomes/red.json').exists())
        self.assertEqual((self.install / 'res/adventure/common/decks/standard/private.dck').read_bytes(), b'private unrelated edit')
        self.assertEqual(self.saved.read_bytes(), b'untouched player save')
        self.assertEqual(self.prepare(), [], 'second migration must be a no-op')
        migration.rollback(Path(result['receipt']))
        self.assertEqual(self.snapshot(), before)

    def test_wrong_update_channel_cannot_change_files(self):
        before = self.snapshot()
        with zipfile.ZipFile(self.adventure, 'w') as archive:
            archive.writestr('snapshot-url.txt', 'https://wrong.example/')
        with self.assertRaises(ValueError):
            self.prepare()
        self.assertEqual(self.snapshot(), before)

    def test_concurrent_edit_prevents_any_installation(self):
        changes = self.prepare()
        self.write(self.install / changes[0]['relative'], b'later user edit')
        before = self.snapshot()
        with self.assertRaises(ValueError):
            migration.apply_changes(self.install, self.backups, changes)
        self.assertEqual(self.snapshot(), before)

    def test_rollback_refuses_to_overwrite_later_user_edit(self):
        result = migration.apply_changes(self.install, self.backups, self.prepare())
        self.write(self.install / 'res/adventure/common/world/enemies.json', b'later edit')
        before = self.snapshot()
        with self.assertRaises(ValueError):
            migration.rollback(Path(result['receipt']))
        self.assertEqual(self.snapshot(), before)

    def test_interrupted_install_rolls_back_as_a_pair(self):
        before = self.snapshot()
        real_replace = migration.os.replace
        calls = 0

        def fail_second(source, destination):
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError('simulated interrupted installation')
            return real_replace(source, destination)

        with patch.object(migration.os, 'replace', side_effect=fail_second):
            with self.assertRaises(OSError):
                migration.apply_changes(self.install, self.backups, self.prepare())
        self.assertEqual(self.snapshot(), before)

    def test_changed_copy_counts_require_review(self):
        deck = self.install / 'res/adventure/common/decks/standard/humanelite.dck'
        self.write(deck, deck.read_text().replace('2 Arthur', '3 Arthur'))
        before = self.snapshot()
        with self.assertRaises(ValueError):
            self.prepare()
        self.assertEqual(self.snapshot(), before)


if __name__ == '__main__':
    unittest.main()
