package com.robot.common.core.domain;

import com.robot.common.core.constant.RobotConstants;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;

/**
 * 分页查询基类。
 *
 * <p>字段名 {@code page} / {@code pageSize} 与 Robot_Admin 前端 GET 查询参数一一对应；
 * 不接受前端直接传入 orderBy（排序白名单由各业务 Query 自行声明），防止 SQL 注入。
 */
@Data
@NoArgsConstructor
public class PageQuery {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 页码，从 1 开始。 */
    private Integer page = RobotConstants.DEFAULT_PAGE;

    /** 每页条数。 */
    private Integer pageSize = RobotConstants.DEFAULT_PAGE_SIZE;

    public int pageValue() {
        if (page == null || page < 1) {
            return RobotConstants.DEFAULT_PAGE;
        }
        return page;
    }

    public int pageSizeValue() {
        if (pageSize == null || pageSize < 1) {
            return RobotConstants.DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, RobotConstants.MAX_PAGE_SIZE);
    }

    /** offset 起点（供手写 SQL LIMIT 使用）。 */
    public long offset() {
        return (long) (pageValue() - 1) * pageSizeValue();
    }
}
