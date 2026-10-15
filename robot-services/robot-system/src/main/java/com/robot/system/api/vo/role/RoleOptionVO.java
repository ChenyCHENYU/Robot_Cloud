package com.robot.system.api.vo.role;

/**
 * 角色下拉选项（/sys/roles/options）。
 *
 * @param id 记录ID
 * @param name 显示名称
 * @param code 业务编码
 */
public record RoleOptionVO(String id, String name, String code) {}
