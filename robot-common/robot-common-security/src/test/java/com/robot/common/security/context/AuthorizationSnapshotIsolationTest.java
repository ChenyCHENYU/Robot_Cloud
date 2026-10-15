package com.robot.common.security.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.common.security.config.RobotSecurityProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthorizationSnapshotIsolationTest {
    @Test
    void loginUserBuilderAndJsonSetterDetachRoleCodes() throws Exception {
        var source = new ArrayList<>(List.of("USER"));
        var user = LoginUser.builder().userId("user").roleCodes(source).build();
        source.add("SUPER_ADMIN");
        assertEquals(List.of("USER"), user.getRoleCodes());
        assertThrows(
                UnsupportedOperationException.class, () -> user.getRoleCodes().add("SUPER_ADMIN"));
        user.setRoleCodes(source);
        source.clear();
        assertEquals(List.of("USER", "SUPER_ADMIN"), user.getRoleCodes());
        var mapper = new ObjectMapper();
        var hydrated = mapper.readValue(mapper.writeValueAsString(user), LoginUser.class);
        assertEquals(user.getRoleCodes(), hydrated.getRoleCodes());
        hydrated.setRoleCodes(null);
        assertEquals(List.of(), hydrated.getRoleCodes());
        assertEquals(List.of(), LoginUser.builder().userId("user").build().getRoleCodes());
    }

    @Test
    void exclusionConfigurationRetainsWritableBindingWithoutSharingTheInputList() {
        var source = new ArrayList<>(List.of("/auth/login"));
        var properties = new RobotSecurityProperties();
        properties.setExcludePaths(source);
        source.add("/**");
        assertEquals(List.of("/auth/login"), properties.getExcludePaths());
        assertThrows(
                UnsupportedOperationException.class, () -> properties.getExcludePaths().add("/**"));
    }
}
