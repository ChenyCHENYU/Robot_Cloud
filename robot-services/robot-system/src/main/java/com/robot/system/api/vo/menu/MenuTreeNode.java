package com.robot.system.api.vo.menu;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 菜单树节点（/sys/menus 数据源；字段与前端 MenuData 契约一致）。
 * record 即契约：字段名即 JSON 键，编译期防漂移。
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
        List<MenuTreeNode> children) {
}
