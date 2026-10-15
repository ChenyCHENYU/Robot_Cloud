package com.robot.api.system.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class RemoteUserSensitiveOutputTest {
    @Test
    void hidesSummaryAndNestedOutputButPreservesTrustedAuthenticationJson() throws Exception {
        String secret = "synthetic-password-hash-that-must-not-be-logged";
        var user = RemoteUserDTO.builder().id("fixture-user").passwordHash(secret).build();
        assertFalse(user.toString().contains(secret));
        assertFalse(List.of(user).toString().contains(secret));
        assertEquals(
                secret,
                new ObjectMapper()
                        .readTree(new ObjectMapper().writeValueAsString(user))
                        .path("passwordHash")
                        .asText());
    }
}
