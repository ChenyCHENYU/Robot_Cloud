package com.robot.system.api.vo.dept;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 部门树节点（/sys/departments；name/label 双字段兼容前端两种取值）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DepartmentNode(
        String id,
        String parentId,
        String name,
        String label,
        Integer sort,
        List<DepartmentNode> children) {
}
