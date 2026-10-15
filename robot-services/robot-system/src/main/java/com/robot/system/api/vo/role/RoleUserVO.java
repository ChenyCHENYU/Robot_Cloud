package com.robot.system.api.vo.role;

/**
 * 角色成员视图（/sys/roles/{id}/users；status: active|disabled）。
 *
 * @param id 记录ID
 * @param username 平台账号登录名
 * @param name 显示名称
 * @param status 账号与当前公司成员均启用时active，否则disabled
 */
public record RoleUserVO(String id, String username, String name, String status) {}
