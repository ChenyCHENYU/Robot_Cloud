"""Pure-memory protocol/gate tests; no sockets, database or service process."""
import importlib.util
from pathlib import Path
import threading
import unittest

spec = importlib.util.spec_from_file_location('redis_fault_proxy', Path(__file__).with_name('redis-fault-proxy.py'))
proxy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proxy)


def request(*parts):
    result = b'*' + str(len(parts)).encode() + b'\r\n'
    for part in parts:
        encoded = part.encode()
        result += b'$' + str(len(encoded)).encode() + b'\r\n' + encoded + b'\r\n'
    return result


class ProtocolTest(unittest.TestCase):
    def test_fragmented_pipeline_preserves_frame_order_and_all_bytes(self):
        frames = [request('HELLO', '3'), request('GET', 'auth:family:sample'),
                  request('EVALSHA', 'script-digest', '1', 'robot:local:system:perm-version', '123', '60000')]
        stream = proxy.Frames()
        parsed = []
        raw = b''.join(frames)
        for position in range(0, len(raw), 3):
            parsed.extend(stream.feed(raw[position:position + 3]))
        self.assertEqual(frames, [frame[0] for frame in parsed])
        self.assertEqual([False, False, True], [proxy.is_permission_notification(frame[1]) for frame in parsed])

    def test_gate_matches_only_single_permission_notification_key(self):
        for args in [('EVAL', 'script', '1', 'auth:family:sample', '2'),
                     ('EVAL', 'script', '2', 'robot:local:system:perm-version', 'other'),
                     ('GET', 'robot:local:system:perm-version')]:
            self.assertFalse(proxy.is_permission_notification(proxy.Frames().feed(request(*args))[0][1]))

    def test_resp3_map_push_attribute_and_null_are_independent_frames(self):
        raw = b'%1\r\n+proto\r\n:3\r\n>2\r\n+notice\r\n+item\r\n|1\r\n+ttl\r\n:10\r\n_\r\n$-1\r\n'
        frames = proxy.Frames().feed(raw)
        self.assertEqual(['%', '>', '|', '_', '$'], [frame[2] for frame in frames])
        self.assertEqual(raw, b''.join(frame[0] for frame in frames))

    def test_two_held_replies_release_together_and_count_is_bounded(self):
        state = proxy.State()
        state.change('hold_ack', 2)
        ready = threading.Barrier(3)
        released = []
        def worker():
            self.assertEqual('hold_ack', state.reply_action(True))
            ready.wait(timeout=2)
            released.append(state.release.wait(2))
        threads = [threading.Thread(target=worker) for _ in range(2)]
        for thread in threads:
            thread.start()
        ready.wait(timeout=2)
        self.assertEqual('online', state.reply_action(True))
        state.change('online')
        for thread in threads:
            thread.join(timeout=2)
        self.assertEqual([True, True], released)
        self.assertEqual(2, state.summary()['held_replies'])

    def test_drop_ack_consumes_exactly_one_eligible_reply(self):
        state = proxy.State()
        state.change('drop_ack', 1)
        self.assertEqual('online', state.reply_action(False))
        self.assertEqual('drop_ack', state.reply_action(True))
        self.assertEqual('online', state.reply_action(True))
        self.assertEqual(1, state.summary()['dropped_replies'])

    def test_lua_errors_do_not_consume_gate_before_successful_ack(self):
        state = proxy.State()
        state.change('hold_ack', 1)
        self.assertEqual('online', state.reply_action(
            proxy.is_successful_permission_reply(True, b'NOSCRIPT no cached script', '-')))
        self.assertEqual(1, state.summary()['remaining'])
        self.assertEqual('hold_ack', state.reply_action(
            proxy.is_successful_permission_reply(True, b'1', ':')))
        self.assertFalse(proxy.is_successful_permission_reply(False, b'1', ':'))


if __name__ == '__main__':
    unittest.main()
