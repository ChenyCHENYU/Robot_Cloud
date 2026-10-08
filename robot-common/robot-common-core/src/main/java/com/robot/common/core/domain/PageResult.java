package com.robot.common.core.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.util.Collections;
import java.util.List;

/**
 * 分页结果。
 *
 * <p>字段契约与 Robot_Admin 前端 {@code PageResult<T>} 完全一致：
 * {@code { list, total, page, pageSize }}（前端读取 data.list / data.total）。
 */
@Data
@NoArgsConstructor
public class PageResult<T> {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 当前页数据。 */
    private List<T> list;

    /** 总条数。 */
    private long total;

    /** 当前页码。 */
    private long page;

    /** 每页条数。 */
    private long pageSize;

    public PageResult(List<T> list, long total, long page, long pageSize) {
        this.list = list;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }

    public static <T> PageResult<T> of(List<T> list, long total, long page, long pageSize) {
        return new PageResult<>(list, total, page, pageSize);
    }

    public static <T> PageResult<T> empty(long page, long pageSize) {
        return new PageResult<>(Collections.emptyList(), 0L, page, pageSize);
    }
}
