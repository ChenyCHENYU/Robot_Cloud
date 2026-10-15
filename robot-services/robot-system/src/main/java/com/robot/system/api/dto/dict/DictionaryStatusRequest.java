package com.robot.system.api.dto.dict;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 定义DictionaryStatusRequest的业务边界或值快照。
 *
 * @param status 状态，1有效/0停用
 * @param revision 当前聚合版本
 */
public record DictionaryStatusRequest(
        @NotNull @Min(0) @Max(1) Integer status, @NotNull @Min(0) Integer revision) {}
