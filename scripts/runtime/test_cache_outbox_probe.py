"""Pure-memory probe-contract tests; no HTTP, sockets, credentials or SQL."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('cache_outbox_probe', Path(__file__).with_name('cache-outbox-probe.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ContractTest(unittest.TestCase):
    def test_mysql_fixture_session_uses_utc_independently_of_server_timezone(self):
        instance = object.__new__(module.Probe)
        instance.args = SimpleNamespace(mysql_port=33317, database='robot_iteration_fixture')
        completed = SimpleNamespace(returncode=0, stdout='utc-row\n')
        with patch.dict(module.os.environ, {'PROBE_MYSQL_PASSWORD': 'fixture-only-password'}), \
                patch.object(module.subprocess, 'run', return_value=completed) as run:
            self.assertEqual([['utc-row']], instance.sql('SELECT CURRENT_TIMESTAMP;'))
        call = run.call_args
        self.assertEqual("SET SESSION time_zone='+00:00';\nSELECT CURRENT_TIMESTAMP;",
                         call.kwargs['input'])
        self.assertEqual('fixture-only-password', call.kwargs['env']['MYSQL_PWD'])
        self.assertNotIn('fixture-only-password', ' '.join(call.args[0]))

    def probe(self, responses):
        instance = object.__new__(module.Probe)
        instance.auth = 'http://127.0.0.1:29201'
        instance.username = 'fixture-user'
        instance.password = 'fixture-password'
        instance.context = 'fixture-context'
        instance.nodes = []
        instance.token = None
        calls = []
        def business(base, path, body=None, **kwargs):
            calls.append((path, body, kwargs))
            value = responses.pop(0)
            if isinstance(value, Exception):
                raise value
            return value
        instance.business = business
        return instance, calls

    def test_direct_login_uses_current_body_and_token_field(self):
        instance, calls = self.probe([{'token': 'fixture-access', 'refreshToken': 'fixture-refresh'}])
        instance.login()
        self.assertEqual('fixture-access', instance.token)
        self.assertIsNone(instance.password)
        self.assertEqual(['/auth/login'], [call[0] for call in calls])
        self.assertEqual({'username': 'fixture-user', 'password': 'fixture-password',
                          'contextId': 'fixture-context'}, calls[0][1])
        self.assertFalse(calls[0][2]['auth'])

    def test_pending_login_activates_once_and_uses_token_field(self):
        instance, calls = self.probe([{'token': '', 'loginTicket': 'fixture-ticket'}, {'token': 'fixture-access'}])
        instance.login()
        self.assertEqual('fixture-access', instance.token)
        self.assertEqual(['/auth/login', '/auth/context/activate'], [call[0] for call in calls])
        self.assertEqual({'loginTicket': 'fixture-ticket', 'contextId': 'fixture-context'}, calls[1][1])
        self.assertFalse(calls[1][2]['auth'])

    def test_login_failure_clears_password_and_legacy_field_is_rejected(self):
        for response in (module.ProbeFailure('business_request_rejected'), {'accessToken': 'legacy-field'}):
            with self.subTest(response_type=type(response).__name__):
                instance, _ = self.probe([response])
                with self.assertRaises(module.ProbeFailure):
                    instance.login()
                self.assertIsNone(instance.password)
                self.assertIsNone(instance.token)

    def test_fence_metrics_select_only_superseded_counter_and_inflight(self):
        payload = '''# TYPE robot_cache_outbox_results_total counter
robot_cache_outbox_results_total{application="fixture",outcome="confirmed"} 12.0
robot_cache_outbox_results_total{application="fixture",outcome="superseded"} 4.0
robot_cache_outbox_inflight{application="fixture"} 1.0
unrelated_secret_label{value="never-returned"} 5
'''
        self.assertEqual({'superseded': 4.0, 'inflight': 1.0}, module.parse_fence_metrics(payload))

    def test_missing_or_invalid_metrics_fail_without_payload(self):
        for payload in ('robot_cache_outbox_inflight 1\n',
                        'robot_cache_outbox_results_total{outcome="superseded"} NaN\nrobot_cache_outbox_inflight 0\n'):
            with self.assertRaises(module.ProbeFailure) as caught:
                module.parse_fence_metrics(payload)
            self.assertRegex(str(caught.exception), '^fence_metric')

    def test_destination_guard_rejects_production_and_remote_ports(self):
        for address in ('http://127.0.0.1:9202', 'http://example.test:29202',
                        'http://fixture:password@127.0.0.1:29202'):
            with self.assertRaises(module.ProbeFailure):
                module.loopback_url(address)


if __name__ == '__main__':
    unittest.main()
