package com.robot.common.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JdbcUrlParametersTest {
    @Test
    void acceptsOneExactDecodedValueAndPreservesUnrelatedParameters() {
        assertEquals(
                Optional.of("3000"),
                JdbcUrlParameters.uniqueValue(
                        "jdbc:mysql://db/schema?user=fixture&connectTimeout=3000&sslMode=VERIFY_IDENTITY",
                        "connectTimeout"));
        assertEquals(
                Optional.of("VERIFY_IDENTITY"),
                JdbcUrlParameters.uniqueValue(
                        "jdbc:mysql://db/schema?ssl%4dode=VERIFY%5fIDENTITY", "sslMode"));
        assertTrue(JdbcUrlParameters.uniqueValue(null, "sslMode").isEmpty());
        assertTrue(
                JdbcUrlParameters.uniqueValue(
                                "jdbc:mysql://db/schema?sslMode=VERIFY_IDENTITY", null)
                        .isEmpty());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:mysql://db/schema?connectTimeout=3000&connectTimeout=0",
                "jdbc:mysql://db/schema?connectTimeout=0&connectTimeout=3000",
                "jdbc:mysql://db/schema?connectTimeout=3000&connect%54imeout=0",
                "jdbc:mysql://db/schema?connectTimeout=3000&CONNECTTIMEOUT=0",
                "jdbc:mysql://db/schema?connectTimeout&connectTimeout=3000",
                "jdbc:mysql://db/schema?label=connectTimeout=3000",
                "jdbc:mysql://db/schema?connectTimeout=%ZZ",
                "jdbc:mysql://db/schema?connectTimeout=3000#ignored",
                "jdbc:postgresql://db/schema?connectTimeout=3000",
                "jdbc:mysql://db/schema"
            })
    void rejectsAmbiguousOrMalformedTimeoutSettings(String jdbc) {
        assertTrue(JdbcUrlParameters.uniqueValue(jdbc, "connectTimeout").isEmpty());
    }
}
