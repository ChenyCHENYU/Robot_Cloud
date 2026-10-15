package com.robot.system.api.dto.cache;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 完成历史逻辑归档；不删除数据库内容。confirm必须携带预览的planHash。
 *
 * @param retentionDays 完成记录保留天数
 * @param limit 本次查询/操作的数量上限
 * @param planHash 绑定当前事实快照的确认摘要
 */
public record CacheOutboxArchiveRequest(
        @NotNull @Min(30) @Max(3650) Integer retentionDays,
        @NotNull @Min(1) @Max(500) Integer limit,
        @Pattern(regexp = "[a-f0-9]{64}") String planHash) {}
