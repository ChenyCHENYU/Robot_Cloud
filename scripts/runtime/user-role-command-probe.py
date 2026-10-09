#!/usr/bin/env python3
"""Exercise user/role client-revision commands through a running local Gateway.

Requires PROBE_USERNAME, PROBE_PASSWORD and PROBE_CONTEXT_ID in the environment,
plus --confirm-isolated-fixture. Credentials, tokens and response bodies remain
in memory. Only a random role/member created by this invocation may be changed
or removed; cleanup verifies their username/role code again before a write.
Company removal intentionally retains the platform account. The coordinator
owns the temporary schema, application startup and eventual database cleanup.

Example: python3 scripts/runtime/user-role-command-probe.py
    --confirm-isolated-fixture --output /tmp/user-role-command-evidence.json
"""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener


class ProbeFailure(Exception):
    """Fixed diagnostic categories only; never include a response or credential."""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def local_gateway(value):
    parsed = urlparse(value)
    if parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost', '::1') \
            or parsed.username or parsed.password or not parsed.port or parsed.port < 10000 \
            or parsed.path not in ('', '/') or parsed.query or parsed.fragment:
        raise ProbeFailure('loopback_high_port_gateway_required')
    return value.rstrip('/')


class Probe:
    def __init__(self, args):
        if not args.confirm_isolated_fixture:
            raise ProbeFailure('isolated_fixture_confirmation_required')
        self.gateway = local_gateway(args.gateway)
        self.username = os.environ.pop('PROBE_USERNAME', '')
        self.password = os.environ.pop('PROBE_PASSWORD', '')
        self.context_id = os.environ.pop('PROBE_CONTEXT_ID', '')
        if not all((self.username, self.password, self.context_id)):
            raise ProbeFailure('credential_environment_required')
        suffix = secrets.token_hex(6)
        self.fixture_username = 'probe_' + suffix
        self.fixture_role_code = 'PROBE_' + suffix.upper()
        self.fixture_role_name = '命令探针_' + suffix
        self.fixture_password = secrets.token_urlsafe(15)
        self.reset_password = secrets.token_urlsafe(15)
        self.user_id = None
        self.role_id = None
        self.member_removed = False
        self.role_removed = False
        self.session_closed = False
        self.token = None
        self.checks = []
        self.requests = []
        self.opener = build_opener(NoRedirect())
        self.started = time.monotonic()

    def http(self, label, method, path, body=None, auth=True):
        headers = {'Content-Type': 'application/json'}
        if auth and self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        data = None if body is None else json.dumps(body).encode()
        request = Request(self.gateway + path, data=data, method=method, headers=headers)
        try:
            try:
                response = self.opener.open(request, timeout=8)
            except HTTPError as error:
                response = error
            with response:
                status = response.code
                payload = response.read(1024 * 1024 + 1)
        except (URLError, TimeoutError, OSError):
            self.requests.append({'operation': label, 'http_status': None, 'business_code': None})
            raise ProbeFailure('http_transport_failure') from None
        if len(payload) > 1024 * 1024:
            raise ProbeFailure('http_response_size_limit')
        try:
            result = json.loads(payload)
        except (ValueError, UnicodeError):
            self.requests.append({'operation': label, 'http_status': status, 'business_code': None})
            raise ProbeFailure('http_json_contract_failure') from None
        code = result.get('code') if isinstance(result, dict) else None
        self.requests.append({'operation': label, 'http_status': status, 'business_code': code
                              if isinstance(code, int) and not isinstance(code, bool) else None})
        if not isinstance(code, int) or isinstance(code, bool):
            raise ProbeFailure('business_code_contract_failure')
        return status, result

    def business(self, label, method, path, body=None, auth=True):
        status, result = self.http(label, method, path, body, auth=auth)
        if status != 200 or result['code'] != 0:
            raise ProbeFailure(label + '_rejected')
        return result.get('data')

    def rejected(self, label, method, path, code, body=None):
        status, result = self.http(label, method, path, body)
        return status == 200 and result['code'] == code

    def check(self, label, passed):
        self.checks.append({'check': label, 'passed': bool(passed)})
        if not passed:
            raise ProbeFailure(label)

    def user_detail(self):
        view = self.business('user_detail', 'GET', '/sys/users/' + self.user_id)
        if not isinstance(view, dict) or view.get('id') != self.user_id \
                or view.get('username') != self.fixture_username:
            raise ProbeFailure('fixture_member_identity_mismatch')
        self.validate_revisions(view, ('revision', 'membershipRevision'))
        return view

    def role_detail(self):
        view = self.business('role_detail', 'GET', '/sys/roles/' + self.role_id)
        if not isinstance(view, dict) or view.get('id') != self.role_id \
                or view.get('roleCode') != self.fixture_role_code:
            raise ProbeFailure('fixture_role_identity_mismatch')
        self.validate_revisions(view, ('revision',))
        return view

    @staticmethod
    def validate_revisions(view, fields):
        if any(not isinstance(view.get(field), int) or isinstance(view[field], bool)
               or view[field] < 0 for field in fields):
            raise ProbeFailure('detail_revision_contract_failure')

    @staticmethod
    def member_state(view):
        return tuple(view.get(field) for field in
                     ('revision', 'membershipRevision', 'status', 'statusFlag', 'roleIds'))

    def login(self):
        try:
            data = self.business('login', 'POST', '/auth/login',
                                 {'username': self.username, 'password': self.password,
                                  'contextId': self.context_id}, auth=False)
        finally:
            self.password = None
        if isinstance(data, dict) and not data.get('token') and data.get('loginTicket'):
            data = self.business('activate_context', 'POST', '/auth/context/activate',
                                 {'loginTicket': data['loginTicket'], 'contextId': self.context_id}, auth=False)
        if not isinstance(data, dict) or not isinstance(data.get('token'), str) or not data['token']:
            raise ProbeFailure('login_token_contract_failure')
        self.token = data['token']
        self.check('authenticated_through_gateway', True)

    def create_fixtures(self):
        role_id = self.business('create_fixture_role', 'POST', '/sys/roles',
                                {'roleName': self.fixture_role_name, 'roleCode': self.fixture_role_code,
                                 'dataScope': 'self', 'status': 1, 'permissionIds': []})
        if not isinstance(role_id, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', role_id):
            raise ProbeFailure('created_role_id_contract_failure')
        self.role_id = role_id
        role = self.role_detail()
        user_id = self.business('create_fixture_member', 'POST', '/sys/users',
                                {'username': self.fixture_username, 'password': self.fixture_password,
                                 'nickname': self.fixture_username, 'status': 1, 'roleIds': [self.role_id]})
        self.fixture_password = None
        if not isinstance(user_id, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', user_id):
            raise ProbeFailure('created_member_id_contract_failure')
        self.user_id = user_id
        user = self.user_detail()
        users = self.business('role_bound_users', 'GET', '/sys/roles/' + self.role_id + '/users')
        self.check('fixture_details_and_precise_binding', user['roleIds'] == [self.role_id]
                   and user['status'] == user['statusFlag'] == 1
                   and role.get('menuIds') == role.get('buttonIds') == role.get('permissionIds') == []
                   and isinstance(users, list) and len(users) == 1 and users[0].get('id') == self.user_id)
        return user, role

    def role_commands(self, role):
        path = '/sys/roles/' + self.role_id
        old_revision = role['revision']
        self.business('role_update', 'PUT', path,
                      {'roleName': self.fixture_role_name, 'roleCode': self.fixture_role_code,
                       'remark': 'isolated command probe', 'revision': old_revision})
        current = self.role_detail()
        self.check('role_update_revision_and_read_aliases', current['revision'] == old_revision + 1
                   and current.get('name') == current.get('roleName')
                   and current.get('code') == current.get('roleCode')
                   and current.get('description') == current.get('remark'))
        blocked = self.rejected('role_delete_bound', 'DELETE',
                                path + '?expectedRevision=' + str(current['revision']), 40903)
        self.check('bound_role_delete_rejected_without_version_change', blocked
                   and self.role_detail()['revision'] == current['revision'])
        negatives = [self.rejected('role_delete_missing_version', 'DELETE', path, 400),
                     self.rejected('role_delete_negative_version', 'DELETE', path + '?expectedRevision=-1', 400),
                     self.rejected('role_delete_stale_version', 'DELETE',
                                   path + '?expectedRevision=' + str(old_revision), 40902)]
        self.check('role_delete_missing_negative_stale_are_rejected', all(negatives)
                   and self.role_detail()['revision'] == current['revision'])

    def status_commands(self, initial):
        path = '/sys/users/' + self.user_id + '/status'
        negatives = [self.rejected('status_missing_version', 'PUT', path, 400, {'status': 0}),
                     self.rejected('status_negative_version', 'PUT', path, 400,
                                   {'status': 0, 'expectedRevision': -1}),
                     self.rejected('status_invalid_value', 'PUT', path, 400,
                                   {'status': 2, 'expectedRevision': initial['membershipRevision']})]
        self.check('status_invalid_commands_leave_member_unchanged', all(negatives)
                   and self.member_state(self.user_detail()) == self.member_state(initial))
        self.business('status_disable_current', 'PUT', path,
                      {'status': 0, 'expectedRevision': initial['membershipRevision']})
        disabled = self.user_detail()
        self.check('status_disable_consumes_member_version_only', disabled['membershipRevision']
                   == initial['membershipRevision'] + 1 and disabled['revision'] == initial['revision']
                   and disabled['status'] == disabled['statusFlag'] == 0)
        stale = self.rejected('status_enable_stale', 'PUT', path, 40902,
                              {'status': 1, 'expectedRevision': initial['membershipRevision']})
        self.check('status_stale_command_leaves_member_unchanged', stale
                   and self.member_state(self.user_detail()) == self.member_state(disabled))
        self.business('status_enable_current', 'PUT', path,
                      {'status': 1, 'expectedRevision': disabled['membershipRevision']})
        enabled = self.user_detail()
        self.check('status_enable_returns_incremented_member_version', enabled['membershipRevision']
                   == disabled['membershipRevision'] + 1 and enabled['revision'] == initial['revision']
                   and enabled['status'] == enabled['statusFlag'] == 1)
        return enabled

    def password_commands(self, initial):
        path = '/sys/users/' + self.user_id + '/reset-password'
        current_body = {'password': self.reset_password, 'expectedRevision': initial['revision'],
                        'expectedMembershipRevision': initial['membershipRevision']}
        negatives = [self.rejected('reset_missing_account_version', 'POST', path, 400,
                                   {'password': self.reset_password,
                                    'expectedMembershipRevision': initial['membershipRevision']}),
                     self.rejected('reset_missing_member_version', 'POST', path, 400,
                                   {'password': self.reset_password, 'expectedRevision': initial['revision']}),
                     self.rejected('reset_negative_account_version', 'POST', path, 400,
                                   dict(current_body, expectedRevision=-1)),
                     self.rejected('reset_negative_member_version', 'POST', path, 400,
                                   dict(current_body, expectedMembershipRevision=-1))]
        self.check('reset_missing_negative_versions_leave_member_unchanged', all(negatives)
                   and self.member_state(self.user_detail()) == self.member_state(initial))
        stale = [self.rejected('reset_stale_account_version', 'POST', path, 40902,
                              dict(current_body, expectedRevision=initial['revision'] + 1)),
                 self.rejected('reset_stale_member_version', 'POST', path, 40902,
                               dict(current_body, expectedMembershipRevision=initial['membershipRevision'] - 1))]
        self.check('reset_checks_both_versions_before_writing', all(stale)
                   and self.member_state(self.user_detail()) == self.member_state(initial))
        self.business('reset_current_dual_versions', 'POST', path, current_body)
        changed = self.user_detail()
        self.check('reset_returns_both_versions_incremented', changed['revision'] == initial['revision'] + 1
                   and changed['membershipRevision'] == initial['membershipRevision'] + 1
                   and changed['status'] == changed['statusFlag'] == 1 and changed['roleIds'] == initial['roleIds'])
        retry = self.rejected('reset_old_versions_retry', 'POST', path, 40902, current_body)
        self.check('reset_retry_rejected_without_another_write', retry
                   and self.member_state(self.user_detail()) == self.member_state(changed))
        self.reset_password = None
        return changed

    def remove_member(self, current):
        path = '/sys/users/' + self.user_id
        negatives = [self.rejected('remove_missing_version', 'DELETE', path, 400),
                     self.rejected('remove_negative_version', 'DELETE', path + '?expectedRevision=-1', 400)]
        self.check('remove_missing_negative_leave_member_unchanged', all(negatives)
                   and self.member_state(self.user_detail()) == self.member_state(current))
        stale = self.rejected('remove_stale_version', 'DELETE',
                              path + '?expectedRevision=' + str(current['membershipRevision'] - 1), 40902)
        self.check('remove_stale_leaves_member_unchanged', stale
                   and self.member_state(self.user_detail()) == self.member_state(current))
        self.business('remove_current_member_version', 'DELETE',
                      path + '?expectedRevision=' + str(current['membershipRevision']))
        self.member_removed = True
        self.check('remove_current_member_version_accepted', True)
        hidden = self.rejected('removed_member_detail', 'GET', path, 403)
        users = self.business('role_users_after_member_removal', 'GET', '/sys/roles/' + self.role_id + '/users')
        page = self.business('members_after_removal', 'GET', '/sys/users?' + urlencode(
                             {'keyword': self.fixture_username, 'page': 1, 'pageSize': 10}))
        self.check('removed_member_is_hidden_and_role_binding_removed', hidden and users == []
                   and isinstance(page, dict) and page.get('list') == [] and page.get('total') == 0)

    def remove_role(self):
        current = self.role_detail()
        path = '/sys/roles/' + self.role_id
        self.business('role_delete_current_version', 'DELETE',
                      path + '?expectedRevision=' + str(current['revision']))
        self.role_removed = True
        self.check('role_delete_current_version_accepted', True)
        self.check('deleted_role_is_hidden', self.rejected('deleted_role_detail', 'GET', path, 40301))

    def run(self):
        self.login()
        initial, role = self.create_fixtures()
        self.role_commands(role)
        enabled = self.status_commands(initial)
        reset = self.password_commands(enabled)
        self.remove_member(reset)
        self.remove_role()

    def cleanup(self):
        """Best effort cleanup of this invocation's validated fixture identities."""
        if not self.token:
            return
        if self.user_id and not self.member_removed:
            try:
                current = self.user_detail()
                self.business('cleanup_fixture_member', 'DELETE', '/sys/users/' + self.user_id
                              + '?expectedRevision=' + str(current['membershipRevision']))
                self.member_removed = True
            except ProbeFailure:
                pass
        if self.role_id and not self.role_removed:
            try:
                current = self.role_detail()
                self.business('cleanup_fixture_role', 'DELETE', '/sys/roles/' + self.role_id
                              + '?expectedRevision=' + str(current['revision']))
                self.role_removed = True
            except ProbeFailure:
                pass
        try:
            self.business('logout_probe_session', 'POST', '/auth/logout')
            self.session_closed = True
        except ProbeFailure:
            pass
        self.token = None
        self.password = None
        self.fixture_password = None
        self.reset_password = None

    def report(self, failure):
        return {'probe': 'user-role-client-revision-commands', 'passed': failure is None,
                'failure_category': failure, 'checks_passed': sum(item['passed'] for item in self.checks),
                'checks_total': len(self.checks), 'checks': self.checks, 'requests': self.requests,
                'http_requests': len(self.requests), 'elapsed_seconds': round(time.monotonic() - self.started, 2),
                'fixture_member_created': bool(self.user_id), 'fixture_role_created': bool(self.role_id),
                'fixture_member_removed': self.member_removed, 'fixture_role_removed': self.role_removed,
                'probe_session_closed': self.session_closed, 'credentials_persisted': False,
                'response_bodies_persisted': False,
                'boundary': 'Real Gateway/Auth/System HTTP commands. Negative cases verify returned versions/state; '
                            'SQL audit/epoch/outbox rollback is proved by separate Spring/H2/MySQL tests. '
                            'Removing a company member retains its platform account by design; the coordinator '
                            'owns disposal of the dedicated temporary schema. No seed account is changed.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gateway', default='http://127.0.0.1:28080')
    parser.add_argument('--confirm-isolated-fixture', action='store_true')
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    probe = None
    failure = None
    try:
        probe = Probe(args)
        probe.run()
    except Exception as error:
        failure = str(error) if isinstance(error, ProbeFailure) else type(error).__name__
    finally:
        if probe:
            probe.cleanup()
    report = probe.report(failure) if probe else {'probe': 'user-role-client-revision-commands',
                                                 'passed': False, 'failure_category': failure,
                                                 'credentials_persisted': False,
                                                 'response_bodies_persisted': False}
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False, separators=(',', ':')))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
