package com.robot.system.api.vo.account;

/**
 * 安全设置项视图（/account/security-settings）。
 *
 * @param key 配置项或缓存键
 * @param label 显示标签
 * @param description 显示说明
 * @param icon 图标标识
 * @param enabled 能力当前是否实现/启用
 */
public record SecuritySettingVO(
        String key, String label, String description, String icon, boolean enabled) {}
