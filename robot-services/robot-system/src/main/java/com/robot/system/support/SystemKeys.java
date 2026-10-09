package com.robot.system.support;

import com.robot.common.redis.key.RedisKeys;
import com.robot.common.core.util.Digests;
import java.util.Locale;

/**
 * 系统管理域 Redis Key 契约（业务键归属服务自身定义，不进 common）。
 */
public final class SystemKeys {

    private SystemKeys() {
    }

    /**
     * 权限码缓存（版本化键）：version 必须是版本的<b>实际值</b>（epoch 整数），
     * 由调用方读取已提交的数据库 epoch——不得拼接版本键名
     * （RC05 历史缺陷：键名常量导致撤权永不失效）。
     */
    public static String userPermissions(String companyId, String userId, long version) {
        return RedisKeys.base("system") + ":permissions:"
                + RedisKeys.requireSafe(companyId) + ":" + RedisKeys.requireSafe(userId)
                + ":db-v" + version;
    }

    /** 权限缓存版本键（7 天 TTL 滚动续期，等价持久键已按 standards/20 登记说明）。 */
    public static String permissionVersion() {
        return RedisKeys.base("system") + ":perm-version";
    }

    /** 编码可含冒号；大小写归一后散列成固定长度段，避免业务编码改变 Redis 键结构。 */
    public static String dictCache(String dictType) {
        String segment = Digests.sha256Hex(dictType.toLowerCase(Locale.ROOT));
        return RedisKeys.base("system") + ":dict:" + RedisKeys.requireSafe(segment);
    }

    public static String dictCache(String dictType, long version) {
        return dictCache(dictType) + ":db-v" + version;
    }
}
