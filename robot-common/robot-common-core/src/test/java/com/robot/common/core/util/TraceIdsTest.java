package com.robot.common.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TraceIdsTest {
    private static final int MAX_TRACE_CHARACTERS = 64;
    private static final int OVER_TRACE_CHARACTER_LIMIT = 65;

    @Test
    void legalOneToSixtyFourCharacterIdsArePreservedExactly() {
        for (String candidate :
                new String[] {"a", "TRACE_2026-10", "Z".repeat(MAX_TRACE_CHARACTERS)}) {
            assertEquals(candidate, TraceIds.resolve(candidate));
        }
    }

    @Test
    void missingOversizedAndInjectedIdsAreReplacedWithoutEcho() {
        for (String candidate :
                new String[] {
                    null,
                    "",
                    " ",
                    "a".repeat(OVER_TRACE_CHARACTER_LIMIT),
                    "trace\npassword=fixture",
                    "https://downstream.fixture?token=fixture",
                    "trace:tenant",
                    "链路",
                    " leading"
                }) {
            String resolved = TraceIds.resolve(candidate);
            assertTrue(resolved.matches("[a-f0-9]{32}"));
            assertNotEquals(candidate, resolved);
        }
    }
}
