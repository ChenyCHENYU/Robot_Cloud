package com.robot.system.api.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.robot.system.api.dto.account.PasswordChangeRequest;
import com.robot.system.api.dto.user.UserSaveRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 实际Lombok编译模型及嵌套集合输出不得包含密码或摘要。 */
class SensitiveModelOutputTest {
    @Test
    void credentialInputsAndEntityStringsExcludeSecretsWhileJsonRetainsInput() throws Exception {
        String secret = "synthetic-secret";
        UserSaveRequest user = new UserSaveRequest();
        user.setPassword(secret);
        PasswordChangeRequest change = new PasswordChangeRequest();
        change.setOldPassword(secret);
        change.setNewPassword(secret);
        SysUser account = new SysUser();
        account.setPasswordHash(secret);
        user.setEmail("synthetic-private@example.test");
        user.setPhone("13700001111");
        account.setEmail("synthetic-private@example.test");
        account.setPhone("13700001111");
        var profile = new com.robot.system.api.dto.account.ProfileUpdateRequest();
        profile.setEmail("synthetic-private@example.test");
        profile.setPhone("13700001111");
        assertFalse(List.of(user, change, account).toString().contains(secret));
        assertFalse(
                List.of(user, account, profile)
                        .toString()
                        .contains("synthetic-private@example.test"));
        assertFalse(List.of(user, account, profile).toString().contains("13700001111"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(
                secret, mapper.readTree(mapper.writeValueAsString(user)).get("password").asText());
        assertEquals(
                secret,
                mapper.readTree(mapper.writeValueAsString(change)).get("newPassword").asText());
        assertEquals(
                "synthetic-private@example.test",
                mapper.readTree(mapper.writeValueAsString(user)).get("email").asText());
    }
}
