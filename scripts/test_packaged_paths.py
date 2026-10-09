import tempfile
import unittest
from pathlib import Path
from verify_packaged_mappers import safe_target


class PackagedPathTest(unittest.TestCase):
    def test_symlinked_temp_root_is_valid(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            real = root / 'real'
            real.mkdir()
            alias = root / 'alias'
            alias.symlink_to(real, target_is_directory=True)
            self.assertEqual((real / 'BOOT-INF/classes/example.class').resolve(), safe_target(alias, 'BOOT-INF/classes/example.class'))

    def test_absolute_and_parent_traversal_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            for name in ['../outside.txt', '/outside.txt', 'BOOT-INF/../../outside.txt']:
                with self.assertRaises(ValueError):
                    safe_target(Path(folder), name)

    def test_existing_nested_symlink_cannot_escape_root(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder) / 'root'
            root.mkdir()
            outside = Path(folder) / 'outside'
            outside.mkdir()
            (root / 'BOOT-INF').symlink_to(outside, target_is_directory=True)
            with self.assertRaises(ValueError):
                safe_target(root, 'BOOT-INF/classes/example.class')
