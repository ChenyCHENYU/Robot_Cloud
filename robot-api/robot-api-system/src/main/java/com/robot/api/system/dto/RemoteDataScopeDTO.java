package com.robot.api.system.dto;

import java.io.Serializable;

/**
 * 数据权限范围跨服务视图（module=* 表示全域）。
 */
public record RemoteDataScopeDTO(
        String module,
        String scope) implements Serializable {
}
