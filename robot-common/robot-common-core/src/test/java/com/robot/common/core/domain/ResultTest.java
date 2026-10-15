package com.robot.common.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Result 契约测试：成功码 0（前端 BUSINESS_SUCCESS_CODES 兼容 activate 强校验）。 */
class ResultTest {
    private static final int UNAUTHORIZED_CODE = 401;
    private static final int MATCHING_ROWS = 42;
    private static final int REQUESTED_PAGE = 3;
    private static final int REQUESTED_PAGE_SIZE = 20;
    private static final int OVERSIZED_PAGE = 99999;
    private static final int MAXIMUM_PAGE_SIZE = 200;
    private static final int SELECTED_PAGE = 5;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int EXPECTED_OFFSET = 40;

    @Test
    @DisplayName("成功响应 code=0 且 message/msg 同值")
    void successShouldUseZeroCodeAndAliasMessage() {
        Result<String> result = Result.success("payload");

        assertEquals(0, result.getCode());
        assertTrue(result.successValue());
        assertEquals("成功", result.getMessage());
        assertEquals("成功", result.getMsg());
        assertEquals("payload", result.getData());
        assertNull(result.getTraceId());
    }

    @Test
    @DisplayName("失败响应携带语义码与 traceId")
    void failedShouldCarryCodeAndTraceId() {
        Result<Void> result =
                Result.failed(
                        ResultCode.UNAUTHORIZED, ResultCode.UNAUTHORIZED.getMessage(), "trace-001");

        assertEquals(UNAUTHORIZED_CODE, result.getCode());
        assertEquals("trace-001", result.getTraceId());
        assertEquals(ResultCode.UNAUTHORIZED.getMessage(), result.getMsg());
    }

    @Test
    @DisplayName("分页结果字段契约：list/total/page/pageSize")
    void pageResultShouldMatchFrontendContract() {
        PageResult<String> page =
                PageResult.of(
                        java.util.List.of("a", "b"),
                        MATCHING_ROWS,
                        REQUESTED_PAGE,
                        REQUESTED_PAGE_SIZE);

        assertEquals(2, page.getList().size());
        assertEquals(MATCHING_ROWS, page.getTotal());
        assertEquals(REQUESTED_PAGE, page.getPage());
        assertEquals(REQUESTED_PAGE_SIZE, page.getPageSize());
    }

    @Test
    @DisplayName("分页参数防御：非法值回落默认，超大页截断")
    void pageQueryShouldClampInvalidValues() {
        PageQuery query = new PageQuery();
        query.setPage(0);
        query.setPageSize(OVERSIZED_PAGE);
        assertEquals(1, query.pageValue());
        assertEquals(MAXIMUM_PAGE_SIZE, query.pageSizeValue());

        query.setPage(SELECTED_PAGE);
        query.setPageSize(null);
        assertEquals(SELECTED_PAGE, query.pageValue());
        assertEquals(DEFAULT_PAGE_SIZE, query.pageSizeValue());
        assertEquals(EXPECTED_OFFSET, query.offset());
    }
}
