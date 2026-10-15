package com.robot.system.api.vo.permission;

/**
 * 权限码清单项（/sys/permissions）。
 *
 * @param id 记录ID
 * @param menuId 页面菜单ID
 * @param name 显示名称
 * @param permCode 精确权限码
 * @param type 资源类型
 */
public record PermissionItem(String id, String menuId, String name, String permCode, String type) {}
