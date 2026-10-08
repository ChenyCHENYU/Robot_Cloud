package com.robot.system.api.vo.account;

/**
 * 安全设置项视图（/account/security-settings）。
 */
public record SecuritySettingVO(
        String key,
        String label,
        String description,
        String icon,
        boolean enabled) {
}
