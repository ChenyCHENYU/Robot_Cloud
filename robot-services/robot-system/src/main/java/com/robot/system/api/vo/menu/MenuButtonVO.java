package com.robot.system.api.vo.menu;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 菜单按钮视图（/sys/menu-buttons 数据源）。
 *
 * @param id 记录ID
 * @param menuId 页面菜单ID
 * @param name 显示名称
 * @param permCode 精确权限码
 * @param sort 排序号
 * @param status 状态，1有效/0停用
 * @param permission 精确权限码兼容字段
 * @param remark 说明
 * @param revision 当前聚合版本
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MenuButtonVO(
        String id,
        String menuId,
        String name,
        String permCode,
        Integer sort,
        Integer status,
        String permission,
        String remark,
        Integer revision) {

    /**
     * 初始化当前用例的明确协作依赖或兼容值元组。
     *
     * @param id 记录ID
     * @param menuId 页面菜单ID
     * @param name 显示名称
     * @param permCode 精确权限码
     * @param sort 排序号
     * @param status 状态，1有效/0停用
     */
    public MenuButtonVO(
            String id, String menuId, String name, String permCode, Integer sort, Integer status) {
        this(id, menuId, name, permCode, sort, status, permCode, null, null);
    }
}
