package com.robot.system.api.vo.cache;

import java.util.List;

/**
 * 到期完成记录与状态绑定预览，零数据库写入。
 *
 * @param events 符合当前治理条件的通知快照
 * @param planHash 绑定当前事实快照的确认摘要
 */
public record CacheOutboxArchivePreview(List<CacheOutboxEventView> events, String planHash) {
    /**
     * Snapshot input and output collections so caller mutations cannot alter a published tree/plan.
     */
    public CacheOutboxArchivePreview {
        events = events == null ? null : List.copyOf(events);
    }
    /**
     * 创建或返回该类型的明确协作结果。
     *
     * @return 当前事实派生的结果
     */
    public List<CacheOutboxEventView> events() {
        return events == null ? null : List.copyOf(events);
    }
}
