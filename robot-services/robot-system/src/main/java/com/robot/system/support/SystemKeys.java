package com.robot.system.support;

import com.robot.common.redis.key.RedisKeys;

/**
 * 系统管理域 Redis Key 契约（业务键归属服务自身定义，不进 common）。
 */
public final class SystemKeys {

    private SystemKeys() {
    }

    /**
     * 权限码缓存（版本化键）：version 必须是版本的<b>实际值</b>（epoch 整数），
     * 由调用方经 RedisHelper.readVersion 读取——不得拼接版本键名
     * （RC05 历史缺陷：键名常量导致撤权永不失效）。
     */
    public static String userPermissions(String companyId, String userId, long version) {
        return RedisKeys.base("system") + ":permissions:"
                + RedisKeys.requireSafe(companyId) + ":" + RedisKeys.requireSafe(userId)
                + ":v" + version;
    }

    /** 权限缓存版本键（7 天 TTL 滚动续期，等价持久键已按 standards/20 登记说明）。 */
    public static String permissionVersion() {
        return RedisKeys.base("system") + ":perm-version";
    }

    /** 字典缓存。 */
    public static String dictCache(String dictType) {
        return RedisKeys.base("system") + ":dict:" + RedisKeys.requireSafe(dictType);
    }
}
