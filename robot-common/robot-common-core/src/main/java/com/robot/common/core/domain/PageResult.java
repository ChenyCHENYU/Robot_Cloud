package com.robot.common.core.domain;

import java.io.Serial;
import java.util.Collections;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 分页结果。
 *
 * <p>字段契约与 Robot_Admin 前端 {@code PageResult<T>} 完全一致： {@code { list, total, page, pageSize }}（前端读取
 * data.list / data.total）。
 *
 * @param <T> 单条响应的数据类型
 */
@Data
@EqualsAndHashCode(doNotUseGetters = true)
@NoArgsConstructor
public class PageResult<T> {

    @Serial private static final long serialVersionUID = 1L;

    /** 当前页数据。 */
    private List<T> list = Collections.emptyList();

    /** 总条数。 */
    private long total;

    /** 当前页码。 */
    private long page;

    /** 每页条数。 */
    private long pageSize;

    /**
     * Captures page metadata and a defensive snapshot of its rows.
     *
     * @param list page rows; null is normalized to an empty page
     * @param total total matching rows
     * @param page selected page
     * @param pageSize requested page size
     */
    public PageResult(List<T> list, long total, long page, long pageSize) {
        setList(list);
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }

    /**
     * Replaces the row snapshot without retaining caller-owned mutable state.
     *
     * @param rows page rows; null is normalized to empty
     */
    public void setList(List<T> rows) {
        list =
                rows == null
                        ? Collections.emptyList()
                        : Collections.unmodifiableList(new java.util.ArrayList<>(rows));
    }

    /**
     * @return immutable row view, with row element semantics defined by the payload contract
     */
    public List<T> getList() {
        return Collections.unmodifiableList(list);
    }

    /**
     * Constructs the stable frontend page envelope.
     *
     * @param <T> row type
     * @param list page rows
     * @param total total matching rows
     * @param page selected page
     * @param pageSize bounded page size
     * @return page envelope
     */
    public static <T> PageResult<T> of(List<T> list, long total, long page, long pageSize) {
        return new PageResult<>(list, total, page, pageSize);
    }

    /**
     * Constructs an empty page without altering the requested page metadata.
     *
     * @param <T> row type
     * @param page selected page
     * @param pageSize bounded page size
     * @return empty page envelope
     */
    public static <T> PageResult<T> empty(long page, long pageSize) {
        return new PageResult<>(Collections.emptyList(), 0L, page, pageSize);
    }
}
