package com.robot.common.core.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robot.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {
    private static final int MAX_ASCII_CHARACTERS = 32;
    private static final int CJK_BELOW_BYTE_LIMIT = 23;
    private static final int CJK_AT_BYTE_LIMIT = 24;
    private static final int MIXED_CJK_CHARACTERS = 17;
    private static final int MIXED_EMOJI_CHARACTERS = 5;
    private static final int BLANK_CREDENTIAL_LENGTH = 10;
    private static final int OVER_CHARACTER_LIMIT = 33;

    @Test
    void validatesUtf8BoundaryWithoutSilentlyDroppingSuffix() {
        assertTrue(PasswordPolicy.validNewPassword("a".repeat(MAX_ASCII_CHARACTERS)));
        assertTrue(PasswordPolicy.validNewPassword("中".repeat(CJK_BELOW_BYTE_LIMIT) + "ab"));
        assertTrue(PasswordPolicy.validNewPassword("中".repeat(CJK_AT_BYTE_LIMIT)));
        assertFalse(PasswordPolicy.validNewPassword("中".repeat(CJK_AT_BYTE_LIMIT) + "a"));
        assertTrue(
                PasswordPolicy.validNewPassword(
                        "中".repeat(MIXED_CJK_CHARACTERS) + "😀".repeat(MIXED_EMOJI_CHARACTERS)));
        assertTrue(
                PasswordPolicy.validNewPassword(
                        "中".repeat(MIXED_CJK_CHARACTERS)
                                + "😀".repeat(MIXED_EMOJI_CHARACTERS)
                                + "a"));
        assertFalse(
                PasswordPolicy.validNewPassword(
                        "中".repeat(MIXED_CJK_CHARACTERS)
                                + "😀".repeat(MIXED_EMOJI_CHARACTERS)
                                + "ab"));
        assertThrows(
                ServiceException.class,
                () -> PasswordPolicy.requireNewPassword("中".repeat(CJK_AT_BYTE_LIMIT) + "a"));
    }

    @Test
    void keepsCharacterPolicyAndRejectsMalformedUnicode() {
        assertFalse(PasswordPolicy.validNewPassword(null));
        assertFalse(PasswordPolicy.validNewPassword(" ".repeat(BLANK_CREDENTIAL_LENGTH)));
        assertFalse(PasswordPolicy.validNewPassword("short"));
        assertFalse(PasswordPolicy.validNewPassword("a".repeat(OVER_CHARACTER_LIMIT)));
        assertFalse(PasswordPolicy.validNewPassword("abcde\uD800"));
    }
}
