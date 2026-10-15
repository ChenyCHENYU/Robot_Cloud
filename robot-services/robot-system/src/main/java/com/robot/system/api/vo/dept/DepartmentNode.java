package com.robot.system.api.vo.dept;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 部门树节点（/sys/departments；name/label 双字段兼容前端两种取值）。
 *
 * @param id 记录ID
 * @param parentId 父节点ID；根节点可为空
 * @param name 显示名称
 * @param label 显示标签
 * @param sort 排序号
 * @param children 子节点快照；叶子可为空
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DepartmentNode(
        String id,
        String parentId,
        String name,
        String label,
        Integer sort,
        List<DepartmentNode> children) {
    /**
     * Snapshot input and output collections so caller mutations cannot alter a published tree/plan.
     */
    public DepartmentNode {
        children = children == null ? null : List.copyOf(children);
    }
    /**
     * 创建或返回该类型的明确协作结果。
     *
     * @return 当前事实派生的结果
     */
    public List<DepartmentNode> children() {
        return children == null ? null : List.copyOf(children);
    }
}
