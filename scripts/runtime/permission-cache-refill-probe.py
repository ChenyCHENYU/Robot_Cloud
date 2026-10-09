#!/usr/bin/env python3
"""Replay a real serialized old permission-cache snapshot in an isolated fixture.

Requires the coordinator's Gateway/System/Auth, MySQL and Redis on loopback high
ports. Admin credentials come only from PROBE_USERNAME, PROBE_PASSWORD and
PROBE_CONTEXT_ID. MySQL uses MYSQL_BINARY/PROBE_MYSQL_USER/PROBE_MYSQL_PASSWORD.
The probe discovers the real sys:user:list menu-page resource and grants that
exact page to its own ordinary self-scope role/member via HTTP, then warms the list,
keeps Redis DUMP bytes only in memory, revokes the exact role permissions, and
RESTOREs the old cache key. A request must remain HTTP 403 while the new DB-epoch
namespace is populated. This simulates an in-flight serialized cache refill; it
does not prove restoration of a former HA database/Redis snapshot.

Cleanup removes only this probe's member and role using fresh API revisions,
logs out its sessions and deletes exact cache keys for its own newly created ID.
The public member-delete API intentionally retains the platform account and
soft-deleted history; this is reported separately, never hidden as full deletion.
No cache payloads, tokens, passwords, raw HTTP bodies or SQL errors are persisted.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import uuid


spec = importlib.util.spec_from_file_location('cache_outbox_helpers', Path(__file__).with_name('cache-outbox-probe.py'))
helpers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helpers)
ProbeFailure = helpers.ProbeFailure


def list_page_resources(catalogue):
    """Select the list page by actual resource type and code, without seed-ID assumptions."""
    return [item for item in catalogue if item.get('type') == 'menu' and item.get('permCode') == 'sys:user:list']


def permission_key(environment, company_id, user_id, epoch):
    """Generate one exact versioned key; no broad key discovery or dump."""
    if not re.fullmatch(r'[a-z0-9-]+', environment) or not all(
            re.fullmatch(r'[A-Za-z0-9_-]+', value or '') for value in (company_id, user_id)) or epoch < 1:
        raise ProbeFailure('cache_key_identity_contract_failure')
    return f'robot:{environment}:system:permissions:{company_id}:{user_id}:db-v{epoch}'


def read_resp(stream, depth=0):
    """Read bounded Redis replies as bytes so serialized DUMP data stays opaque."""
    if depth > 8:
        raise ProbeFailure('redis_reply_depth_limit')
    line = stream.readline(4096)
    if not line.endswith(b'\r\n'):
        raise ProbeFailure('redis_reply_contract_failure')
    prefix, value = line[:1], line[1:-2]
    if prefix == b'-':
        raise ProbeFailure('redis_command_rejected')
    if prefix == b'+':
        return value
    if prefix == b':':
        return int(value)
    if prefix == b'$':
        length = int(value)
        if length == -1:
            return None
        if not 0 <= length <= 1024 * 1024:
            raise ProbeFailure('redis_reply_size_limit')
        payload = stream.read(length)
        if len(payload) != length or stream.read(2) != b'\r\n':
            raise ProbeFailure('redis_bulk_contract_failure')
        return payload
    raise ProbeFailure('redis_reply_contract_failure')


class RefillProbe:
    def __init__(self, args):
        self.args = args
        self.gateway = helpers.loopback_url(args.gateway)
        if not args.confirm_isolated_fixture or not re.fullmatch(r'robot_iteration_[a-z0-9_]+', args.database) \
                or not re.fullmatch(r'[a-z0-9-]+', args.environment) \
                or not 10000 <= args.mysql_port < 65536 or not 10000 <= args.redis_port < 65536 \
                or not 0 <= args.redis_database <= 15:
            raise ProbeFailure('isolated_fixture_confirmation_required')
        self.admin_username = os.environ.pop('PROBE_USERNAME', '')
        self.admin_password = os.environ.pop('PROBE_PASSWORD', '')
        self.admin_context = os.environ.pop('PROBE_CONTEXT_ID', '')
        if not all((self.admin_username, self.admin_password, self.admin_context)):
            raise ProbeFailure('credential_environment_required')
        suffix = uuid.uuid4().hex[:12]
        self.username = 'refill_' + suffix
        self.role_code = 'REFILL_' + suffix.upper()
        self.password = secrets.token_urlsafe(18)
        self.admin_token = None
        self.member_token = None
        self.role_id = None
        self.user_id = None
        self.company_id = None
        self.old_payload = None
        self.cache_keys = set()
        self.checks = []
        self.http_requests = 0
        self.started = time.monotonic()
        self.old_epoch = None
        self.new_epoch = None
        self.snapshot_size = None
        self.cleanup_state = {'member_removed': False, 'role_removed': False,
                              'sessions_logged_out': False, 'exact_cache_keys_removed': False,
                              'platform_account_retained_by_api': False}

    def request(self, method, path, body=None, token=None):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        request = Request(self.gateway + path, method=method, headers=headers,
                          data=None if body is None else json.dumps(body).encode())
        self.http_requests += 1
        try:
            with urlopen(request, timeout=5) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            try:
                return error.code, json.load(error)
            except (ValueError, OSError):
                raise ProbeFailure('http_error_response_contract_failure') from None
        except (URLError, TimeoutError, OSError, ValueError):
            raise ProbeFailure('http_transport_failure') from None

    def success(self, method, path, body=None, token=None):
        status, result = self.request(method, path, body, token)
        if status != 200 or not isinstance(result, dict) or result.get('code') != 0:
            raise ProbeFailure('business_request_rejected')
        return result.get('data')

    def login(self, username, password, context_id=None):
        body = {'username': username, 'password': password}
        if context_id:
            body['contextId'] = context_id
        result = self.success('POST', '/auth/login', body)
        if not isinstance(result, dict):
            raise ProbeFailure('login_response_contract_failure')
        if not result.get('token') and result.get('loginTicket'):
            contexts = result.get('availableContexts', [])
            selected = context_id or (contexts[0].get('id') if len(contexts) == 1 else None)
            if not selected:
                raise ProbeFailure('fixture_context_not_unique')
            result = self.success('POST', '/auth/context/activate',
                                  {'loginTicket': result['loginTicket'], 'contextId': selected})
        if not isinstance(result, dict) or not isinstance(result.get('token'), str) or not result['token']:
            raise ProbeFailure('login_token_contract_failure')
        return result

    def epoch(self):
        env = os.environ.copy()
        env['MYSQL_PWD'] = env.pop('PROBE_MYSQL_PASSWORD', '')
        command = [os.environ.get('MYSQL_BINARY', 'mysql'), '--no-defaults', '--protocol=TCP', '--host=127.0.0.1',
                   '--port=' + str(self.args.mysql_port), '--user=' + os.environ.get('PROBE_MYSQL_USER', 'root'),
                   '--connect-timeout=2', '--batch', '--skip-column-names', self.args.database]
        try:
            result = subprocess.run(command,
                                    input="SELECT version FROM sys_cache_epoch WHERE scope='permissions';",
                                    text=True, capture_output=True, env=env, timeout=5, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise ProbeFailure('mysql_transport_failure') from None
        if result.returncode or not re.fullmatch(r'[1-9][0-9]*\s*', result.stdout):
            raise ProbeFailure('mysql_epoch_read_failure')
        return int(result.stdout)

    def redis(self, *parts):
        encoded = [part if isinstance(part, bytes) else str(part).encode() for part in parts]
        packet = b'*' + str(len(encoded)).encode() + b'\r\n' + b''.join(
            b'$' + str(len(part)).encode() + b'\r\n' + part + b'\r\n' for part in encoded)
        try:
            with socket.create_connection(('127.0.0.1', self.args.redis_port), timeout=3) as client:
                stream = client.makefile('rb')
                if self.args.redis_database:
                    database = str(self.args.redis_database).encode()
                    client.sendall(b'*2\r\n$6\r\nSELECT\r\n$' + str(len(database)).encode()
                                   + b'\r\n' + database + b'\r\n')
                    if read_resp(stream) != b'OK':
                        raise ProbeFailure('redis_database_selection_failure')
                client.sendall(packet)
                return read_resp(stream)
        except (OSError, ValueError):
            raise ProbeFailure('redis_transport_failure') from None

    def check(self, label, condition):
        self.checks.append({'check': label, 'passed': bool(condition)})
        if not condition:
            raise ProbeFailure(label)

    def run(self):
        try:
            login = self.login(self.admin_username, self.admin_password, self.admin_context)
        finally:
            self.admin_password = None
        self.admin_token = login['token']
        self.company_id = login.get('activeContext', {}).get('companyId')
        catalogue = self.success('GET', '/sys/permissions', token=self.admin_token)
        matches = list_page_resources(catalogue)
        self.check('real_permission_catalogue_has_one_exact_list_page', len(matches) == 1)
        self.role_id = self.success('POST', '/sys/roles',
                                    {'roleName': 'Refill ' + self.username[-12:], 'roleCode': self.role_code,
                                     'dataScope': 'self', 'status': 1, 'permissionIds': [matches[0]['id']]}, self.admin_token)
        self.check('created_role_id_is_safe', isinstance(self.role_id, str)
                   and bool(re.fullmatch(r'[A-Za-z0-9_-]+', self.role_id)))
        self.user_id = self.success('POST', '/sys/users',
                                    {'username': self.username, 'password': self.password, 'nickname': self.username,
                                     'status': 1, 'roleIds': [self.role_id]}, self.admin_token)
        self.check('created_member_id_is_safe', isinstance(self.user_id, str)
                   and bool(re.fullmatch(r'[A-Za-z0-9_-]+', self.user_id)))
        try:
            member = self.login(self.username, self.password)
        finally:
            self.password = None
        self.member_token = member['token']
        self.check('member_login_has_exact_created_identity', member.get('user', {}).get('id') == self.user_id
                   and member.get('activeContext', {}).get('companyId') == self.company_id)
        self.old_epoch = self.epoch()
        listed = self.success('GET', '/sys/users?page=1&pageSize=10', token=self.member_token)
        self.check('ordinary_member_can_use_exact_list_before_revocation', True)
        self.check('self_scope_lists_only_the_created_member', listed.get('total') == 1
                   and [item.get('id') for item in listed.get('list', [])] == [self.user_id])
        self.check('warm_request_epoch_is_stable', self.epoch() == self.old_epoch)
        old_key = permission_key(self.args.environment, self.company_id, self.user_id, self.old_epoch)
        self.cache_keys.add(old_key)
        self.old_payload = self.redis('DUMP', old_key)
        self.check('real_serialized_old_permission_cache_exists', isinstance(self.old_payload, bytes) and bool(self.old_payload))
        self.snapshot_size = len(self.old_payload)
        old_ttl = self.redis('PTTL', old_key)
        self.check('old_cache_has_bounded_ttl', isinstance(old_ttl, int) and old_ttl > 0)
        role = self.success('GET', '/sys/roles/' + self.role_id, token=self.admin_token)
        self.success('PUT', '/sys/roles/' + self.role_id + '/permissions',
                     {'permissionIds': [], 'revision': role['revision']}, self.admin_token)
        self.new_epoch = self.epoch()
        self.check('real_role_revoke_commits_larger_db_epoch', self.new_epoch > self.old_epoch)
        role = self.success('GET', '/sys/roles/' + self.role_id, token=self.admin_token)
        self.check('role_authorization_is_exactly_empty', role.get('permissionIds') == []
                   and role.get('buttonIds') == [] and role.get('menuIds') == [])
        self.check('opaque_snapshot_restore_acknowledged', self.redis('RESTORE', old_key,
                   min(old_ttl, 120000), self.old_payload, 'REPLACE') == b'OK')
        self.check('old_epoch_key_really_exists_after_refill', self.redis('EXISTS', old_key) == 1
                   and self.redis('DUMP', old_key) == self.old_payload)
        new_key = permission_key(self.args.environment, self.company_id, self.user_id, self.new_epoch)
        self.cache_keys.add(new_key)
        status, denied = self.request('GET', '/sys/users?page=1&pageSize=10', token=self.member_token)
        self.check('first_request_denies_restored_old_grant', status == 403 and denied.get('code') == 403)
        self.check('new_epoch_namespace_is_materialized', self.redis('EXISTS', new_key) == 1)
        status, denied = self.request('GET', '/sys/users?page=1&pageSize=10', token=self.member_token)
        self.check('new_namespace_cache_hit_still_denies_grant', status == 403 and denied.get('code') == 403)
        self.check('old_refill_does_not_regress_database_epoch', self.epoch() == self.new_epoch)
        self.check('old_snapshot_and_new_namespace_coexist', self.redis('EXISTS', old_key) == 1
                   and self.redis('EXISTS', new_key) == 1)

    def cleanup(self):
        """Use fresh revisions and only IDs returned by this probe's create calls."""
        failures = []
        if self.user_id and self.admin_token:
            try:
                member = self.success('GET', '/sys/users/' + self.user_id, token=self.admin_token)
                if member.get('username') != self.username:
                    raise ProbeFailure('cleanup_member_identity_changed')
                self.success('DELETE', '/sys/users/' + self.user_id + '?expectedRevision='
                             + str(member['membershipRevision']), token=self.admin_token)
                self.cleanup_state['member_removed'] = True
                self.cleanup_state['platform_account_retained_by_api'] = True
            except Exception:
                failures.append('member_cleanup_failed')
        if self.role_id and self.admin_token:
            try:
                role = self.success('GET', '/sys/roles/' + self.role_id, token=self.admin_token)
                if role.get('roleCode') != self.role_code:
                    raise ProbeFailure('cleanup_role_identity_changed')
                self.success('DELETE', '/sys/roles/' + self.role_id + '?expectedRevision='
                             + str(role['revision']), token=self.admin_token)
                self.cleanup_state['role_removed'] = True
            except Exception:
                failures.append('role_cleanup_failed')
        logged_out = True
        for token in (self.member_token, self.admin_token):
            if token:
                try:
                    self.success('POST', '/auth/logout', token=token)
                except Exception:
                    logged_out = False
                    failures.append('session_cleanup_failed')
        self.cleanup_state['sessions_logged_out'] = logged_out
        try:
            for key in self.cache_keys:
                self.redis('DEL', key)
                if self.redis('EXISTS', key) != 0:
                    raise ProbeFailure('cleanup_cache_still_exists')
            self.cleanup_state['exact_cache_keys_removed'] = True
        except Exception:
            failures.append('cache_cleanup_failed')
        self.old_payload = None
        self.admin_token = None
        self.member_token = None
        self.password = None
        self.admin_password = None
        return failures

    def report(self, failure, cleanup_failures):
        return {'probe': 'permission-old-serialized-cache-refill',
                'passed': failure is None and not cleanup_failures, 'failure_category': failure,
                'checks_passed': sum(item['passed'] for item in self.checks), 'checks': self.checks,
                'old_db_epoch': self.old_epoch, 'new_db_epoch': self.new_epoch,
                'serialized_snapshot_bytes': self.snapshot_size, 'http_requests': self.http_requests,
                'cleanup': self.cleanup_state, 'cleanup_failures': cleanup_failures,
                'elapsed_seconds': round(time.monotonic() - self.started, 2),
                'credentials_persisted': False, 'cache_payload_persisted': False,
                'boundary': 'Real Gateway MVC role/user/revoke commands and actual Redis serialization; opaque DUMP/RESTORE simulates old in-flight cache refill, not HA snapshot restoration. Epoch SQL is read-only. Member-delete API retains the platform account and soft-deleted audit history.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--confirm-isolated-fixture', action='store_true')
    parser.add_argument('--gateway', default='http://127.0.0.1:28080')
    parser.add_argument('--database', default='robot_iteration_a1c6')
    parser.add_argument('--mysql-port', type=int, default=33317)
    parser.add_argument('--redis-port', type=int, default=16397)
    parser.add_argument('--redis-database', type=int, default=0)
    parser.add_argument('--environment', default='iteration-a1c6')
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    instance = None
    failure = None
    cleanup_failures = []
    try:
        instance = RefillProbe(args)
        instance.run()
    except Exception as error:
        failure = str(error) if isinstance(error, ProbeFailure) else type(error).__name__
    finally:
        if instance:
            cleanup_failures = instance.cleanup()
    report = instance.report(failure, cleanup_failures) if instance else {
        'passed': False, 'failure_category': failure, 'credentials_persisted': False, 'cache_payload_persisted': False}
    destination = Path(args.output)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False, separators=(',', ':')))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
