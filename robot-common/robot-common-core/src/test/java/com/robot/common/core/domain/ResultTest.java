package com.robot.common.core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Result 契约测试：成功码 0（前端 BUSINESS_SUCCESS_CODES 兼容 activate 强校验）。
 */
class ResultTest {

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
        Result<Void> result = Result.failed(ResultCode.UNAUTHORIZED,
                ResultCode.UNAUTHORIZED.getMessage(), "trace-001");

        assertEquals(401, result.getCode());
        assertEquals("trace-001", result.getTraceId());
        assertEquals(ResultCode.UNAUTHORIZED.getMessage(), result.getMsg());
    }

    @Test
    @DisplayName("分页结果字段契约：list/total/page/pageSize")
    void pageResultShouldMatchFrontendContract() {
        PageResult<String> page = PageResult.of(java.util.List.of("a", "b"), 42, 3, 20);

        assertEquals(2, page.getList().size());
        assertEquals(42, page.getTotal());
        assertEquals(3, page.getPage());
        assertEquals(20, page.getPageSize());
    }

    @Test
    @DisplayName("分页参数防御：非法值回落默认，超大页截断")
    void pageQueryShouldClampInvalidValues() {
        PageQuery query = new PageQuery();
        query.setPage(0);
        query.setPageSize(99999);
        assertEquals(1, query.pageValue());
        assertEquals(200, query.pageSizeValue());

        query.setPage(5);
        query.setPageSize(null);
        assertEquals(5, query.pageValue());
        assertEquals(10, query.pageSizeValue());
        assertEquals(40, query.offset());
    }
}
