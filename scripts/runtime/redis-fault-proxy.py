#!/usr/bin/env python3
"""Isolated loopback Redis fault proxy; preserves RESP ordering and never logs payloads.

Start separately via the coordinator. Auth uses Redis directly; both System nodes
use this proxy. POST /mode accepts online, offline, hold_ack, drop_ack; /release
releases an applied permission-notification reply. GET /state returns aggregates.
Only successful integer replies to single-key EVAL/EVALSHA ending ':perm-version'
are eligible. Error replies, including initial NOSCRIPT, are always forwarded.
No services, databases or other processes are stopped by this tool.
"""
import argparse
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import signal
import socket
import socketserver
import threading

MAX_BUFFER = 16 * 1024 * 1024


def parse_frame(buffer, offset=0, depth=0):
    """Return end/value/type for a complete RESP2/RESP3 frame, else None."""
    if depth > 64 or len(buffer) > MAX_BUFFER:
        raise ValueError('protocol_limit')
    if offset >= len(buffer):
        return None
    prefix = chr(buffer[offset])
    line_end = buffer.find(b'\r\n', offset + 1)
    if line_end < 0:
        return None
    value = bytes(buffer[offset + 1:line_end])
    end = line_end + 2
    if prefix in '+-:,_#(':
        return end, value, prefix
    if prefix in '$!=':
        size = int(value)
        if size == -1:
            return end, None, prefix
        if size < 0 or size > MAX_BUFFER:
            raise ValueError('invalid_bulk')
        if len(buffer) < end + size + 2:
            return None
        if buffer[end + size:end + size + 2] != b'\r\n':
            raise ValueError('invalid_bulk_terminator')
        return end + size + 2, bytes(buffer[end:end + size]), prefix
    if prefix in '*~>%|':
        size = int(value)
        if size == -1:
            return end, None, prefix
        if size < 0 or size > 1_000_000:
            raise ValueError('invalid_container')
        count = size * 2 if prefix in '%|' else size
        values = []
        for _ in range(count):
            parsed = parse_frame(buffer, end, depth + 1)
            if parsed is None:
                return None
            end, item, _ = parsed
            values.append(item)
        return end, values, prefix
    raise ValueError('unsupported_protocol')


class Frames:
    def __init__(self):
        self.buffer = bytearray()

    def feed(self, chunk):
        self.buffer.extend(chunk)
        result = []
        while self.buffer:
            parsed = parse_frame(self.buffer)
            if parsed is None:
                break
            end, value, prefix = parsed
            result.append((bytes(self.buffer[:end]), value, prefix))
            del self.buffer[:end]
        return result


def is_permission_notification(value):
    if not isinstance(value, list) or len(value) < 4:
        return False
    command = value[0].upper() if isinstance(value[0], bytes) else b''
    return command in (b'EVAL', b'EVALSHA') and value[2] == b'1' \
        and isinstance(value[3], bytes) and value[3].endswith(b':perm-version')


def is_successful_permission_reply(request_matches, value, prefix):
    """Gate acknowledged Lua execution, never a NOSCRIPT or other error reply."""
    return request_matches and prefix == ':' and value in (b'0', b'1')


class State:
    def __init__(self):
        self.lock = threading.RLock()
        self.mode = 'online'
        self.remaining = 0
        self.release = threading.Event()
        self.release.set()
        self.connections = set()
        self.counters = dict(accepted=0, rejected=0, eligible_replies=0,
                             held_replies=0, dropped_replies=0, protocol_failures=0)

    def summary(self):
        with self.lock:
            return dict(mode=self.mode, remaining=self.remaining,
                        active_connections=len(self.connections), **self.counters)

    def change(self, mode, count=1):
        if mode not in ('online', 'offline', 'hold_ack', 'drop_ack') or not 1 <= count <= 100:
            raise ValueError('invalid_mode')
        with self.lock:
            self.mode = mode
            self.remaining = count if mode in ('hold_ack', 'drop_ack') else 0
            if mode == 'hold_ack':
                self.release.clear()
            else:
                self.release.set()
            connections = list(self.connections) if mode == 'offline' else []
        for connection in connections:
            connection.close()

    def reply_action(self, eligible):
        with self.lock:
            if not eligible:
                return 'online'
            self.counters['eligible_replies'] += 1
            if self.remaining < 1 or self.mode not in ('hold_ack', 'drop_ack'):
                return 'online'
            self.remaining -= 1
            action = self.mode
            self.counters['held_replies' if action == 'hold_ack' else 'dropped_replies'] += 1
            return action


STATE = State()


class Connection:
    def __init__(self, client, upstream):
        self.client = client
        self.upstream = upstream
        self.pending = deque()
        self.lock = threading.Lock()
        self.closed = threading.Event()

    def close(self):
        self.closed.set()
        for peer in (self.client, self.upstream):
            try:
                peer.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            peer.close()

    def forward(self, source, destination, requests):
        frames = Frames()
        try:
            while not self.closed.is_set():
                chunk = source.recv(65536)
                if not chunk:
                    break
                for raw, value, prefix in frames.feed(chunk):
                    if requests:
                        with self.lock:
                            self.pending.append(is_permission_notification(value))
                    elif prefix not in ('>', '|'):
                        with self.lock:
                            eligible = self.pending.popleft() if self.pending else False
                        action = STATE.reply_action(is_successful_permission_reply(eligible, value, prefix))
                        if action == 'drop_ack':
                            return
                        if action == 'hold_ack' and not STATE.release.wait(30):
                            return
                    destination.sendall(raw)
        except OSError:
            # Offline/ACK-loss intentionally close sockets; these are not RESP failures.
            pass
        except (ValueError, TypeError):
            with STATE.lock:
                STATE.counters['protocol_failures'] += 1
        finally:
            self.close()


class RedisHandler(socketserver.BaseRequestHandler):
    def handle(self):
        with STATE.lock:
            if STATE.mode == 'offline':
                STATE.counters['rejected'] += 1
                return
        try:
            upstream = socket.create_connection(('127.0.0.1', self.server.target_port), timeout=2)
            upstream.settimeout(None)
        except OSError:
            with STATE.lock:
                STATE.counters['rejected'] += 1
            return
        connection = Connection(self.request, upstream)
        with STATE.lock:
            if STATE.mode == 'offline':
                connection.close()
                STATE.counters['rejected'] += 1
                return
            STATE.connections.add(connection)
            STATE.counters['accepted'] += 1
        request_thread = threading.Thread(target=connection.forward,
                                          args=(self.request, upstream, True), daemon=True)
        request_thread.start()
        try:
            connection.forward(upstream, self.request, False)
        finally:
            connection.close()
            request_thread.join(timeout=2)
            with STATE.lock:
                STATE.connections.discard(connection)


class RedisServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


class ControlHandler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def response(self, status, data):
        raw = json.dumps(data, separators=(',', ':')).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        self.response(200, STATE.summary()) if self.path == '/state' \
            else self.response(404, {'error': 'unknown_control'})

    def do_POST(self):
        try:
            if self.path == '/release':
                STATE.change('online')
            elif self.path == '/mode':
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 2048:
                    raise ValueError('invalid_control_size')
                request = json.loads(self.rfile.read(length))
                STATE.change(request['mode'], int(request.get('count', 1)))
            else:
                self.response(404, {'error': 'unknown_control'})
                return
            self.response(200, STATE.summary())
        except (ValueError, KeyError, TypeError):
            self.response(400, {'error': 'invalid_control'})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--listen-port', type=int, default=16497)
    parser.add_argument('--control-port', type=int, default=16498)
    parser.add_argument('--target-port', type=int, default=16397)
    args = parser.parse_args()
    if not all(1024 < port < 65536 for port in
               (args.listen_port, args.control_port, args.target_port)) \
            or len({args.listen_port, args.control_port, args.target_port}) != 3 \
            or args.target_port == 6379:
        raise SystemExit('isolated_high_loopback_ports_required')
    proxy = RedisServer(('127.0.0.1', args.listen_port), RedisHandler)
    proxy.target_port = args.target_port
    control = ThreadingHTTPServer(('127.0.0.1', args.control_port), ControlHandler)
    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    threads = [threading.Thread(target=server.serve_forever, daemon=True)
               for server in (proxy, control)]
    for thread in threads:
        thread.start()
    print(json.dumps({'proxy': 'started', 'loopback_only': True,
                      'payload_logging': False, 'target_port': args.target_port}), flush=True)
    stop.wait()
    STATE.change('offline')
    for server in (proxy, control):
        server.shutdown()
        server.server_close()


if __name__ == '__main__':
    main()
