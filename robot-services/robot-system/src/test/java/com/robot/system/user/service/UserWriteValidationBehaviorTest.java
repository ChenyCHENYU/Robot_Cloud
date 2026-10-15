package com.robot.system.user.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robot.common.core.exception.ServiceException;
import com.robot.system.api.dto.user.UserSaveRequest;
import com.robot.system.api.entity.SysUser;
import org.junit.jupiter.api.Test;

class UserWriteValidationBehaviorTest {
    private static final int MAX_ACCOUNT_NAME_LENGTH = 30;
    private static final int MAX_EMAIL_LENGTH = 100;
    private static final int MAX_PHONE_LENGTH = 20;
    private static final int THREE_BYTE_PASSWORD_OVERFLOW_CHARACTERS = 25;

    @Test
    void invalidValuesAndAccountRevisionBoundariesFailBeforeAnyPersistence() {
        for (String value :
                new String[] {
                    null, "a", "contains space", "a".repeat(MAX_ACCOUNT_NAME_LENGTH + 1)
                }) {
            assertThrows(ServiceException.class, () -> UserWriteValidation.validateUsername(value));
        }
        UserWriteValidation.validateUsername("valid_123");
        UserWriteValidation.assertUnchangedUsername("alice", "alice");
        assertThrows(
                ServiceException.class,
                () -> UserWriteValidation.assertUnchangedUsername("alice", "other"));
        assertThrows(
                ServiceException.class,
                () -> UserWriteValidation.assertMutableAccount("admin", "移出"));
        UserWriteValidation.assertMutableAccount("alice", "移出");
        for (Integer value : new Integer[] {null, 0, 1}) {
            UserWriteValidation.validateStatus(value);
        }
        assertThrows(ServiceException.class, () -> UserWriteValidation.validateStatus(2));
        UserWriteValidation.validateProfile(null, null, null);
        UserWriteValidation.validateProfile(
                "alice", "a".repeat(MAX_EMAIL_LENGTH), "1".repeat(MAX_PHONE_LENGTH));
        assertThrows(
                ServiceException.class, () -> UserWriteValidation.validateProfile(" ", null, null));
        assertThrows(
                ServiceException.class,
                () ->
                        UserWriteValidation.validateProfile(
                                "a".repeat(MAX_ACCOUNT_NAME_LENGTH + 1), null, null));
        assertThrows(
                ServiceException.class,
                () ->
                        UserWriteValidation.validateProfile(
                                null, "a".repeat(MAX_EMAIL_LENGTH + 1), null));
        assertThrows(
                ServiceException.class,
                () ->
                        UserWriteValidation.validateProfile(
                                null, null, "1".repeat(MAX_PHONE_LENGTH + 1)));
        for (Integer value : new Integer[] {null, -1, 2}) {
            assertThrows(
                    ServiceException.class,
                    () -> UserWriteValidation.assertRevision(1, value, "revision"));
        }
        UserWriteValidation.assertRevision(1, 1, "revision");
        assertThrows(
                ServiceException.class,
                () ->
                        UserWriteValidation.validatePassword(
                                "汉".repeat(THREE_BYTE_PASSWORD_OVERFLOW_CHARACTERS)));
    }

    @Test
    void omittedSameAndExplicitNullProfileValuesHaveDistinctPatchMeaning() {
        var user = new SysUser();
        user.setNickname("Alice");
        user.setEmail("alice@example.test");
        user.setPhone("123");
        assertFalse(UserWriteValidation.hasProfileChanges(user, new UserSaveRequest()));
        var patch = new UserSaveRequest();
        patch.setNickname("Alice");
        patch.setEmail("alice@example.test");
        patch.setPhone("123");
        assertFalse(UserWriteValidation.hasProfileChanges(user, patch));
        patch.setPhone(null);
        assertTrue(UserWriteValidation.hasProfileChanges(user, patch));
        patch = new UserSaveRequest();
        patch.setEmail(null);
        assertTrue(UserWriteValidation.hasProfileChanges(user, patch));
        patch = new UserSaveRequest();
        patch.setNickname("New");
        assertTrue(UserWriteValidation.hasProfileChanges(user, patch));
    }
}
