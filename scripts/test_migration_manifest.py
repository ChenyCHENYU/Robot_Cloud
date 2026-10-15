import tempfile
import unittest
from pathlib import Path
import migration_manifest as manifest


class MigrationManifestTest(unittest.TestCase):
    def test_detects_added_deleted_modified_and_renamed_files(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'V1__base.sql').write_text('SELECT 1;\n')
            first = manifest.snapshot(root)
            self.assertEqual([], manifest.compare(first, manifest.snapshot(root)))
            (root / 'V1__base.sql').write_text('SELECT 2;\n')
            self.assertEqual(1, len(manifest.compare(first, manifest.snapshot(root))))
            (root / 'V2__later.sql').write_text('SELECT 3;\n')
            self.assertEqual(2, len(manifest.compare(first, manifest.snapshot(root))))
            (root / 'V1__base.sql').unlink()
            self.assertEqual(2, len(manifest.compare(first, manifest.snapshot(root))))
            (root / 'V1__renamed.sql').write_text('SELECT 1;\n')
            self.assertEqual(2, len(manifest.compare(first, manifest.snapshot(root))))

    def test_rejects_duplicate_versions_and_empty_scan(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with self.assertRaises(ValueError):
                manifest.snapshot(root)
            (root / 'V1__one.sql').write_text('SELECT 1;')
            (root / 'V1__two.sql').write_text('SELECT 2;')
            with self.assertRaises(ValueError):
                manifest.snapshot(root)

    def test_review_cannot_reset_a_published_boundary_or_rewrite_v6_to_v10(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for version in range(1, 11):
                (root / f'V{version}__base.sql').write_text(f'SELECT {version};\n')
            expected = manifest.snapshot(root, released_through=5)
            # The project already published V6–V10; an old manifest cannot unlock them.
            actual = manifest.reviewed_snapshot(root, expected)
            self.assertEqual(10, actual['releasedThrough'])
            for version in range(6, 11):
                migration = root / f'V{version}__base.sql'
                migration.write_text('SELECT 99;\n')
                with self.assertRaisesRegex(ValueError, 'released migrations are immutable'):
                    manifest.reviewed_snapshot(root, expected)
                migration.write_text(f'SELECT {version};\n')

    def test_review_allows_new_migrations_and_preserves_a_later_release_boundary(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for version in range(1, 12):
                (root / f'V{version}__base.sql').write_text(f'SELECT {version};\n')
            expected = manifest.snapshot(root, released_through=11)
            (root / 'V12__expand.sql').write_text('SELECT 12;\n')
            actual = manifest.reviewed_snapshot(root, expected)
            self.assertEqual(11, actual['releasedThrough'])
            self.assertEqual(12, len(actual['migrations']))
