package com.robot.common.core.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ManagementEndpointPolicyTest {
    @Test
    void productionDocumentationAndActuatorHealthBoundariesAreExplicit() {
        for (String path :
                List.of(
                        "/v3/api-docs",
                        "/v3/api-docs/group",
                        "/swagger-ui.html",
                        "/swagger-ui/index.html",
                        "/doc.html",
                        "/webjars/ui.js")) {
            assertTrue(ManagementEndpointPolicy.documentation(path));
        }
        assertFalse(ManagementEndpointPolicy.documentation("/auth/login"));
        assertFalse(ManagementEndpointPolicy.protectedManagement("/actuator/health"));
        assertFalse(ManagementEndpointPolicy.protectedManagement("/actuator/health/readiness"));
        assertFalse(ManagementEndpointPolicy.protectedManagement("/sys/users"));
        assertTrue(ManagementEndpointPolicy.protectedManagement("/actuator/prometheus"));
        assertTrue(ManagementEndpointPolicy.protectedManagement("/actuator/gateway/routes"));
    }

    @Test
    void managementCredentialsRequireExactlyOneExactHeaderAndNeverAcceptAnEmptySecret() {
        String secret = "synthetic-secret";
        assertTrue(ManagementEndpointPolicy.authorized(List.of(secret), secret));
        assertFalse(ManagementEndpointPolicy.authorized(null, secret));
        assertFalse(ManagementEndpointPolicy.authorized(List.of(secret), null));
        assertFalse(
                ManagementEndpointPolicy.authorized(
                        java.util.Arrays.asList((String) null), secret));
        assertFalse(ManagementEndpointPolicy.authorized(List.of(), secret));
        assertFalse(ManagementEndpointPolicy.authorized(List.of(secret, secret), secret));
        assertFalse(ManagementEndpointPolicy.authorized(List.of(""), ""));
        assertFalse(ManagementEndpointPolicy.authorized(List.of(secret + " "), secret));
        assertFalse(
                ManagementEndpointPolicy.authorized(
                        List.of(secret.toUpperCase(java.util.Locale.ROOT)), secret));
    }
}
