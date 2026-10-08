package com.robot.system.api.vo.role;

/**
 * 角色下拉选项（/sys/roles/options）。
 */
public record RoleOptionVO(
        String id,
        String name,
        String code) {
}
