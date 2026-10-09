#!/usr/bin/env python3
"""Check reviewed migration bytes. --write is an explicit review action, never a CI repair."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / 'contracts/database/migrations.json'
MIGRATIONS = ROOT / 'robot-services/robot-system/src/main/resources/db/migration'


def snapshot(directory=MIGRATIONS):
    migrations = []
    seen = set()
    for path in directory.glob('V*__*.sql'):
        match = re.fullmatch(r'V(\d+)__(.+)\.sql', path.name)
        if not match or int(match[1]) in seen:
            raise ValueError(f'invalid/duplicate migration version: {path.name}')
        version = int(match[1])
        seen.add(version)
        raw = path.read_bytes()
        migrations.append({'version': version, 'file': path.name, 'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw)})
    if not migrations:
        raise ValueError('no migration found')
    return {'schemaVersion': 1, 'releasedThrough': 5, 'hashAlgorithm': 'SHA-256', 'migrations': sorted(migrations, key=lambda m: m['version'])}


def compare(expected, actual):
    old = {m['version']: m for m in expected['migrations']}
    new = {m['version']: m for m in actual['migrations']}
    return [f'V{version}: missing, unexpected, renamed, or changed bytes' for version in sorted(old.keys() | new.keys()) if old.get(version) != new.get(version)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    actual = snapshot()
    if args.write:
        if MANIFEST.exists():
            expected = json.loads(MANIFEST.read_text())
            violations = [v for v in compare(expected, actual) if int(v.split(':')[0][1:]) <= expected['releasedThrough']]
            if violations:
                raise ValueError('released migrations are immutable: ' + '; '.join(violations))
        MANIFEST.write_text(json.dumps(actual, ensure_ascii=False, indent=2) + '\n')
        print(f'manifest written: {len(actual["migrations"])} migrations; review the diff')
    else:
        differences = compare(json.loads(MANIFEST.read_text()), actual)
        if differences:
            raise ValueError('migration manifest drift: ' + '; '.join(differences))
        print(f'migration manifest verified: {len(actual["migrations"])} files')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
