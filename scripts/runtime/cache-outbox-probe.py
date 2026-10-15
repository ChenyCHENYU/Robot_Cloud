#!/usr/bin/env python3
"""Repeatable isolated MySQL/Redis/two-System cache Outbox fault probe.

Requires V10-built System nodes through redis-fault-proxy.py and Auth directly
connected to Redis. All destinations must be loopback high ports. Only the
explicit robot_iteration_* schema may be mutated, with --confirm-isolated-fixture.
SQL transactions create epoch/outbox fixtures; these are NOT MVC business-write
fault tests. Real business transaction rollback is proved separately by Spring/H2
regressions. No tokens/passwords, raw responses or SQL errors are persisted.

Environment: PROBE_USERNAME, PROBE_PASSWORD, PROBE_CONTEXT_ID; MYSQL_BINARY
(optional mysql), PROBE_MYSQL_USER (optional root), PROBE_MYSQL_PASSWORD (optional
empty). Optional PROBE_ACTUATOR_USERNAME/PROBE_ACTUATOR_PASSWORD supply Basic
credentials for Prometheus only. Coordinator owns startup and cleanup.
For deterministic late ACK completion, set System Redis timeout >=20s; otherwise
hold_ack can exercise a timeout followed by a fenced stale failure instead.
"""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import json
import math
import os
from pathlib import Path
import re
import socket
import subprocess
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen
import uuid


class ProbeFailure(Exception):
    pass


def loopback_url(value):
    parsed = urlparse(value)
    if parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost', '::1') \
            or parsed.username or parsed.password or not parsed.port or parsed.port < 10000:
        raise ProbeFailure('loopback_high_port_required')
    return value.rstrip('/')


def quote(value):
    return "'" + str(value).replace("'", "''") + "'"


def parse_fence_metrics(payload):
    """Read fixed numeric meters without retaining arbitrary labels or payloads."""
    readings = {'superseded': [], 'inflight': []}
    sample = re.compile(r'^(robot_cache_outbox_results_total|robot_cache_outbox_inflight)'
                        r'(\{[^\n]*\})?\s+([^\s]+)(?:\s+[^\s]+)?$')
    for line in payload.splitlines():
        matched = sample.fullmatch(line)
        if not matched:
            continue
        name, labels, value = matched.groups()
        if name.endswith('_results_total'):
            if not re.search(r'(?:\{|,)outcome="superseded"(?:,|\})', labels or ''):
                continue
            target = 'superseded'
        else:
            target = 'inflight'
        try:
            number = float(value)
        except ValueError:
            raise ProbeFailure('fence_metric_invalid_number') from None
        if not math.isfinite(number) or number < 0:
            raise ProbeFailure('fence_metric_invalid_number')
        readings[target].append(number)
    if not all(readings.values()):
        raise ProbeFailure('fence_metrics_missing')
    return {name: sum(values) for name, values in readings.items()}


class Probe:
    def __init__(self, args):
        self.args = args
        self.auth = loopback_url(args.auth)
        self.nodes = [loopback_url(value) for value in args.system]
        self.control = loopback_url(args.control)
        if not args.confirm_isolated_fixture or not re.fullmatch('robot_iteration_[a-z0-9_]+', args.database) \
                or args.mysql_port < 10000 or args.redis_port < 10000:
            raise ProbeFailure('isolated_fixture_confirmation_required')
        if not re.fullmatch(r'robot:[a-z0-9-]+:system:perm-version', args.permission_key):
            raise ProbeFailure('permission_key_contract_required')
        self.username = os.environ.pop('PROBE_USERNAME', '')
        self.password = os.environ.pop('PROBE_PASSWORD', '')
        self.context = os.environ.pop('PROBE_CONTEXT_ID', '')
        if not all((self.username, self.password, self.context)):
            raise ProbeFailure('credential_environment_required')
        actuator_user = os.environ.pop('PROBE_ACTUATOR_USERNAME', '')
        actuator_password = os.environ.pop('PROBE_ACTUATOR_PASSWORD', '')
        if bool(actuator_user) != bool(actuator_password):
            raise ProbeFailure('actuator_credential_pair_required')
        self.actuator_auth = 'Basic ' + base64.b64encode(
            (actuator_user + ':' + actuator_password).encode()).decode() if actuator_user else None
        self.token = None
        self.checks = []
        self.fixtures = []
        self.fence_evidence = None
        self.started = time.monotonic()

    def http(self, base, path, body=None, auth=True, timeout=4):
        headers = {'Content-Type': 'application/json'}
        if auth and self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        request = Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
        try:
            with urlopen(request, timeout=timeout) as response:
                return json.load(response)
        except HTTPError as error:
            try:
                return json.load(error)
            except (ValueError, OSError):
                raise ProbeFailure('http_status_failure') from None
        except (URLError, TimeoutError, OSError, ValueError):
            raise ProbeFailure('http_transport_failure') from None

    def business(self, base, path, body=None, timeout=4, auth=True):
        result = self.http(base, path, body, timeout=timeout, auth=auth)
        if result.get('code') != 0:
            raise ProbeFailure('business_request_rejected')
        return result.get('data')

    def sql(self, statement):
        env = os.environ.copy()
        env['MYSQL_PWD'] = env.pop('PROBE_MYSQL_PASSWORD', '')
        command = [os.environ.get('MYSQL_BINARY', 'mysql'), '--no-defaults', '--protocol=TCP', '--host=127.0.0.1',
                   '--port=' + str(self.args.mysql_port), '--user=' + os.environ.get('PROBE_MYSQL_USER', 'root'),
                   '--connect-timeout=2', '--batch', '--skip-column-names', '--raw', self.args.database]
        try:
            result = subprocess.run(command, input="SET SESSION time_zone='+00:00';\n" + statement,
                                    text=True, capture_output=True,
                                    env=env, timeout=8, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise ProbeFailure('mysql_transport_failure') from None
        if result.returncode:
            raise ProbeFailure('mysql_statement_failure')
        return [line.split('\t') for line in result.stdout.splitlines() if line]

    def scalar(self, statement):
        rows = self.sql(statement)
        if len(rows) != 1 or len(rows[0]) != 1:
            raise ProbeFailure('mysql_scalar_contract_failure')
        return rows[0][0]

    def epoch(self):
        return int(self.scalar("SELECT version FROM sys_cache_epoch WHERE scope='permissions';"))

    def redis_get(self):
        command = ['GET', self.args.permission_key]
        packet = ('*2\r\n' + ''.join('$' + str(len(part.encode())) + '\r\n' + part + '\r\n'
                                    for part in command)).encode()
        try:
            with socket.create_connection(('127.0.0.1', self.args.redis_port), timeout=2) as client:
                client.sendall(packet)
                stream = client.makefile('rb')
                header = stream.readline()
                if header == b'$-1\r\n':
                    return None
                if not header.startswith(b'$'):
                    raise ProbeFailure('direct_redis_contract_failure')
                size = int(header[1:-2])
                value = stream.read(size)
                if stream.read(2) != b'\r\n' or not value.isdigit():
                    raise ProbeFailure('redis_version_contract_failure')
                return int(value)
        except (OSError, ValueError):
            raise ProbeFailure('direct_redis_transport_failure') from None

    def mode(self, value):
        return self.http(self.control, '/mode', {'mode': value, 'count': 1}, auth=False)

    def state(self):
        return self.http(self.control, '/state', auth=False)

    def wait(self, predicate, label, timeout=45):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.25)
        raise ProbeFailure(label + '_timeout')

    def check(self, label, passed):
        self.checks.append({'check': label, 'passed': bool(passed)})
        if not passed:
            raise ProbeFailure(label)

    def create_event(self, increment=True, version=None):
        event_id = str(uuid.uuid4())
        update = "UPDATE sys_cache_epoch SET version=version+1 WHERE scope='permissions';" if increment else ''
        selected_version = "(SELECT version FROM sys_cache_epoch WHERE scope='permissions')" if version is None else str(int(version))
        self.sql('BEGIN;' + update + "INSERT INTO sys_cache_outbox "
                 "(id,event_kind,cache_key,cache_version,created_at,next_attempt_at,attempts) VALUES ("
                 + quote(event_id) + ",'permissions'," + quote(self.args.permission_key) + ',' + selected_version
                 + ',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0);COMMIT;')
        self.fixtures.append(event_id)
        return event_id, int(self.scalar('SELECT cache_version FROM sys_cache_outbox WHERE id=' + quote(event_id) + ';'))

    def event(self, event_id):
        row = self.sql('SELECT attempts,delivery_revision,completed_at IS NOT NULL,COALESCE(last_error,\'\'),'
                       'COALESCE(last_failure_id,\'\') FROM sys_cache_outbox WHERE id=' + quote(event_id) + ';')[0]
        return dict(attempts=int(row[0]), revision=int(row[1]), completed=row[2] == '1',
                    category=row[3], failure_id=row[4])

    def login(self):
        try:
            result = self.business(self.auth, '/auth/login',
                                   {'username': self.username, 'password': self.password,
                                    'contextId': self.context}, auth=False)
        finally:
            self.password = None
        if not isinstance(result, dict):
            raise ProbeFailure('login_response_contract_failure')
        if not result.get('token') and result.get('loginTicket'):
            result = self.business(self.auth, '/auth/context/activate',
                                   {'loginTicket': result['loginTicket'], 'contextId': self.context}, auth=False)
        if not isinstance(result, dict) or not isinstance(result.get('token'), str) or not result['token']:
            raise ProbeFailure('login_token_contract_failure')
        self.token = result['token']
        for node in self.nodes:
            self.business(node, '/sys/cache-outbox/summary')

    def metrics(self, node):
        headers = {'Authorization': self.actuator_auth} if self.actuator_auth else {}
        request = Request(node + '/actuator/prometheus', headers=headers)
        try:
            with urlopen(request, timeout=2) as response:
                raw = response.read(2 * 1024 * 1024 + 1)
            if len(raw) > 2 * 1024 * 1024:
                raise ProbeFailure('fence_metrics_size_limit')
            return parse_fence_metrics(raw.decode())
        except (HTTPError, URLError, TimeoutError, OSError, UnicodeError):
            raise ProbeFailure('fence_metrics_transport_failure') from None

    def held_worker(self):
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            samples = [self.metrics(node) for node in self.nodes]
            active = [index for index, sample in enumerate(samples) if sample['inflight'] > 0]
            if len(active) == 1:
                return active[0]
            time.sleep(0.1)
        raise ProbeFailure('held_worker_not_identifiable')

    def preview(self, node, event_id):
        return self.business(node, '/sys/cache-outbox/' + event_id + '/replay-preview',
                             {'reason': 'DEPENDENCY_RECOVERED'})

    def replay(self, node, event_id, preview):
        return self.http(node, '/sys/cache-outbox/' + event_id + '/replay',
                         {'reason': preview['reason'], 'planHash': preview['planHash']})

    def replay_once(self, node, event_id):
        for _ in range(5):
            preview = self.preview(node, event_id)
            result = self.replay(node, event_id, preview)
            if result.get('code') == 0:
                return result['data']
            if result.get('code') != 40902:
                raise ProbeFailure('replay_rejected')
        raise ProbeFailure('replay_continuously_drifted')

    def available_node(self):
        for node in self.nodes:
            try:
                self.business(node, '/sys/cache-outbox/summary', timeout=1)
                return node
            except ProbeFailure:
                pass
        raise ProbeFailure('no_peer_available_during_ack_gate')

    def run(self):
        self.mode('online')
        self.check('schema_v10_present', int(self.scalar("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema="
                   + quote(self.args.database) + " AND table_name='sys_cache_outbox' AND column_name='delivery_revision';")) == 1)
        self.login()
        self.mode('offline')
        event_id, version = self.create_event()
        self.wait(lambda: self.event(event_id)['attempts'] > 0, 'offline_failure_recorded', 25)
        failed = self.event(event_id)
        self.check('unreachable_keeps_committed_epoch_and_pending', self.epoch() >= version and not failed['completed'])
        self.check('unreachable_diagnosis_is_classified', failed['category'] in
                   ('REDIS_UNAVAILABLE', 'REDIS_TIMEOUT', 'REDIS_FAILURE') and bool(re.fullmatch(r'[a-f0-9-]{36}', failed['failure_id'])))
        self.mode('online')
        self.wait(lambda: self.event(event_id)['completed'], 'online_recovery')
        self.check('recovery_notifies_committed_version', self.redis_get() >= version)
        before = self.redis_get()
        older_id, _ = self.create_event(increment=False, version=max(1, version - 1))
        self.wait(lambda: self.event(older_id)['completed'], 'late_version_delivery')
        self.check('late_notification_never_reduces_redis_version', self.redis_get() >= before)

        preview = self.preview(self.nodes[0], event_id)
        epoch = self.epoch()
        barrier = threading.Barrier(2)
        def concurrent_replay(node):
            barrier.wait(timeout=5)
            return self.replay(node, event_id, preview).get('code')
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(concurrent_replay, self.nodes))
        self.check('same_plan_two_systems_has_one_winner', sorted(results) == [0, 40902])
        self.check('replay_does_not_change_epoch', self.epoch() == epoch)
        self.check('same_plan_appends_one_audit', int(self.scalar('SELECT COUNT(*) FROM sys_cache_outbox_replay_log WHERE event_id='
                   + quote(event_id) + ';')) == 1)
        self.wait(lambda: self.event(event_id)['completed'], 'replay_delivery')

        dropped = self.state()['dropped_replies']
        self.mode('drop_ack')
        lost_id, lost_version = self.create_event()
        self.wait(lambda: self.state()['dropped_replies'] > dropped, 'applied_ack_dropped', 20)
        self.wait(lambda: self.event(lost_id)['completed'], 'ack_loss_recovery')
        self.check('lost_ack_does_not_lose_committed_epoch', self.epoch() >= lost_version and self.redis_get() >= lost_version)
        self.mode('online')

        held = self.state()['held_replies']
        self.wait(lambda: all(self.metrics(node)['inflight'] == 0 for node in self.nodes),
                  'prior_ack_loss_workers_idle', 40)
        baseline = [self.metrics(node) for node in self.nodes]
        self.check('dispatchers_idle_before_ack_gate', all(value['inflight'] == 0 for value in baseline))
        self.mode('hold_ack')
        held_id, held_version = self.create_event()
        self.wait(lambda: self.state()['held_replies'] > held, 'applied_reply_held', 20)
        holder = self.held_worker()
        peer = self.available_node()
        replayed = self.replay_once(peer, held_id)
        new_id, new_version = self.create_event()
        self.wait(lambda: self.redis_get() >= new_version, 'newer_version_during_old_reply', 15)
        self.http(self.control, '/release', {}, auth=False)
        self.wait(lambda: self.event(held_id)['completed'] and self.event(new_id)['completed'], 'fenced_late_recovery')
        self.wait(lambda: self.metrics(self.nodes[holder])['superseded'] > baseline[holder]['superseded'],
                  'held_worker_conditional_update_zero', 25)
        delta = self.metrics(self.nodes[holder])['superseded'] - baseline[holder]['superseded']
        self.fence_evidence = {'holder_identified_by_inflight': True, 'superseded_counter_delta': delta,
                               'conditional_update_zero_observed': True}
        self.check('held_old_worker_conditional_update_is_zero', delta > 0)
        self.check('old_worker_never_rewinds_replay_revision', self.event(held_id)['revision'] >= replayed['revision'])
        self.check('released_old_ack_never_reduces_version', self.redis_get() >= new_version > held_version)
        self.check('late_reply_replay_audit_committed', int(self.scalar('SELECT COUNT(*) FROM sys_cache_outbox_replay_log WHERE event_id='
                   + quote(held_id) + ';')) == 1)
        for node in self.nodes:
            self.business(node, '/sys/cache-outbox/summary')
        self.check('both_systems_healthy_after_faults', True)

    def report(self, failure=None):
        return {'probe': 'cache-outbox-two-system-faults', 'passed': failure is None,
                'checks_passed': sum(item['passed'] for item in self.checks), 'checks': self.checks,
                'failure_category': failure, 'fixtures_created': len(self.fixtures),
                'elapsed_seconds': round(time.monotonic() - self.started, 2),
                'credentials_persisted': False, 'payload_logging': False,
                'old_worker_fence_evidence': self.fence_evidence,
                'boundary': 'Real dispatcher/Redis/MySQL + guarded replay API. SQL epoch/outbox fixtures are not MVC business-write fault tests. Offline closes/rejects TCP connections; hold/drop gates only applied permission Lua replies. No service restart or HA claim.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--confirm-isolated-fixture', action='store_true')
    parser.add_argument('--database', default='robot_iteration_a1c6')
    parser.add_argument('--mysql-port', type=int, default=33317)
    parser.add_argument('--redis-port', type=int, default=16397)
    parser.add_argument('--auth', default='http://127.0.0.1:29201')
    parser.add_argument('--system', nargs=2, default=['http://127.0.0.1:29202', 'http://127.0.0.1:29203'])
    parser.add_argument('--control', default='http://127.0.0.1:16498')
    parser.add_argument('--permission-key', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    probe = None
    try:
        probe = Probe(args)
        probe.run()
        report = probe.report()
    except Exception as error:
        category = str(error) if isinstance(error, ProbeFailure) else type(error).__name__
        report = probe.report(category) if probe else {'passed': False, 'failure_category': category,
                                                      'credentials_persisted': False}
    finally:
        if probe:
            try:
                probe.mode('online')
            except ProbeFailure:
                pass
            probe.token = None
            probe.password = None
            probe.actuator_auth = None
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False, separators=(',', ':')))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
