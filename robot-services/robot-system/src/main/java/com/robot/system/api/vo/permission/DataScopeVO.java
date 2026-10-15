package com.robot.system.api.vo.permission;

/**
 * 数据范围视图（module=* 表示全域；scope=all/custom/department_below/department/self）。
 *
 * @param module 数据范围模块
 * @param scope 权威版本或数据范围键
 */
public record DataScopeVO(String module, String scope) {}
