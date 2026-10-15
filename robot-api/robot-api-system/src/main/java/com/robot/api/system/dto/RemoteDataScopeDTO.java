package com.robot.api.system.dto;

import java.io.Serializable;

/**
 * 数据权限范围跨服务视图（module=* 表示全域）。
 *
 * @param module 业务模块，星号表示全域
 * @param scope 服务端计算的有效范围摘要
 */
public record RemoteDataScopeDTO(String module, String scope) implements Serializable {}
