package com.robot.system.api.vo.permission;

/**
 * 权限码清单项（/sys/permissions）。
 */
public record PermissionItem(
        String id,
        String menuId,
        String name,
        String permCode,
        String type) {
}
