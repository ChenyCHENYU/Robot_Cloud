package com.robot.system.api.dto.role;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 精准撤回一条授权，含浏览器快照版本。
 *
 * @param expectedRevision 客户端期望的聚合版本
 */
public record TempAuthorizationRevokeRequest(@NotNull @Min(0) Integer expectedRevision) {}
