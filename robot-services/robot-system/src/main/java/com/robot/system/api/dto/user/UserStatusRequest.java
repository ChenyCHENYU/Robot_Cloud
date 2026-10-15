package com.robot.system.api.dto.user;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 公司成员启停命令；expectedRevision取自用户详情的membershipRevision。
 *
 * @author Robot Cloud
 * @since 1.0.0-SNAPSHOT
 * @param status 状态，1有效/0停用
 * @param expectedRevision 客户端期望的聚合版本
 */
public record UserStatusRequest(
        @NotNull @Min(0) @Max(1) Integer status, @NotNull @Min(0) Integer expectedRevision) {}
