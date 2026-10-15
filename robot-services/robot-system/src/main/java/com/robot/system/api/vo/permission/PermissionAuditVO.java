package com.robot.system.api.vo.permission;

import java.time.LocalDateTime;

/**
 * 成功审计白名单，不回传内部公司或治理列。
 *
 * @param id 记录ID
 * @param actorUserNo 操作人登录名
 * @param action 审计动作
 * @param target 审计目标
 * @param detail 审计详情
 * @param createDateTime 创建时间
 */
public record PermissionAuditVO(
        String id,
        String actorUserNo,
        String action,
        String target,
        String detail,
        LocalDateTime createDateTime) {}
