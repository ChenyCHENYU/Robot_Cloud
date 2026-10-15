package com.robot.system.api.vo.menu;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 菜单树节点（/sys/menus 数据源；字段与前端 MenuData 契约一致）。 record 即契约：字段名即 JSON 键，编译期防漂移。
 *
 * @param id 记录ID
 * @param parentId 父节点ID；根节点可为空
 * @param type 资源类型
 * @param name 显示名称
 * @param path 菜单路由路径
 * @param component 页面组件路径
 * @param redirect 跳转路径
 * @param permCode 精确权限码
 * @param icon 图标标识
 * @param sort 排序号
 * @param visible 菜单可见标记
 * @param keepAlive 页面缓存标记
 * @param children 子节点快照；叶子可为空
 * @param permission 精确权限码兼容字段
 * @param status 状态，1有效/0停用
 * @param hidden 菜单隐藏标记
 * @param remark 说明
 * @param revision 当前聚合版本
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MenuTreeNode(
        String id,
        String parentId,
        String type,
        String name,
        String path,
        String component,
        String redirect,
        String permCode,
        String icon,
        Integer sort,
        Boolean visible,
        Boolean keepAlive,
        List<MenuTreeNode> children,
        String permission,
        Integer status,
        Integer hidden,
        String remark,
        Integer revision) {

    /**
     * 初始化当前用例的明确协作依赖或兼容值元组。
     *
     * @param id 记录ID
     * @param parentId 父节点ID；根节点可为空
     * @param type 资源类型
     * @param name 显示名称
     * @param path 菜单路由路径
     * @param component 页面组件路径
     * @param redirect 跳转路径
     * @param permCode 精确权限码
     * @param icon 图标标识
     * @param sort 排序号
     * @param visible 菜单可见标记
     * @param keepAlive 页面缓存标记
     * @param children 子节点快照；叶子可为空
     */
    public MenuTreeNode(
            String id,
            String parentId,
            String type,
            String name,
            String path,
            String component,
            String redirect,
            String permCode,
            String icon,
            Integer sort,
            Boolean visible,
            Boolean keepAlive,
            List<MenuTreeNode> children) {
        this(
                id,
                parentId,
                type,
                name,
                path,
                component,
                redirect,
                permCode,
                icon,
                sort,
                visible,
                keepAlive,
                children,
                permCode,
                null,
                Boolean.FALSE.equals(visible) ? 1 : 0,
                null,
                null);
    }
    /**
     * Snapshot input and output collections so caller mutations cannot alter a published tree/plan.
     */
    public MenuTreeNode {
        children = children == null ? null : List.copyOf(children);
    }
    /**
     * 创建或返回该类型的明确协作结果。
     *
     * @return 当前事实派生的结果
     */
    public List<MenuTreeNode> children() {
        return children == null ? null : List.copyOf(children);
    }
}
