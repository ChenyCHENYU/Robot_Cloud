package com.robot.common.security.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.dev33.satoken.exception.SaJsonConvertException;
import cn.dev33.satoken.json.SaJsonTemplateForJackson3;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.strategy.SaJsonStrategy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies the real Sa-Token 1.46 Jackson 3 session wire format and type boundary. */
class SaSessionJsonContractTest {
    private static final String SUBJECT_KEY = "robot-login-user";

    @Test
    void declaredSubjectSurvivesAnActualTypedSessionRoundTrip() {
        var json = new SaJsonTemplateForJackson3();
        var user =
                LoginUser.builder()
                        .userId("user")
                        .contextId("membership")
                        .companyId("company")
                        .tenantId("tenant")
                        .userSecurityVersion(0L)
                        .contextSecurityVersion(0L)
                        .roleCodes(new ArrayList<>(List.of("READER")))
                        .build();
        var session = new SaSession("synthetic-session");
        session.getDataMap().put(SUBJECT_KEY, user);

        String encoded = json.objectToJson(session);
        SaSession restored = json.jsonToObject(encoded, SaSession.class);

        assertThat(SaJsonStrategy.instance.loadSpiAllowTypeList()).contains(LoginUser.class);
        assertThat(restored.get(SUBJECT_KEY)).isInstanceOf(LoginUser.class);
        assertThat((LoginUser) restored.get(SUBJECT_KEY)).isEqualTo(user);
    }

    @Test
    void replacingTheTrustedTypeWithAnUndeclaredClassFailsClosed() {
        var json = new SaJsonTemplateForJackson3();
        var session = new SaSession("synthetic-session");
        session.getDataMap().put(SUBJECT_KEY, LoginUser.builder().userId("user").build());
        String encoded = json.objectToJson(session);
        assertThat(encoded).contains(LoginUser.class.getName());
        String poisoned = encoded.replace(LoginUser.class.getName(), "java.lang.ProcessBuilder");

        assertThatThrownBy(() -> json.jsonToObject(poisoned, SaSession.class))
                .isInstanceOf(SaJsonConvertException.class);
    }
}
