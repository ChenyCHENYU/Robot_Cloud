package com.robot.common.security.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

class RobotSecurityPropertiesOutputTest {
    @Test
    void configurationSummaryDoesNotExposeRealServiceCredentialAndConsumerCanStillReadIt() {
        var properties = new RobotSecurityProperties();
        String secret = "synthetic-service-credential-that-must-not-be-logged";
        properties.setInnerSecret(secret);
        assertFalse(properties.toString().contains(secret));
        assertFalse(List.of(properties).toString().contains(secret));
        assertEquals(secret, properties.getInnerSecret());
    }
}
