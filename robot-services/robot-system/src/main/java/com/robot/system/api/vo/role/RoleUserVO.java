package com.robot.system.api.vo.role;

/**
 * 角色成员视图（/sys/roles/{id}/users；status: active|disabled）。
 */
public record RoleUserVO(
        String id,
        String username,
        String name,
        String status) {
}
