package com.robot.common.web.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class NewPasswordValidationTest {
    private static final int CJK_AT_BYTE_LIMIT = 24;
    private static final int BLANK_CREDENTIAL_LENGTH = 10;

    record Required(@ValidNewPassword String value) {}

    record Optional(@ValidNewPassword(optional = true) String value) {}

    @Test
    void rejectsByteOverflowAndMalformedUnicodeButRetainsEditNoChangeSemantics() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(new Required("中".repeat(CJK_AT_BYTE_LIMIT))).isEmpty());
            assertFalse(
                    validator
                            .validate(new Required("中".repeat(CJK_AT_BYTE_LIMIT) + "a"))
                            .isEmpty());
            assertFalse(validator.validate(new Required(null)).isEmpty());
            assertFalse(
                    validator
                            .validate(new Optional(" ".repeat(BLANK_CREDENTIAL_LENGTH)))
                            .isEmpty());
            assertTrue(validator.validate(new Optional(null)).isEmpty());
            assertTrue(validator.validate(new Optional("")).isEmpty());
        }
    }
}
