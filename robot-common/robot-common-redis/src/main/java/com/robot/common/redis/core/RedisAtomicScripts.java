package com.robot.common.redis.core;

import java.time.Duration;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Redis 原子命令脚本集：状态、持久事件队列与单调版本协议。 */
final class RedisAtomicScripts {
    private RedisAtomicScripts() {}

    static final DefaultRedisScript<String> CONSUME =
            new DefaultRedisScript<>(
                    "local v = redis.call('GET', KEYS[1]); "
                            + "if v then redis.call('DEL', KEYS[1]); end; return v",
                    String.class);
    static final DefaultRedisScript<Long> COMPARE_AND_SET =
            new DefaultRedisScript<>(
                    "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                            + "redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]); return 1; "
                            + "end; return 0",
                    Long.class);
    static final DefaultRedisScript<Long> INCREMENT_VERSION =
            new DefaultRedisScript<>(
                    "local v = redis.call('INCR', KEYS[1]); "
                            + "redis.call('PEXPIRE', KEYS[1], ARGV[1]); return v",
                    Long.class);
    static final DefaultRedisScript<Long> READ_VERSION =
            new DefaultRedisScript<>(
                    "local v = redis.call('GET', KEYS[1]); if not v then redis.call('SET', KEYS[1],"
                            + " '1', 'PX', ARGV[1]); return 1; end; local n = tonumber(v); if not n or"
                            + " n < 1 then return redis.error_reply('Invalid version value'); end;"
                            + " return n",
                    Long.class);
    static final Duration VERSION_TTL = Duration.ofDays(7);
    static final DefaultRedisScript<Long> LIMITED_INCREMENT =
            new DefaultRedisScript<>(
                    "local count = tonumber(redis.call('GET', KEYS[1]) or '0'); if count >="
                            + " tonumber(ARGV[1]) then return 0; end; redis.call('INCR', KEYS[1]); if"
                            + " count == 0 then redis.call('PEXPIRE', KEYS[1], ARGV[2]); end; return 1",
                    Long.class);
    static final DefaultRedisScript<Long> STATE_AND_EVENT =
            new DefaultRedisScript<>(
                    "local current = redis.call('GET', KEYS[1]); if (ARGV[1] == 'create' and"
                            + " current) or (ARGV[1] == 'replace' and current ~= ARGV[2]) then return"
                            + " 0; end; if redis.call('HLEN', KEYS[2]) >= tonumber(ARGV[7]) then return"
                            + " -2; end; local dueType = redis.call('TYPE', KEYS[3]).ok; if dueType ~="
                            + " 'none' and dueType ~= 'zset' then return redis.error_reply('Invalid"
                            + " event due storage'); end; if ARGV[3] == '__delete__' then"
                            + " redis.call('DEL', KEYS[1]); else redis.call('SET', KEYS[1], ARGV[3],"
                            + " 'PX', ARGV[4]); end; redis.call('HSET', KEYS[2], ARGV[5], ARGV[6]);"
                            + " redis.call('ZADD', KEYS[3], ARGV[8], ARGV[5]); return 1",
                    Long.class);
    static final DefaultRedisScript<Long> APPEND_EVENT =
            new DefaultRedisScript<>(
                    "if redis.call('HLEN', KEYS[1]) >= tonumber(ARGV[3]) then return -2; end; local"
                            + " dueType = redis.call('TYPE', KEYS[2]).ok; if dueType ~= 'none' and"
                            + " dueType ~= 'zset' then return redis.error_reply('Invalid event due"
                            + " storage'); end; redis.call('HSET', KEYS[1], ARGV[1], ARGV[2]);"
                            + " redis.call('ZADD', KEYS[2], ARGV[4], ARGV[1]); return 1",
                    Long.class);
    static final DefaultRedisScript<Long> ACK_EVENT =
            new DefaultRedisScript<>(
                    "if redis.call('HGET', KEYS[1], ARGV[1]) ~= ARGV[2] then return 0; end; local"
                            + " dueType = redis.call('TYPE', KEYS[2]).ok; if dueType ~= 'none' and"
                            + " dueType ~= 'zset' then return redis.error_reply('Invalid event due"
                            + " storage'); end; redis.call('HDEL', KEYS[1], ARGV[1]);"
                            + " redis.call('ZREM', KEYS[2], ARGV[1]); return 1",
                    Long.class);
    static final DefaultRedisScript<Long> RETRY_EVENT =
            new DefaultRedisScript<>(
                    "if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 1 then redis.call('ZADD',"
                            + " KEYS[2], ARGV[2], ARGV[1]); return 1; end; return 0",
                    Long.class);
    static final DefaultRedisScript<Long> ADVANCE_VERSION =
            new DefaultRedisScript<>(
                    "local v = redis.call('GET', KEYS[1]); local n = ARGV[1]; "
                            + "if v and not string.match(v, '^[1-9][0-9]*$') then "
                            + "return redis.error_reply('Invalid version value'); end; "
                            + "if not v or string.len(n) > string.len(v) or "
                            + "(string.len(n) == string.len(v) and n > v) then "
                            + "redis.call('SET', KEYS[1], n, 'PX', ARGV[2]); return 1; end; "
                            + "redis.call('PEXPIRE', KEYS[1], ARGV[2]); return 0",
                    Long.class);
}
