"""Pure-memory RESP/cleanup contract checks; never connect to fixtures."""
import importlib.util
import io
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('permission_cache_refill_probe',
                                              Path(__file__).with_name('permission-cache-refill-probe.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RefillContractTest(unittest.TestCase):
    def test_list_grant_selects_real_menu_page_without_assuming_a_list_button(self):
        catalogue = [
            {'id': 'menu-sys-user', 'type': 'menu', 'permCode': 'sys:user:list'},
            {'id': 'btn-user-query', 'type': 'button', 'permCode': 'sys:user:query'},
            {'id': 'untrusted-list-button', 'type': 'button', 'permCode': 'sys:user:list'},
            {'id': 'directory', 'type': 'directory', 'permCode': 'sys:user:list'}]
        self.assertEqual(['menu-sys-user'],
                         [item['id'] for item in module.list_page_resources(catalogue)])

    def test_dump_binary_is_opaque_and_preserves_nul_and_crlf(self):
        raw = b'\x00serialized\r\n\xff\x00'
        frame = b'$' + str(len(raw)).encode() + b'\r\n' + raw + b'\r\n'
        self.assertEqual(raw, module.read_resp(io.BytesIO(frame)))

    def test_redis_errors_expose_only_fixed_category(self):
        with self.assertRaises(module.ProbeFailure) as caught:
            module.read_resp(io.BytesIO(b'-ERR arbitrary downstream secret\r\n'))
        self.assertEqual('redis_command_rejected', str(caught.exception))

    def test_exact_keys_reject_identity_segment_injection(self):
        self.assertEqual('robot:iteration-a1c6:system:permissions:company:user:db-v41',
                         module.permission_key('iteration-a1c6', 'company', 'user', 41))
        for company, user, epoch in (('company:*', 'user', 41), ('company', 'user:db-v1', 41),
                                     ('company', 'user', 0)):
            with self.assertRaises(module.ProbeFailure):
                module.permission_key('iteration-a1c6', company, user, epoch)

    def fixture(self, member_name='refill_fixture'):
        instance = object.__new__(module.RefillProbe)
        instance.user_id, instance.role_id = 'fixture-user', 'fixture-role'
        instance.username, instance.role_code = 'refill_fixture', 'REFILL_FIXTURE'
        instance.admin_token, instance.member_token = 'fixture-admin', 'fixture-member'
        instance.old_payload = b'opaque-fixture'
        instance.password = instance.admin_password = 'fixture-only'
        instance.cache_keys = {'robot:iteration-a1c6:system:permissions:company:fixture-user:db-v41'}
        instance.cleanup_state = {}
        calls = []
        def success(method, path, body=None, token=None):
            calls.append((method, path))
            if method == 'GET' and path.startswith('/sys/users/'):
                return {'username': member_name, 'membershipRevision': 41, 'revision': 27}
            if method == 'GET' and path.startswith('/sys/roles/'):
                return {'roleCode': 'REFILL_FIXTURE', 'revision': 23}
            return None
        instance.success = success
        redis_calls = []
        def redis(*parts):
            redis_calls.append(parts)
            return 0 if parts[0] == 'EXISTS' else 1
        instance.redis = redis
        return instance, calls, redis_calls

    def test_cleanup_uses_current_member_and_role_versions_and_exact_cache_keys(self):
        instance, calls, redis_calls = self.fixture()
        self.assertEqual([], instance.cleanup())
        self.assertIn(('DELETE', '/sys/users/fixture-user?expectedRevision=41'), calls)
        self.assertIn(('DELETE', '/sys/roles/fixture-role?expectedRevision=23'), calls)
        self.assertTrue(instance.cleanup_state['platform_account_retained_by_api'])
        self.assertEqual(['DEL', 'EXISTS'], [call[0] for call in redis_calls])
        self.assertIsNone(instance.old_payload)
        self.assertIsNone(instance.admin_token)
        self.assertIsNone(instance.member_token)

    def test_changed_member_identity_cannot_be_deleted_by_cleanup(self):
        instance, calls, _ = self.fixture(member_name='other-fixture')
        self.assertIn('member_cleanup_failed', instance.cleanup())
        self.assertFalse(any(method == 'DELETE' and path.startswith('/sys/users/') for method, path in calls))


if __name__ == '__main__':
    unittest.main()
