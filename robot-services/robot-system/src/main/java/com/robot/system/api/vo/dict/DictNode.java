package com.robot.system.api.vo.dict;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 字典树节点（/sys/dictionaries：type 形态含 code/name/remark，item 形态含 label/value）。
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
        Integer sort,
        Integer status,
        List<DictNode> children) {

    public static DictNode ofType(String id, String code, String name, String remark,
                                  List<DictNode> children) {
        return new DictNode(id, "type", null, code, name, remark, null, null,
                null, null, children.isEmpty() ? null : children);
    }

    public static DictNode ofItem(String id, String parentId, String label, String value,
                                  Integer sort, Integer status) {
        return new DictNode(id, "item", parentId, null, null, null, label, value,
                sort, status, null);
    }
}
