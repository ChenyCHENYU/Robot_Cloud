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
RELEASED_BASELINE = 10


def snapshot(directory=MIGRATIONS, released_through=0):
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
    return {'schemaVersion': 1, 'releasedThrough': released_through, 'hashAlgorithm': 'SHA-256', 'migrations': sorted(migrations, key=lambda m: m['version'])}


def compare(expected, actual):
    old = {m['version']: m for m in expected['migrations']}
    new = {m['version']: m for m in actual['migrations']}
    return [f'V{version}: missing, unexpected, renamed, or changed bytes' for version in sorted(old.keys() | new.keys()) if old.get(version) != new.get(version)]


def reviewed_snapshot(directory, expected, minimum_released=RELEASED_BASELINE):
    """Preserve the published boundary and reject byte changes before a rewrite."""
    released = max(minimum_released, expected.get('releasedThrough', 0))
    actual = snapshot(directory, released_through=released)
    violations = [item for item in compare(expected, actual)
                  if int(item.split(':')[0][1:]) <= released]
    if violations:
        raise ValueError('released migrations are immutable: ' + '; '.join(violations))
    return actual


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    expected = json.loads(MANIFEST.read_text()) if MANIFEST.exists() else {'migrations': []}
    if args.write:
        actual = reviewed_snapshot(MIGRATIONS, expected)
        MANIFEST.write_text(json.dumps(actual, ensure_ascii=False, indent=2) + '\n')
        print(f'manifest written: {len(actual["migrations"])} migrations; review the diff')
    else:
        if expected.get('releasedThrough', 0) < RELEASED_BASELINE:
            raise ValueError(f'release boundary must preserve V1–V{RELEASED_BASELINE}')
        actual = snapshot(released_through=expected['releasedThrough'])
        differences = compare(expected, actual)
        if differences:
            raise ValueError('migration manifest drift: ' + '; '.join(differences))
        print(f'migration manifest verified: {len(actual["migrations"])} files')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
