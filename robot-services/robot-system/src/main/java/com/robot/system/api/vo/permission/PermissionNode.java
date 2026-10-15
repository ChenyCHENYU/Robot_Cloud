package com.robot.system.api.vo.permission;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 权限树节点（/sys/permissions/tree：菜单节点 type=directory/menu，按钮节点 type=button）。
 *
 * @param id 记录ID
 * @param parentId 父节点ID；根节点可为空
 * @param type 资源类型
 * @param name 显示名称
 * @param permCode 精确权限码
 * @param menuId 页面菜单ID
 * @param children 子节点快照；叶子可为空
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
    /**
     * Snapshot input and output collections so caller mutations cannot alter a published tree/plan.
     */
    public PermissionNode {
        children = children == null ? null : List.copyOf(children);
    }
    /**
     * 创建或返回该类型的明确协作结果。
     *
     * @return 当前事实派生的结果
     */
    public List<PermissionNode> children() {
        return children == null ? null : List.copyOf(children);
    }
}
