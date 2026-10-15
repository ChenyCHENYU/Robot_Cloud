package com.robot.system.api.vo.dict;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.robot.system.api.entity.SysDictItem;
import com.robot.system.api.entity.SysDictType;
import java.util.List;

/**
 * 兼容 Admin 与既有 label/value 字段；revision 用于下一次写入。
 *
 * @param id 记录ID
 * @param type 资源类型
 * @param parentId 父节点ID；根节点可为空
 * @param code 业务编码
 * @param name 显示名称
 * @param remark 说明
 * @param label 显示标签
 * @param value 待校验或快照复制的输入值
 * @param dictLabel dictLabel协作输入
 * @param dictValue dictValue协作输入
 * @param sort 排序号
 * @param status 状态，1有效/0停用
 * @param revision 当前聚合版本
 * @param children 子节点快照；叶子可为空
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DictNode(
        String id,
        String type,
        String parentId,
        String code,
        String name,
        String remark,
        String label,
        String value,
        String dictLabel,
        String dictValue,
        Integer sort,
        Integer status,
        Integer revision,
        List<DictNode> children) {
    /**
     * 从字典类型及子项快照构造树节点。
     *
     * @param type 资源类型
     * @param children 子节点快照；叶子可为空
     * @return 当前事实派生的结果
     */
    public static DictNode ofType(SysDictType type, List<DictNode> children) {
        return new DictNode(
                type.getId(),
                "type",
                null,
                type.getTypeCode(),
                type.getTypeName(),
                type.getRemark(),
                null,
                null,
                null,
                null,
                0,
                type.getStatusFlag(),
                type.getRevision(),
                children);
    }

    /**
     * 从字典项构造叶子节点。
     *
     * @param item item协作输入
     * @return 当前事实派生的结果
     */
    public static DictNode ofItem(SysDictItem item) {
        return new DictNode(
                item.getId(),
                "item",
                item.getTypeId(),
                item.getItemCode(),
                item.getItemLabel(),
                item.getRemark(),
                item.getItemLabel(),
                item.getItemValue(),
                item.getItemLabel(),
                item.getItemValue(),
                item.getSortNo(),
                item.getStatusFlag(),
                item.getRevision(),
                null);
    }
    /**
     * Snapshot input and output collections so caller mutations cannot alter a published tree/plan.
     */
    public DictNode {
        children = children == null ? null : List.copyOf(children);
    }
    /**
     * 创建或返回该类型的明确协作结果。
     *
     * @return 当前事实派生的结果
     */
    public List<DictNode> children() {
        return children == null ? null : List.copyOf(children);
    }
}
