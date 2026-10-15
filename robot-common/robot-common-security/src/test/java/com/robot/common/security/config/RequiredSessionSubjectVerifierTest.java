package com.robot.common.security.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.common.security.session.SessionStateVerifier;
import com.robot.common.security.session.SessionSubjectVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class RequiredSessionSubjectVerifierTest {
    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(RobotSecurityAutoConfiguration.class))
                    .withBean(ObjectMapper.class, ObjectMapper::new)
                    .withBean(SessionStateVerifier.class, () -> () -> {});

    @Test
    void servletServiceWithoutSubjectRevalidationCannotStart() {
        runner.run(
                context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("SessionSubjectVerifier");
                });
    }

    @Test
    void subjectVerifierSatisfiesRequiredAssembly() {
        runner.withBean(SessionSubjectVerifier.class, () -> (user, fingerprint) -> true)
                .run(context -> assertThat(context).hasNotFailed());
    }
}
