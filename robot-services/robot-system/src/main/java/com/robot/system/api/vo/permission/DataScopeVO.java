package com.robot.system.api.vo.permission;

/**
 * 数据范围视图（module=* 表示全域；scope=all/custom/department_below/department/self）。
 */
public record DataScopeVO(
        String module,
        String scope) {
}
