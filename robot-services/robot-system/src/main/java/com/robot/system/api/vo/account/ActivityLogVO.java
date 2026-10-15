package com.robot.system.api.vo.account;

/**
 * 操作动态视图（/account/activity-logs；映射权限审计日志）。
 *
 * @param id 记录ID
 * @param time 时间显示值
 * @param actionType 审计动作类型
 * @param module 数据范围模块
 * @param description 显示说明
 * @param ip 请求来源地址
 * @param result 操作结果显示值
 */
public record ActivityLogVO(
        String id,
        String time,
        String actionType,
        String module,
        String description,
        String ip,
        String result) {}
