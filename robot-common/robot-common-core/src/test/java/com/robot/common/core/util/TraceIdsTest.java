package com.robot.common.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceIdsTest {
    @Test
    void legalOneToSixtyFourCharacterIdsArePreservedExactly() {
        for (String candidate : new String[]{"a", "TRACE_2026-10", "Z".repeat(64)}) {
            assertEquals(candidate, TraceIds.resolve(candidate));
        }
    }

    @Test
    void missingOversizedAndInjectedIdsAreReplacedWithoutEcho() {
        for (String candidate : new String[]{null, "", " ", "a".repeat(65), "trace\npassword=fixture",
                "https://downstream.fixture?token=fixture", "trace:tenant", "链路", " leading"}) {
            String resolved = TraceIds.resolve(candidate);
            assertTrue(resolved.matches("[a-f0-9]{32}"));
            assertNotEquals(candidate, resolved);
        }
    }
}
