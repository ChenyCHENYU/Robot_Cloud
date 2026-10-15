package com.robot.common.web.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robot.common.core.domain.Result;
import com.robot.common.core.domain.ResultCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class BusinessResultMetricsTest {
    private static final int UNKNOWN_AUTH_CODE = 40199;
    private static final int UNKNOWN_CONFLICT_CODE = 40999;
    private static final int FIXED_OUTCOME_COUNT = 8;
    private static final int UNKNOWN_CODE_START = 1000;
    private static final int UNKNOWN_CODE_END = 1100;
    private static final int KNOWN_AND_OTHER_OUTCOMES = 3;
    private static final int UNKNOWN_CODE_COUNT = 100;

    @Test
    void semanticErrorsAndUnknownCodesRemainWithinTheEightFixedOutcomeLabels() {
        var registry = new SimpleMeterRegistry();
        try {
            var advice = new BusinessResultMetrics(registry);
            for (ResultCode code : ResultCode.values()) {
                advice.beforeBodyWrite(
                        Result.failed(code, "fixture-sensitive-message"),
                        null,
                        null,
                        null,
                        null,
                        null);
            }
            advice.beforeBodyWrite(
                    Result.failed(UNKNOWN_AUTH_CODE, "fixture-sensitive-message"),
                    null,
                    null,
                    null,
                    null,
                    null);
            advice.beforeBodyWrite(
                    Result.failed(UNKNOWN_CONFLICT_CODE, "fixture-sensitive-message"),
                    null,
                    null,
                    null,
                    null,
                    null);
            Object unrelated = new Object();
            assertSame(unrelated, advice.beforeBodyWrite(unrelated, null, null, null, null, null));
            assertEquals(FIXED_OUTCOME_COUNT, registry.getMeters().size());
            java.util.Set<String> allowed =
                    java.util.Set.of(
                            "success",
                            "throttled",
                            "authentication",
                            "authorization",
                            "conflict",
                            "validation",
                            "dependency",
                            "other");
            for (var meter : registry.getMeters()) {
                assertEquals(1, meter.getId().getTags().size());
                assertEquals("outcome", meter.getId().getTags().get(0).getKey());
                assertTrue(allowed.contains(meter.getId().getTag("outcome")));
                assertFalse(meter.getId().toString().contains("fixture-sensitive-message"));
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void countsBusinessFailureEvenWhenHttpResponseIsSuccessfulWithOnlyBoundedLabels() {
        var registry = new SimpleMeterRegistry();
        try {
            var advice = new BusinessResultMetrics(registry);
            Object failure = Result.failed(ResultCode.DATA_CONCURRENT_CONFLICT);
            assertSame(failure, advice.beforeBodyWrite(failure, null, null, null, null, null));
            advice.beforeBodyWrite(Result.success(), null, null, null, null, null);
            for (int i = UNKNOWN_CODE_START; i < UNKNOWN_CODE_END; i++) {
                advice.beforeBodyWrite(
                        Result.failed(i, "fixture-secret", null), null, null, null, null, null);
            }
            assertEquals(
                    1,
                    registry.get("robot.http.business.results")
                            .tag("outcome", "conflict")
                            .counter()
                            .count());
            assertEquals(
                    1,
                    registry.get("robot.http.business.results")
                            .tag("outcome", "success")
                            .counter()
                            .count());
            assertEquals(KNOWN_AND_OTHER_OUTCOMES, registry.getMeters().size());
            assertEquals(
                    UNKNOWN_CODE_COUNT,
                    registry.get("robot.http.business.results")
                            .tag("outcome", "other")
                            .counter()
                            .count());
        } finally {
            registry.close();
        }
    }
}
