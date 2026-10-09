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
