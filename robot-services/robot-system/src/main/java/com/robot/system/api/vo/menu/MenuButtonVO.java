package com.robot.system.api.vo.menu;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 菜单按钮视图（/sys/menu-buttons 数据源）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MenuButtonVO(
        String id,
        String menuId,
        String name,
        String permCode,
        Integer sort,
        Integer status) {
}
