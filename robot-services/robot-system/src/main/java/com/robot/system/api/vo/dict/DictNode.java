package com.robot.system.api.vo.dict;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.robot.system.api.entity.SysDictItem;
import com.robot.system.api.entity.SysDictType;
import java.util.List;

/** 兼容 Admin 与既有 label/value 字段；revision 用于下一次写入。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DictNode(String id, String type, String parentId, String code, String name,
                       String remark, String label, String value, String dictLabel,
                       String dictValue, Integer sort, Integer status, Integer revision,
                       List<DictNode> children) {
    public static DictNode ofType(SysDictType type, List<DictNode> children) {
        return new DictNode(type.getId(), "type", null, type.getTypeCode(), type.getTypeName(),
                type.getRemark(), null, null, null, null, 0, type.getStatusFlag(), type.getRevision(), children);
    }
    public static DictNode ofItem(SysDictItem item) {
        return new DictNode(item.getId(), "item", item.getTypeId(), item.getItemCode(), item.getItemLabel(),
                item.getRemark(), item.getItemLabel(), item.getItemValue(), item.getItemLabel(),
                item.getItemValue(), item.getSortNo(), item.getStatusFlag(), item.getRevision(), null);
    }
}
