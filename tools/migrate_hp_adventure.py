#!/usr/bin/env python3
"""Migrate a private legacy HP Adventure installation. Dry-run unless --apply.

Requires Python 3.9+. No profile/save paths are modified. Rollback validates hashes
before restoring files, so later user edits are never silently overwritten.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid
import zipfile

SNAPSHOT = "https://github.com/Nerdslayer/forge/releases/download/fork-windows-snapshot/"


def ensure_closed():
    if os.name != 'nt':
        return
    command = "@(Get-Process | Where-Object { $_.ProcessName -in @('forge','forge-adventure') -or $_.MainWindowTitle -match '^Forge(?:\\b| Adventure)' }).Count"
    result = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', command],
                            capture_output=True, text=True, check=True, creationflags=subprocess.CREATE_NO_WINDOW)
    if int(result.stdout.strip()) != 0:
        raise ValueError('Close Forge before replacing or rolling back game files')


def digest(path):
    if not path.exists():
        return None
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def target(root, relative):
    path = (root / relative).resolve()
    if path == root or root not in path.parents:
        raise ValueError(f'Path escapes installation: {relative}')
    return path


def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def encoded(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + '\n').encode('utf-8')


def verify_jar(path, adventure=False):
    with zipfile.ZipFile(path) as archive:
        if archive.read('snapshot-url.txt').decode().strip() != SNAPSHOT:
            raise ValueError(f'Wrong Integration update channel in {path}')
        if adventure and 'forge/adventure/util/AdventureContentPacks.class' not in archive.namelist():
            raise ValueError(f'Optional-content loader missing from {path}')


def card_name(line):
    match = re.match(r'^(\d+)\s+([^|]+)', line.strip())
    return (int(match[1]), match[2].strip()) if match else (0, '')


def undo_deck(text, baseline, removals, replacements):
    lines = text.splitlines()
    counts = {}
    for line in lines:
        count, name = card_name(line)
        counts[name] = counts.get(name, 0) + count
    if not any(counts.get(name, 0) for name in removals):
        return None
    for name, count in removals.items():
        if counts.get(name, 0) != count:
            raise ValueError(f'Unrecognized local HP copy count for {name}; review this deck before migration')
    lines = [line for line in lines if card_name(line)[1] not in removals]
    for name, count in replacements.items():
        indexes = [i for i, line in enumerate(lines) if card_name(line)[1] == name]
        if indexes:
            index = indexes[0]
            old_count, _ = card_name(lines[index])
            lines[index] = re.sub(r'^\s*\d+', str(old_count + count), lines[index])
        else:
            templates = [line for line in baseline.splitlines() if card_name(line)[1] == name]
            if not templates:
                raise ValueError(f'Cannot find stock printing for {name}')
            entry = re.sub(r'^\s*\d+', str(count), templates[0])
            main = next(i for i, line in enumerate(lines) if line.lower() == '[main]')
            lines.insert(main + 1, entry)
    return ('\n'.join(lines) + '\n').encode('utf-8')


def prepare(install, source, desktop, adventure):
    pack = source / 'optional/harry_potter'
    definitions = load(pack / 'enemies.json')
    ids = {item['name'] for item in definitions}
    labels = ids | {item.get('nameOverride') or item['name'] for item in definitions}
    poi_ids = {item['name'] for item in load(pack / 'points_of_interest.json')}
    changes = {}

    def change(relative, data=None, source_file=None, delete=False):
        path = target(install, relative)
        before = digest(path)
        after = None if delete else digest(source_file) if source_file else hashlib.sha256(data).hexdigest()
        if before != after:
            changes[relative] = {'relative': relative, 'before': before, 'after': after,
                                 'data': data, 'source': source_file, 'delete': delete}

    for logical, owned in [('world/enemies.json', ids), ('world/points_of_interest.json', poi_ids)]:
        relative = 'res/adventure/common/' + logical
        path = target(install, relative)
        original = load(path)
        stripped = [record for record in original if record['name'] not in owned]
        if stripped != original:
            change(relative, encoded(stripped))

    for plane in ['common', 'Shandalar']:
        for color in ['colorless', 'white', 'blue', 'black', 'red', 'green']:
            logical = f'world/biomes/{color}.json'
            relative = f'res/adventure/{plane}/{logical}'
            path = target(install, relative)
            if not path.is_file():
                continue
            original = load(path)
            stripped = dict(original)
            stripped['enemies'] = [name for name in original.get('enemies', []) if name not in labels]
            stripped['pointsOfInterest'] = [name for name in original.get('pointsOfInterest', []) if name not in poi_ids]
            # This membership was introduced solely for the Arthur deck variant.
            stock = source / plane / logical
            if not stock.exists():
                stock = source / 'common' / logical
            baseline = load(stock)
            if color == 'white' and 'Human Captain' not in baseline.get('enemies', []):
                stripped['enemies'] = [name for name in stripped['enemies'] if name != 'Human Captain']
            if plane == 'Shandalar' and not (source / plane / logical).exists() and stripped == baseline:
                change(relative, delete=True)
            elif stripped != original:
                change(relative, encoded(stripped))

    decks = [
        ('standard/humanelite.dck', {'Arthur Weasley, Muggle Enthusiast': 2}, {'Mother of Runes': 2}),
        ('standard/esper_artificer.dck', {'Severus Snape, the Half-Blood Prince': 2,
          'Regulus Black, Defier of the Dark Lord': 1, 'Kreacher, Loyal House-Elf': 2},
         {'Ghalma the Shaper': 2, 'Sharding Sphinx': 2, 'Geth, Lord of the Vault': 1}),
        ('miniboss/thecheff.dck', {"Hufflepuff's Cup": 2}, {'Candy Trail': 2}),
    ]
    for logical, removals, replacements in decks:
        relative = 'res/adventure/common/decks/' + logical
        path = target(install, relative)
        data = undo_deck(path.read_text(encoding='utf-8-sig'),
                         (source / 'common/decks' / logical).read_text(encoding='utf-8-sig'), removals, replacements)
        if data is not None:
            change(relative, data)

    for directory in ['optional/harry_potter', 'common/maps/map/hogwarts',
                      'common/sprites/enemy/harry_potter', 'common/sprites/locations/harry_potter',
                      'common/decks/harry_potter']:
        for path in sorted((source / directory).rglob('*')):
            if path.is_file():
                change('res/adventure/' + path.relative_to(source).as_posix(), source_file=path)
    for path, is_adventure in [(desktop, False), (adventure, True)]:
        if path:
            verify_jar(path, is_adventure)
            if not target(install, path.name).exists():
                raise ValueError('JAR filename/version differs from installed launcher; use a complete installer')
            change(path.name, source_file=path)
    return list(changes.values())


def apply_changes(install, backup_root, changes):
    ensure_closed()
    if not changes:
        return {'state': 'unchanged', 'operations': []}
    stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8]
    backup = (backup_root / ('hp-content-' + stamp)).resolve()
    if install == backup or install in backup.parents:
        raise ValueError('Backups must be outside the game installation')
    backup.mkdir(parents=True)
    records = [{key: item[key] for key in ['relative', 'before', 'after', 'delete']} for item in changes]
    receipt = {'state': 'prepared', 'install': str(install), 'backup': str(backup), 'operations': records}
    receipt_path = backup / 'receipt.json'
    for item in changes:
        path = target(install, item['relative'])
        if digest(path) != item['before']:
            raise ValueError(f'File changed since planning: {path}')
        if item['before']:
            destination = target(backup, item['relative'])
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, destination)
    receipt_path.write_bytes(encoded(receipt))
    try:
        for item in changes:
            path = target(install, item['relative'])
            if digest(path) != item['before']:
                raise ValueError(f'Concurrent modification: {path}')
            if item['delete']:
                path.unlink()
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                temporary = path.with_name(path.name + '.hp-' + uuid.uuid4().hex)
                try:
                    if item['source']:
                        shutil.copy2(item['source'], temporary)
                    else:
                        temporary.write_bytes(item['data'])
                    if digest(temporary) != item['after']:
                        raise ValueError('Prepared content changed before installation')
                    os.replace(temporary, path)
                finally:
                    if temporary.exists():
                        temporary.unlink()
            if digest(path) != item['after']:
                raise ValueError(f'Installed hash mismatch: {path}')
        receipt['state'] = 'complete'
    except Exception:
        receipt['state'] = 'failed; restore with --rollback after reviewing the receipt'
        receipt_path.write_bytes(encoded(receipt))
        rollback(receipt_path)
        raise
    receipt_path.write_bytes(encoded(receipt))
    return {'state': receipt['state'], 'receipt': str(receipt_path), 'operations': records}


def rollback(receipt_path):
    ensure_closed()
    receipt = load(receipt_path)
    install = Path(receipt['install']).resolve()
    backup = Path(receipt['backup']).resolve()
    # Validate every precondition before any restoration.
    for item in receipt['operations']:
        current = digest(target(install, item['relative']))
        if current not in [item['after'], item['before']]:
            raise ValueError(f'Later user edit blocks rollback: {item["relative"]}')
        if item['before'] and digest(target(backup, item['relative'])) != item['before']:
            raise ValueError(f'Backup hash mismatch: {item["relative"]}')
    for item in reversed(receipt['operations']):
        path = target(install, item['relative'])
        if item['before']:
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target(backup, item['relative']), path)
        elif path.exists():
            path.unlink()
    receipt['state'] = 'rolled back'
    receipt_path.write_bytes(encoded(receipt))
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--install-root', type=Path)
    parser.add_argument('--adventure-source', type=Path)
    parser.add_argument('--desktop-jar', type=Path)
    parser.add_argument('--adventure-jar', type=Path)
    parser.add_argument('--backup-root', type=Path)
    parser.add_argument('--plan-out', type=Path)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--rollback', type=Path)
    args = parser.parse_args()
    if args.rollback:
        print(json.dumps(rollback(args.rollback.resolve()), indent=2))
        return
    if not args.install_root or not args.adventure_source:
        parser.error('--install-root and --adventure-source are required')
    install = args.install_root.resolve()
    source = args.adventure_source.resolve()
    if not (install / 'res/adventure/common/world/enemies.json').is_file():
        parser.error('Not an existing Forge Adventure installation')
    if args.apply and not (args.desktop_jar and args.adventure_jar and args.backup_root):
        parser.error('--apply requires both validated JARs and --backup-root')
    changes = prepare(install, source, args.desktop_jar, args.adventure_jar)
    report = {'state': 'dry run', 'install': str(install), 'binariesProvided': bool(args.desktop_jar and args.adventure_jar),
              'operations': [{key: item[key] for key in ['relative', 'before', 'after', 'delete']} for item in changes]}
    if args.apply:
        report = apply_changes(install, args.backup_root.resolve(), changes)
    if args.plan_out:
        args.plan_out.parent.mkdir(parents=True, exist_ok=True)
        args.plan_out.write_bytes(encoded(report))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(f'HP migration stopped: {error}', file=sys.stderr)
        sys.exit(1)
