package com.robot.system.api.vo.permission;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 权限树节点（/sys/permissions/tree：菜单节点 type=directory/menu，按钮节点 type=button）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PermissionNode(
        String id,
        String parentId,
        String type,
        String name,
        String permCode,
        String menuId,
        List<PermissionNode> children) {
}
