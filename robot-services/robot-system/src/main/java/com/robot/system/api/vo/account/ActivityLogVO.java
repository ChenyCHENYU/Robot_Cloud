package com.robot.system.api.vo.account;

/**
 * 操作动态视图（/account/activity-logs；映射权限审计日志）。
 */
public record ActivityLogVO(
        String id,
        String time,
        String actionType,
        String module,
        String description,
        String ip,
        String result) {
}
